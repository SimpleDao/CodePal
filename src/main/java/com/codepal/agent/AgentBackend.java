package com.codepal.agent;

import com.codepal.model.ChatMessage;
import com.codepal.model.ChatResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 后端接口 —— 策略模式，统一不同 AI 后端的调用方式
 *
 * <p>借鉴 AutoDev 的 Agent 基类设计，将 DeepSeek、ACP (Claude Code) 等
 * 不同的 AI 后端抽象为统一接口，为后续多智能体协作打基础。
 *
 * <p>职责：
 * <ul>
 *   <li>发送消息并接收流式响应</li>
 *   <li>管理工具调用循环</li>
 *   <li>统一的流式回调接口</li>
 *   <li>支持取消操作</li>
 * </ul>
 *
 * @author CP Refactor
 */
public interface AgentBackend {

    /**
     * 流式回调接口
     */
    interface StreamCallback {
        void onMessage(String content);
        void onReasoning(String reasoning);
        void onToolCalls(List<ChatMessage.ToolCall> toolCalls);
        void onComplete();
        void onUsage(ChatResponse.Usage usage);
        void onError(Throwable error);

        /**
         * 模型开始构造 tool_calls 参数但尚未完整接收时的中间状态通知。
         * 用于在 tool_calls 参数传输期间（如大文件内容）向 UI 展示友好提示。
         */
        default void onToolCallPreparing() {}

        /**
         * 工具参数增量到达时回调（真实流式写入可视化用）。
         * DeepSeekClient 在每帧 arguments 累积后回调；index 为工具调用序号，
         * deltaArgs 为本帧新增的原始参数字符串。默认空实现，不需要的调用方无需覆盖。
         */
        default void onToolArgsDelta(int index, String deltaArgs) {}
    }

    /**
     * 后端名称，用于标识和区分
     */
    String getName();

    /**
     * 是否可用（配置检查）
     */
    boolean isAvailable();

    /**
     * 发送消息并流式接收响应
     *
     * @param messages 对话历史
     * @param tools 可用工具定义列表
     * @param callback 流式回调
     * @return 可用于取消的 CompletableFuture
     */
    CompletableFuture<Void> streamChat(
            List<ChatMessage> messages,
            List<com.codepal.model.ChatRequest.ToolDefinition> tools,
            StreamCallback callback
    );

    /**
     * 取消当前正在进行的请求
     */
    void cancelCurrent();

    /**
     * 设置 Craft 模式（true=自动执行，false=Plan模式）
     */
    void setCraftMode(boolean craftMode);

    /**
     * 获取当前是否为 Craft 模式
     */
    boolean isCraftMode();
}
