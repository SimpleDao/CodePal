package com.codepal.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.codepal.model.ChatMessage;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具编排器 —— 统一的工具执行入口
 *
 * <p>借鉴 AutoDev 的 ToolOrchestrator 设计，负责：
 * <ul>
 *   <li>工具执行的统一入口（替代 ToolExecutor 的 switch-case）</li>
 *   <li>策略检查（PolicyEngine）</li>
 *   <li>单工具执行 + 批量并行执行</li>
 *   <li>结果格式化和聚合</li>
 *   <li>执行取消和超时控制</li>
 * </ul>
 *
 * @author CP Multi-Agent
 */
public class ToolOrchestrator {

    private final Project project;
    private final ToolRegistry registry;
    private final PolicyEngine policyEngine;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Future<?>> runningFutures = Collections.synchronizedList(new ArrayList<>());

    private ToolExecutor.ToolConfirmProvider confirmProvider;
    private ToolConfirmManager toolConfirmManager;

    public ToolOrchestrator(Project project, ToolRegistry registry, PolicyEngine policyEngine) {
        this.project = project;
        this.registry = registry;
        this.policyEngine = policyEngine;
    }

    public void setConfirmProvider(ToolExecutor.ToolConfirmProvider provider) {
        this.confirmProvider = provider;
        if (provider instanceof ToolConfirmManager) {
            this.toolConfirmManager = (ToolConfirmManager) provider;
        }
    }

    /**
     * 执行单个工具调用
     */
    public ToolResult execute(ChatMessage.ToolCall toolCall) {
        return execute(toolCall, false);
    }

