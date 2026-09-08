package com.codepal.agent;

import com.intellij.openapi.project.Project;
import com.codepal.api.DeepSeekClient;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;
import com.codepal.settings.CPSettings;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 后端实现
 *
 * <p>封装 DeepSeek API 调用，实现 AgentBackend 接口。
 *
 * @author CP Refactor
 */
public class DeepSeekBackend implements AgentBackend {

    private final Project project;
    private final DeepSeekClient client;
    private boolean craftMode = false;

    public DeepSeekBackend(Project project) {
        this.project = project;
        this.client = new DeepSeekClient();
    }

    @Override
    public String getName() {
        return "DeepSeek";
    }

    @Override
    public boolean isAvailable() {
        return CPSettings.getInstance().isConfigured();
    }

    @Override
    public CompletableFuture<Void> streamChat(
            List<ChatMessage> messages,
            List<ChatRequest.ToolDefinition> tools,
            StreamCallback callback
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            client.streamChat(messages, tools, new com.codepal.api.DeepSeekClient.StreamCallback() {
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

    public DeepSeekClient getClient() {
        return client;
    }
}
