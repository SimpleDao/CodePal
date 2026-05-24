package com.loongc.api;

import com.loongc.api.model.ChatMessage;
import com.loongc.api.model.ChatRequest;
import com.loongc.api.model.ChatResponse;
import com.loongc.settings.CodeBuddySettings;
import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 客户端，支持流式响应
 */
public class DeepSeekClient {
    private static final Logger LOG = Logger.getInstance(DeepSeekClient.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    public DeepSeekClient() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 发送流式聊天请求
     */
    public void streamChat(List<ChatMessage> messages, StreamCallback callback) {
        CodeBuddySettings settings = CodeBuddySettings.getInstance();
        if (!settings.isConfigured()) {
            callback.onError(new IllegalStateException("请先配置 DeepSeek API Key（Settings -> CodeBuddy）"));
            return;
        }

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getModel());
        request.setMessages(messages);
        request.setStream(true);
        request.setMax_tokens(settings.getMaxTokens());
        request.setTemperature(settings.getTemperature());

        String jsonBody = GSON.toJson(request);
        RequestBody body = RequestBody.create(jsonBody, JSON);

        Request httpRequest = new Request.Builder()
            .url(settings.getApiBase() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + settings.getApiKey())
            .header("Content-Type", "application/json")
            .post(body)
            .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        factory.newEventSource(httpRequest, new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                LOG.info("DeepSeek SSE connection opened");
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    callback.onComplete();
                    return;
                }
                try {
                    ChatResponse chatResponse = GSON.fromJson(data, ChatResponse.class);
                    if (chatResponse != null && chatResponse.getChoices() != null
                        && !chatResponse.getChoices().isEmpty()) {
                        ChatResponse.Choice choice = chatResponse.getChoices().get(0);
                        ChatMessage delta = choice.getDelta();
                        if (delta != null && delta.getContent() != null) {
                            callback.onMessage(delta.getContent());
                        }
                        if (choice.getFinish_reason() != null) {
                            callback.onComplete();
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SSE data: " + data, e);
                }
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                String errorMsg = t != null ? t.getMessage() : "Unknown error";
                if (response != null) {
                    try {
                        String bodyStr = response.body() != null ? response.body().string() : "";
                        errorMsg += " (HTTP " + response.code() + ": " + bodyStr + ")";
                    } catch (IOException ignored) {}
                }
                LOG.error("DeepSeek SSE failure: " + errorMsg, t);
                callback.onError(new RuntimeException("API 请求失败: " + errorMsg, t));
            }

            @Override
            public void onClosed(EventSource eventSource) {
                LOG.info("DeepSeek SSE connection closed");
            }
        });
    }

    /**
     * 非流式聊天请求
     */
    public String chat(List<ChatMessage> messages) {
        CodeBuddySettings settings = CodeBuddySettings.getInstance();
        if (!settings.isConfigured()) {
            return "错误：请先配置 DeepSeek API Key（Settings -> CodeBuddy）";
        }

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getModel());
        request.setMessages(messages);
        request.setStream(false);
        request.setMax_tokens(settings.getMaxTokens());
        request.setTemperature(settings.getTemperature());

        String jsonBody = GSON.toJson(request);
        RequestBody body = RequestBody.create(jsonBody, JSON);

        Request httpRequest = new Request.Builder()
            .url(settings.getApiBase() + "/v1/chat/completions")
            .header("Authorization", "Bearer " + settings.getApiKey())
            .header("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                return "错误：HTTP " + response.code() + " - " + errorBody;
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            ChatResponse chatResponse = GSON.fromJson(responseBody, ChatResponse.class);
            if (chatResponse != null && chatResponse.getChoices() != null
                && !chatResponse.getChoices().isEmpty()) {
                ChatMessage message = chatResponse.getChoices().get(0).getMessage();
                return message != null ? message.getContent() : "";
            }
            return "";
        } catch (IOException e) {
            LOG.error("DeepSeek API request failed", e);
            return "错误：" + e.getMessage();
        }
    }

    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * 流式响应回调接口
     */
    public interface StreamCallback {
        void onMessage(String content);
        void onComplete();
        void onError(Throwable error);
    }
}