    /**
     * 执行单个工具调用（可跳过确认，用于 Craft 模式）
     */
    public ToolResult execute(ChatMessage.ToolCall toolCall, boolean skipConfirmation) {
        if (toolCall == null || toolCall.getFunction() == null) {
            return ToolResult.error("无效的工具调用");
        }

        // 每次新调用前重置取消状态 —— 防止用户上次点停止后 cancelled 标志残留，
        // 导致后续所有工具调用都误报"工具执行已取消"。
        // 并发的 cancelAll() 仍能在工具调用之间正确中断下一轮。
        resetCancelled();

        String toolName = toolCall.getFunction().getName();
        String argsStr = toolCall.getFunction().getArguments();

        JsonObject params;
        try {
            if (argsStr != null && !argsStr.isBlank()) {
                try {
                    params = com.google.gson.JsonParser.parseString(argsStr).getAsJsonObject();
                } catch (Exception parseEx) {
                    System.err.println("[ToolOrchestrator] 严格解析失败 tool=" + toolName
                            + " error=" + parseEx.getMessage() + "，尝试sanitize后重试");
                    String sanitized = ToolExecutor.sanitizeJsonControlChars(argsStr);
                    params = com.google.gson.JsonParser.parseString(sanitized).getAsJsonObject();
                }
            } else {
                params = new JsonObject();
            }
        } catch (Exception e) {
            return ToolResult.error("工具参数解析失败: " + e.getMessage());
        }

        Tool tool = registry.getTool(toolName);
        if (tool == null) {
            return ToolResult.error("未知工具: " + toolName);
        }

        if (cancelled.get()) {
            return ToolResult.error("工具执行已取消");
        }

        PolicyEngine.Policy policy = policyEngine.evaluate(toolName, params);

        if (policy == PolicyEngine.Policy.DENY) {
            return ToolResult.error("工具被策略拒绝: " + toolName);
        }

        if (policy == PolicyEngine.Policy.ASK && !skipConfirmation && confirmProvider != null) {
            String confirmContent = buildConfirmContent(toolName, params);
            String level = getConfirmLevel(toolName);
            boolean canTrust = canTrustTool(toolName);

            try {
                Boolean confirmed = confirmProvider.requestConfirm(
                        toolName, confirmContent, level, canTrust, "command").get(300, TimeUnit.SECONDS);
                if (confirmed == null || !confirmed) {
                    return ToolResult.error("用户拒绝执行: " + toolName);
                }
                if (canTrust) {
                    policyEngine.trustSession(toolName, params);
                }
            } catch (Exception e) {
                return ToolResult.error("确认失败: " + e.getMessage());
            }
        }

        // ★ 单工具执行加超时兜底（批量路径已有 120s，单工具路径此前无超时，会永久挂起）。
        // 注意：必须大于内部耗时工具的自有超时（如整 jar 反编译 180s），预留余量。
        final int TOOL_TIMEOUT_SECONDS = 240;
        // params 在本方法内被重新赋值（解析/sanitize 分支），非 effectively final，lambda 需捕获快照
        final JsonObject finalParams = params;
        final Tool finalTool = tool;
        final com.intellij.openapi.project.Project finalProject = project;
        final boolean finalSkip = skipConfirmation;
        java.util.concurrent.CompletableFuture<String> execFuture = new java.util.concurrent.CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                execFuture.complete(finalTool.execute(finalParams, finalProject, finalSkip, toolConfirmManager));
            } catch (Throwable t) {
                execFuture.completeExceptionally(t);
            }
        });
        try {
            String result = execFuture.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ToolResult.success(result);
        } catch (java.util.concurrent.TimeoutException te) {
            execFuture.cancel(true);
            return ToolResult.error("工具执行超时（>" + TOOL_TIMEOUT_SECONDS + "s）：" + toolName);
        } catch (Exception e) {
            return ToolResult.error("工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 并行执行多个工具调用
     */
    public List<ToolResult> executeParallel(List<ChatMessage.ToolCall> toolCalls) {
        return executeParallel(toolCalls, false);
    }

    /**
     * 并行执行多个工具调用
     */
    public List<ToolResult> executeParallel(List<ChatMessage.ToolCall> toolCalls, boolean skipConfirmation) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }

        if (toolCalls.size() == 1) {
            return Collections.singletonList(execute(toolCalls.get(0), skipConfirmation));
        }

        ExecutorService executor = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {}).getClass() == null
                ? Executors.newFixedThreadPool(Math.min(toolCalls.size(), 4))
                : null;

        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();

        for (ChatMessage.ToolCall tc : toolCalls) {
            CompletableFuture<ToolResult> future = new CompletableFuture<>();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    ToolResult result = execute(tc, skipConfirmation);
                    future.complete(result);
                } catch (Exception e) {
                    future.complete(ToolResult.error(e.getMessage()));
                }
            });
            futures.add(future);
        }

        List<ToolResult> results = new ArrayList<>();
        for (CompletableFuture<ToolResult> future : futures) {
            try {
                results.add(future.get(120, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(ToolResult.error("执行超时: " + e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 按依赖顺序串行执行多个工具调用
     */
    public List<ToolResult> executeSequential(List<ChatMessage.ToolCall> toolCalls, boolean skipConfirmation) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolResult> results = new ArrayList<>();
        for (ChatMessage.ToolCall tc : toolCalls) {
            if (cancelled.get()) {
                results.add(ToolResult.error("执行已取消"));
                break;
            }
            results.add(execute(tc, skipConfirmation));
        }
        return results;
    }

    /**
     * 取消当前所有执行
     */
    public void cancelAll() {
        cancelled.set(true);
        ToolExecutor.cancelCurrent();
        synchronized (runningFutures) {
            for (Future<?> f : runningFutures) {
                if (!f.isDone()) f.cancel(true);
            }
            runningFutures.clear();
        }
    }

    public void resetCancelled() {
        cancelled.set(false);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ── 辅助方法 ──

    private String buildConfirmContent(String toolName, JsonObject params) {
        Tool tool = registry.getTool(toolName);
        String desc = tool != null ? tool.getDescription() : toolName;

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(toolName).append("】").append(desc).append("\n\n");
        sb.append("参数：\n");

        if ("run_command".equals(toolName) && params.has("command")) {
            sb.append("  command: ").append(params.get("command").getAsString()).append("\n");
        } else if ("edit_file".equals(toolName)) {
            if (params.has("file_path")) {
                sb.append("  file_path: ").append(params.get("file_path").getAsString()).append("\n");
            }
            sb.append("  (编辑操作)\n");
        } else if ("write_file".equals(toolName) || "create_new_file".equals(toolName)) {
            if (params.has("file_path")) {
                sb.append("  file_path: ").append(params.get("file_path").getAsString()).append("\n");
            }
            sb.append("  (写入文件)\n");
        } else if ("delete_file".equals(toolName)) {
            if (params.has("file_path")) {
                sb.append("  file_path: ").append(params.get("file_path").getAsString()).append("\n");
            }
        } else {
            for (String key : params.keySet()) {
                sb.append("  ").append(key).append(": ");
                String val = params.get(key).toString();
                if (val.length() > 100) val = val.substring(0, 100) + "...";
                sb.append(val).append("\n");
            }
        }

        return sb.toString();
    }

    private String getConfirmLevel(String toolName) {
        Tool tool = registry.getTool(toolName);
        if (tool == null) return "info";

        return switch (tool.getRiskLevel()) {
            case READ_ONLY -> "info";
            case WRITE -> "warning";
            case DANGEROUS -> "danger";
        };
    }

    private boolean canTrustTool(String toolName) {
        Tool tool = registry.getTool(toolName);
        if (tool == null) return false;
        return tool.getRiskLevel() == Tool.RiskLevel.WRITE
                || tool.getRiskLevel() == Tool.RiskLevel.READ_ONLY;
    }

    /**
     * 获取编排器状态
     */
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cancelled", cancelled.get());
        status.put("runningFutures", runningFutures.size());
        status.put("registrySize", registry.size());
        return status;
    }

    /**
     * 工具执行结果
     */
    public static class ToolResult {
        public final boolean success;
        public final String content;
        public final String error;

        private ToolResult(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        public static ToolResult success(String content) {
            return new ToolResult(true, content, null);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, null, error);
        }

        @Override
        public String toString() {
            return success ? content : "错误：" + error;
        }
    }
}
