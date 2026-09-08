package com.codepal.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.codepal.api.ModelLinkDispatcher;
import com.codepal.enums.EnumsThinkingIntensity;
import com.codepal.model.ChatMessage;
import com.codepal.model.ModelConfig;
import com.codepal.model.ChatRequest;
import com.codepal.settings.CPSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 搜索子智能体 —— 专门负责代码搜索和探索任务
 *
 * 当主智能体需要大量搜索/浏览代码时，将搜索任务交给子智能体，
 * 避免主智能体上下文被大量搜索结果污染。
 *
 * 子智能体独立运行一轮 ReAct 循环（思考→工具调用→观察→…），
 * 最终返回找到的文件路径和关键信息摘要。
 *
 * @author 水龙吟
 * @date 2026-07-02
 */
public class SearchAgent {

    private static final Gson GSON = new Gson();

    /** 最大工具调用轮次（防止无限循环） */
    private static final int MAX_TOOL_CALLS = 30;

    /** 单次等待超时时间（秒） */
    private static final int TIMEOUT_SECONDS = 120;

    /**
     * 搜索进度回调 —— 用于实时更新 UI
     */
    public interface SearchProgressCallback {
        /** 子智能体思考中（流式） */
        void onReasoning(String delta);
        /** 子智能体调用工具 */
        void onToolCall(String toolName, String toolArgs);
        /** 工具返回结果 */
        void onToolResult(String toolName, String result);
        /** 搜索完成 */
        void onComplete(SearchResult result);
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        /** 是否成功 */
        public final boolean success;
        /** 子智能体的总结回答（给主智能体看的） */
        public final String summary;
        /** 找到的关键文件列表（路径+说明） */
        public final List<FileInfo> foundFiles;
        /** 错误信息（失败时） */
        public final String error;

        private SearchResult(boolean success, String summary, List<FileInfo> foundFiles, String error) {
            this.success = success;
            this.summary = summary;
            this.foundFiles = foundFiles;
            this.error = error;
        }

        public static SearchResult ok(String summary, List<FileInfo> foundFiles) {
            return new SearchResult(true, summary, foundFiles, null);
        }

        public static SearchResult error(String msg) {
            return new SearchResult(false, null, new ArrayList<>(), msg);
        }

        /**
         * 纯文本格式（用于返回给主智能体）
         */
        @Override
        public String toString() {
            if (!success) {
                return "搜索失败：" + error;
            }
            // 子智能体的最终回答（已经是精简的路径+行号格式）
            if (summary != null && !summary.isBlank()) {
                return summary.trim();
            }
            // 兜底：直接列出文件路径
            if (foundFiles != null && !foundFiles.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < foundFiles.size(); i++) {
                    FileInfo fi = foundFiles.get(i);
                    sb.append(fi.path);
                    if (fi.description != null && !fi.description.isEmpty()) {
                        sb.append(" — ").append(fi.description);
                    }
                    sb.append("\n");
                }
                return sb.toString().trim();
            }
            return "未找到相关内容";
        }

