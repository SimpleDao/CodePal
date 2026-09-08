package com.codepal.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略引擎 —— 三级权限策略（ALLOW / ASK / DENY）
 *
 * <p>借鉴 AutoDev 的 PolicyEngine 设计，负责：
 * <ul>
 *   <li>工具级别的权限控制（基于风险等级）</li>
 *   <li>会话级信任名单（本次会话不再询问）</li>
 *   <li>用户自定义规则（黑名单/白名单）</li>
 *   <li>run_command 命令的细粒度分类（基于 CommandClassifier）</li>
 * </ul>
 *
 * <p>策略优先级（从高到低）：
 * <ol>
 *   <li>DENY 黑名单 → 直接拒绝</li>
 *   <li>ALLOW 白名单 → 直接执行</li>
 *   <li>会话信任名单 → 直接执行</li>
 *   <li>工具风险等级 → READ_ONLY=ALLOW, WRITE=ASK, DANGEROUS=ASK</li>
 * </ol>
 *
 * @author CP Multi-Agent
 */
public class PolicyEngine {

    public enum Policy {
        ALLOW,
        ASK,
        DENY
    }

    private final Project project;
    private final ToolRegistry toolRegistry;

    private final Set<String> allowlist = ConcurrentHashMap.newKeySet();
    private final Set<String> denylist = ConcurrentHashMap.newKeySet();
    private final Set<String> sessionTrusted = ConcurrentHashMap.newKeySet();

    private final Map<String, Policy> toolPolicyCache = new ConcurrentHashMap<>();

    public PolicyEngine(Project project, ToolRegistry toolRegistry) {
        this.project = project;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 评估工具调用的策略
     *
     * @param toolName 工具名
     * @param params   工具参数
     * @return 策略结果：ALLOW / ASK / DENY
     */
    public Policy evaluate(String toolName, JsonObject params) {
        if (toolName == null || toolName.isBlank()) {
            return Policy.DENY;
        }

        if (denylist.contains(toolName)) {
            return Policy.DENY;
        }

        if (allowlist.contains(toolName)) {
            return Policy.ALLOW;
        }

        String trustKey = buildTrustKey(toolName, params);
        if (sessionTrusted.contains(trustKey) || sessionTrusted.contains(toolName)) {
            return Policy.ALLOW;
        }

        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return Policy.DENY;
        }

        if ("run_command".equals(toolName)) {
            return evaluateCommandPolicy(params);
        }

        return switch (tool.getRiskLevel()) {
            case READ_ONLY -> Policy.ALLOW;
            case WRITE -> Policy.ASK;
            case DANGEROUS -> Policy.ASK;
        };
    }

    /**
     * 评估 run_command 命令的细粒度策略
     */
    private Policy evaluateCommandPolicy(JsonObject params) {
        String command = params.has("command") ? params.get("command").getAsString() : "";
        if (command.isBlank()) return Policy.DENY;

        CommandClassifier.Category category = CommandClassifier.classify(command);

        return switch (category) {
            case READ_ONLY -> Policy.ALLOW;
            case CONSOLE -> Policy.ASK;
            case DANGEROUS -> Policy.ASK;
        };
    }

    /**
     * 构建会话信任键（工具名 + 关键参数摘要）
     */
    private String buildTrustKey(String toolName, JsonObject params) {
        if ("run_command".equals(toolName) && params != null && params.has("command")) {
            return "run_command:" + params.get("command").getAsString().trim().toLowerCase();
        }
        return toolName;
    }

    /**
     * 添加工具到白名单
     */
    public void addAllowlist(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            allowlist.add(toolName);
            toolPolicyCache.remove(toolName);
        }
    }

    /**
     * 从白名单移除工具
     */
    public void removeAllowlist(String toolName) {
        allowlist.remove(toolName);
        toolPolicyCache.remove(toolName);
    }

    /**
     * 添加工具到黑名单
     */
    public void addDenylist(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            denylist.add(toolName);
            toolPolicyCache.remove(toolName);
        }
    }

    /**
     * 从黑名单移除工具
     */
    public void removeDenylist(String toolName) {
        denylist.remove(toolName);
        toolPolicyCache.remove(toolName);
    }

    /**
     * 添加到会话信任名单（本次会话内不再询问）
     */
    public void trustSession(String toolName, JsonObject params) {
        String trustKey = buildTrustKey(toolName, params);
        sessionTrusted.add(trustKey);
    }

    /**
     * 检查是否在会话信任名单中
     */
    public boolean isSessionTrusted(String toolName, JsonObject params) {
        String trustKey = buildTrustKey(toolName, params);
        return sessionTrusted.contains(trustKey) || sessionTrusted.contains(toolName);
    }

    /**
     * 清空会话信任名单
     */
    public void clearSessionTrusted() {
        sessionTrusted.clear();
    }

    /**
     * 获取工具的风险等级描述
     */
    public String getRiskDescription(String toolName) {
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) return "未知工具";

        return switch (tool.getRiskLevel()) {
            case READ_ONLY -> "只读操作（安全，自动执行）";
            case WRITE -> "写操作（需确认）";
            case DANGEROUS -> "高风险（必须确认）";
        };
    }

    /**
     * 获取 run_command 命令的危险等级描述
     */
    public String getCommandRiskDescription(String command) {
        CommandClassifier.Category category = CommandClassifier.classify(command);
        return CommandClassifier.describe(category);
    }

    /**
     * 批量评估多个工具调用
     */
    public Map<String, Policy> evaluateBatch(List<ToolCallInfo> calls) {
        Map<String, Policy> results = new LinkedHashMap<>();
        for (ToolCallInfo call : calls) {
            results.put(call.toolName, evaluate(call.toolName, call.params));
        }
        return results;
    }

    /**
     * 获取策略引擎状态
     */
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("allowlistSize", allowlist.size());
        status.put("denylistSize", denylist.size());
        status.put("sessionTrustedSize", sessionTrusted.size());
        status.put("allowlist", new ArrayList<>(allowlist));
        status.put("denylist", new ArrayList<>(denylist));
        return status;
    }

    /**
     * 工具调用信息（用于批量评估）
     */
    public static class ToolCallInfo {
        public final String toolName;
        public final JsonObject params;

        public ToolCallInfo(String toolName, JsonObject params) {
            this.toolName = toolName;
            this.params = params;
        }
    }
}
