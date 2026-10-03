package com.codepal.api;

import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;
import com.codepal.model.ModelConfig;

import java.util.List;

/**
 * 模型链路分发器 —— 按模型 {@code apiFormat} 把同一次流式聊天请求路由到正确的客户端：
 * <ul>
 *   <li>{@code anthropic} → 原生 {@link AnthropicClient}（Messages API）</li>
 *   <li>其余（openai / deepseek 等）→ {@link DeepSeekClient}（OpenAI 兼容）</li>
 * </ul>
 *
 * <p>解决此前 SearchAgent / CodeReviewer / CompressionManager 写死 {@link DeepSeekClient}、
 * 导致 Anthropic 格式模型被错发 OpenAI 形态请求（假 apiFormat 分支）的问题。
 *
 * <p>调用方只需提供一个 {@link Relay}（与两家 StreamCallback 同构的精简回调），
 * 并同时传入 OpenAI 形态的 {@link ChatRequest}（openai 链路用）与裸 messages/tools（anthropic 链路用）。
 */
public final class ModelLinkDispatcher {

    /** 与 DeepSeekClient / AnthropicClient 的 StreamCallback 同构的精简回调 */
    public interface Relay {
        void onMessage(String content);
        void onReasoning(String reasoning);
        void onToolCalls(List<ChatMessage.ToolCall> calls);
        void onComplete();
        void onError(Throwable t);
        /**
         * usage 到达。子智能体（SearchAgent）/ 压缩（CompressionManager）等旁路链路的
         * 消耗统计入口；默认空实现保持既有调用方兼容（旁路 usage 此前在此被静默丢弃）。
         */
        default void onUsage(ChatResponse.Usage usage) { }
    }

    private ModelLinkDispatcher() {}

    public static void streamChat(ChatRequest openaiRequest,
                                  List<ChatMessage> messages,
                                  List<ChatRequest.ToolDefinition> tools,
                                  ModelConfig model,
                                  Relay relay) {
        if (model != null && ModelConfig.FORMAT_ANTHROPIC.equals(model.getApiFormat())) {
            AnthropicClient client = new AnthropicClient();
            // ★ 传入 model 配置（旁路模型配置生效的关键）：旧实现不传，AnthropicClient
            //   内部硬取当前聊天模型 → 压缩等旁路的模型配置被完全无视。
            //   thinking 开关一并透传（ChatRequest.thinking，enabled/disabled）。
            client.streamChat(messages, tools, model,
                    openaiRequest != null ? openaiRequest.getThinking() : null,
                    new AnthropicClient.StreamCallback() {
                @Override
                public void onMessage(String content) { relay.onMessage(content); }
                @Override
                public void onReasoning(String reasoning) { relay.onReasoning(reasoning); }
                @Override
                public void onToolCallPreparing() { }
                @Override
                public void onToolArgsDelta(int index, String deltaArgs) { }
                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> calls) { relay.onToolCalls(calls); }
                @Override
                public void onComplete() { relay.onComplete(); }
                @Override
                public void onUsage(ChatResponse.Usage usage) { relay.onUsage(usage); }
                @Override
                public void onError(Throwable t) { relay.onError(t); }
            });
        } else {
            DeepSeekClient client = new DeepSeekClient();
            // ★ 传入旁路模型自己的 base/key（与 anthropic 分支对称）：旧实现 DeepSeekClient
            //   两参版本内部硬取当前聊天模型的端点与密钥——压缩模型换 URL 不生效
            client.streamChat(openaiRequest,
                    model != null ? model.getApiBase() : null,
                    model != null ? model.getApiKey() : null,
                    new DeepSeekClient.StreamCallback() {
                @Override
                public void onMessage(String content) { relay.onMessage(content); }
                @Override
                public void onReasoning(String reasoning) { relay.onReasoning(reasoning); }
                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> calls) { relay.onToolCalls(calls); }
                @Override
                public void onComplete() { relay.onComplete(); }
                @Override
                public void onUsage(ChatResponse.Usage usage) { relay.onUsage(usage); }
                @Override
                public void onError(Throwable t) { relay.onError(t); }
            });
        }
    }
}
