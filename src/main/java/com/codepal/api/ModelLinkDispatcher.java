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
    }

    private ModelLinkDispatcher() {}

    public static void streamChat(ChatRequest openaiRequest,
                                  List<ChatMessage> messages,
                                  List<ChatRequest.ToolDefinition> tools,
                                  ModelConfig model,
                                  Relay relay) {
        if (model != null && ModelConfig.FORMAT_ANTHROPIC.equals(model.getApiFormat())) {
            AnthropicClient client = new AnthropicClient();
            client.streamChat(messages, tools, new AnthropicClient.StreamCallback() {
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
                public void onUsage(ChatResponse.Usage usage) { }
                @Override
                public void onError(Throwable t) { relay.onError(t); }
            });
        } else {
            DeepSeekClient client = new DeepSeekClient();
            client.streamChat(openaiRequest, new DeepSeekClient.StreamCallback() {
                @Override
                public void onMessage(String content) { relay.onMessage(content); }
                @Override
                public void onReasoning(String reasoning) { relay.onReasoning(reasoning); }
                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> calls) { relay.onToolCalls(calls); }
                @Override
                public void onComplete() { relay.onComplete(); }
                @Override
                public void onError(Throwable t) { relay.onError(t); }
            });
        }
    }
}