        /**
         * HTML 格式（用于 UI 展示，像 Trae 的 Relevant Code Snippets 风格）
         */
        public String toHtml() {
            if (!success) {
                return "<div style='color:#F87171;padding:8px 0;'>搜索失败：" + escapeHtml(error) + "</div>";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("<div style='padding:4px 0 8px 0;'>");
            if (foundFiles != null && !foundFiles.isEmpty()) {
                sb.append("<div style='font-size:13px;font-weight:600;margin-bottom:8px;color:");
                sb.append("#E5E7EB");
                sb.append(";'>找到的关键文件</div>");
                for (int i = 0; i < foundFiles.size(); i++) {
                    FileInfo fi = foundFiles.get(i);
                    sb.append("<div style='margin-bottom:8px;padding-left:4px;'>");
                    sb.append("<div style='font-size:12px;color:#9CDCFE;font-family:Consolas,monospace;'>");
                    sb.append("<span style='opacity:0.5;margin-right:6px;'>").append(i + 1).append(".</span>");
                    sb.append(escapeHtml(fi.path));
                    sb.append("</div>");
                    if (fi.description != null && !fi.description.isEmpty()) {
                        sb.append("<div style='font-size:11px;opacity:0.7;margin-top:2px;padding-left:22px;'>");
                        sb.append(escapeHtml(fi.description));
                        sb.append("</div>");
                    }
                    sb.append("</div>");
                }
            } else if (summary != null && !summary.isBlank()) {
                sb.append("<div style='font-size:12px;opacity:0.8;'>").append(escapeHtml(summary)).append("</div>");
            }
            sb.append("</div>");
            return sb.toString();
        }

        private static String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }
    }

    /**
     * 找到的文件信息
     */
    public static class FileInfo {
        public String path;
        public String description;

        public FileInfo(String path, String description) {
            this.path = path;
            this.description = description;
        }
    }

    /**
     * 执行搜索任务（同步阻塞，带进度回调）
     *
     * @param query   搜索任务描述（自然语言）
     * @param project 当前项目
     * @param callback 进度回调（可为 null）
     * @return 搜索结果
     */
    public static SearchResult search(String query, Project project, SearchProgressCallback callback) {
        if (query == null || query.isBlank()) {
            SearchResult err = SearchResult.error("搜索任务为空");
            if (callback != null) callback.onComplete(err);
            return err;
        }

        ModelConfig model = CPSettings.getInstance().getCurrentChatModel();
        try {
            List<ChatMessage> messages = buildInitialMessages(query);
            List<ChatRequest.ToolDefinition> tools = getSearchTools();

            for (int i = 0; i < MAX_TOOL_CALLS; i++) {
                CompletableFuture<AgentStepResult> future = new CompletableFuture<>();

                ChatRequest request = buildSearchRequest(messages, tools);
                ModelLinkDispatcher.streamChat(request, messages, tools, model, new ModelLinkDispatcher.Relay() {
                    private final StringBuilder contentBuilder = new StringBuilder();
                    private final StringBuilder reasoningBuilder = new StringBuilder();
                    private volatile List<ChatMessage.ToolCall> toolCalls = null;

                    @Override
                    public void onMessage(String content) {
                        contentBuilder.append(content);
                    }

                    @Override
                    public void onReasoning(String reasoning) {
                        reasoningBuilder.append(reasoning);
                        if (callback != null) {
                            callback.onReasoning(reasoning);
                        }
                    }

                    @Override
                    public void onComplete() {
                        future.complete(new AgentStepResult(contentBuilder.toString(),
                                reasoningBuilder.toString(), null));
                    }

                    @Override
                    public void onToolCalls(List<ChatMessage.ToolCall> calls) {
                        toolCalls = calls;
                        future.complete(new AgentStepResult(contentBuilder.toString(),
                                reasoningBuilder.toString(), calls));
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });

                AgentStepResult step;
                try {
                    step = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    SearchResult err = SearchResult.error("等待模型响应超时或出错：" + e.getMessage());
                    if (callback != null) callback.onComplete(err);
                    return err;
                }

                if (step.toolCalls == null || step.toolCalls.isEmpty()) {
                    SearchResult ok = SearchResult.ok(step.content, new ArrayList<>());
                    if (callback != null) callback.onComplete(ok);
                    return ok;
                }

                ChatMessage assistantMsg = ChatMessage.assistantWithToolCalls(step.toolCalls);
                if (step.reasoning != null && !step.reasoning.isEmpty()) {
                    assistantMsg.setReasoning_content(step.reasoning);
                }
                messages.add(assistantMsg);

                for (ChatMessage.ToolCall tc : step.toolCalls) {
                    String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                    String toolArgs = tc.getFunction() != null ? tc.getFunction().getArguments() : "";
                    if (callback != null) {
                        callback.onToolCall(toolName, toolArgs);
                    }
                    String toolResult = executeTool(tc, project);
                    if (callback != null) {
                        callback.onToolResult(toolName, toolResult);
                    }
                    messages.add(ChatMessage.toolResult(tc.getId(), tc.getFunction().getName(), toolResult));
                }
            }

            SearchResult err = SearchResult.error("达到最大搜索轮次，未能完成任务");
            if (callback != null) callback.onComplete(err);
            return err;

        } catch (Exception e) {
            SearchResult err = SearchResult.error("搜索过程出错：" + e.getMessage());
            if (callback != null) callback.onComplete(err);
            return err;
        }
    }

    /**
     * 执行搜索任务（同步阻塞，无回调）
     */
    public static SearchResult search(String query, Project project) {
        return search(query, project, null);
    }

    /**
     * 单步执行结果
     */
    private static class AgentStepResult {
        final String content;
        final String reasoning;
        final List<ChatMessage.ToolCall> toolCalls;

        AgentStepResult(String content, String reasoning, List<ChatMessage.ToolCall> toolCalls) {
            this.content = content;
            this.reasoning = reasoning;
            this.toolCalls = toolCalls;
        }
    }

    /**
     * 构建子智能体的 ChatRequest（使用适合搜索任务的参数）
     */
    private static ChatRequest buildSearchRequest(List<ChatMessage> messages,
                                                   List<ChatRequest.ToolDefinition> tools) {
        CPSettings settings = CPSettings.getInstance();
        ChatRequest request = new ChatRequest();
        request.setModel(settings.getChatModelName());
        request.setMessages(messages);
        request.setStream(true);
        request.setMax_tokens(Math.min(settings.getChatMaxOutput(), 4096));
        request.setTemperature(0.5);
        request.setThinking(true);
        request.setReasoning_effort(EnumsThinkingIntensity.High.getCode());
        if (tools != null && !tools.isEmpty()) {
            request.setTools(tools);
            request.setTool_choice("auto");
        }
        return request;
    }

    /**
     * 构建初始消息（系统提示 + 用户查询）
     */
    private static List<ChatMessage> buildInitialMessages(String query) {
        List<ChatMessage> messages = new ArrayList<>();

        String systemPrompt = "你是一个代码搜索定位器。你的唯一任务是在项目中查找与用户查询相关的代码位置。\n\n" +
                "工作流程：\n" +
                "1. 用 search_tool 或 locate_code_by_symbol 搜索关键代码（这是首选，绝大多数情况够用）\n" +
                "2. 仅在明确需要确认某目录下有哪些文件/模块时，才用 list_files 列出该具体目录（不要反复用 list_files 浏览根目录 \".\"）\n" +
                "3. 用 view_file_outline 快速了解文件结构\n" +
                "4. 用 read_file_range 确认关键代码片段\n\n" +
                "输出要求（极其精简）：\n" +
                "- 只输出文件路径和行号范围，格式： 文件路径 L行号-L行号\n" +
                "- 每行一个文件，不要任何说明、总结、分析\n" +
                "- 例如：src/main/UserService.java L45-L78\n" +
                "- 找不到相关内容就说：NOT_FOUND\n" +
                "- 你的结果直接交给主模型，由它决定是否需要进一步读取。";

        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", query));
        return messages;
    }

    /**
     * 获取子智能体可用的工具列表（只有搜索/浏览类，只读）
     */
    private static List<ChatRequest.ToolDefinition> getSearchTools() {
        return Arrays.asList(
                ToolDefinitions.searchTool(),
                ToolDefinitions.locateCodeBySymbolTool(),
                ToolDefinitions.listFilesTool(),
                ToolDefinitions.viewFileOutlineTool(),
                ToolDefinitions.readFileRangeTool()
        );
    }

    /**
     * 执行单个工具调用（复用 ToolExecutor，但只允许只读工具）
     */
    private static String executeTool(ChatMessage.ToolCall toolCall, Project project) {
        if (toolCall == null || toolCall.getFunction() == null) {
            return "错误：无效的工具调用";
        }
        String name = toolCall.getFunction().getName();

        switch (name) {
            case "search_tool":
            case "locate_code_by_symbol":
            case "list_files":
            case "view_file_outline":
            case "read_file_range": {
                final com.intellij.openapi.util.Ref<String> resultRef = new com.intellij.openapi.util.Ref<>();
                com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(() -> {
                    resultRef.set(ToolExecutor.execute(toolCall, project, false));
                });
                return resultRef.get();
            }
            default:
                return "错误：子智能体不允许使用工具 '" + name + "'，仅允许使用搜索和浏览类工具。";
        }
    }
}
