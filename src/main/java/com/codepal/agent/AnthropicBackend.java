package com.codepal.agent;

import com.intellij.openapi.project.Project;
import com.codepal.api.AnthropicClient;
import com.codepal.model.ChatMessage;
import com.codepal.model.ModelConfig;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;
import com.codepal.settings.CPSettings;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic 原生 Messages API 后端实现。
 *
 * <p>封装 AnthropicClient 调用，实现 AgentBackend 接口。与 DeepSeekBackend 走不同的
 * 数据处理链路（鉴权头、system 顶层字段、tool_use block 等），由 AgentBackendManager
 * 按模型的 apiFormat 路由到本实现。
 *
 * @author CP
 */
public class AnthropicBackend implements AgentBackend {

    private final Project project;
    private final AnthropicClient client;
    private boolean craftMode = false;

    public AnthropicBackend(Project project) {
        this.project = project;
        this.client = new AnthropicClient();
    }

    @Override
    public String getName() {
        return "Anthropic";
    }

    @Override
    public boolean isAvailable() {
        ModelConfig model = CPSettings.getInstance().getCurrentChatModel();
        return model != null && model.getApiKey() != null && !model.getApiKey().trim().isEmpty();
    }

    @Override
    public CompletableFuture<Void> streamChat(
            List<ChatMessage> messages,
            List<ChatRequest.ToolDefinition> tools,
            StreamCallback callback
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            client.streamChat(messages, tools, new AnthropicClient.StreamCallback() {
                @Override
                public void onMessage(String content) {
                    callback.onMessage(content);
                }

                @Override
                public void onReasoning(String reasoning) {
                    callback.onReasoning(reasoning);
                }

                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> toolCalls) {
                    callback.onToolCalls(toolCalls);
                }

                @Override
                public void onToolCallPreparing() {
                    callback.onToolCallPreparing();
                }

                @Override
                public void onToolArgsDelta(int index, String deltaArgs) {
                    callback.onToolArgsDelta(index, deltaArgs);
                }

                @Override
                public void onComplete() {
                    callback.onComplete();
                    future.complete(null);
                }

                @Override
                public void onUsage(ChatResponse.Usage usage) {
                    callback.onUsage(usage);
                }

                @Override
                public void onError(Throwable error) {
                    callback.onError(error);
                    future.completeExceptionally(error);
                }
            });
        } catch (Exception e) {
            callback.onError(e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void cancelCurrent() {
        client.cancelCurrentStream();
    }

    @Override
    public void setCraftMode(boolean craftMode) {
        this.craftMode = craftMode;
    }

    @Override
    public boolean isCraftMode() {
        return craftMode;
    }

    public AnthropicClient getClient() {
        return client;
    }
}
