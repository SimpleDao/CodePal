package com.loongc.api;

import com.loongc.api.model.ChatMessage;
import com.loongc.api.model.ChatRequest;
import com.loongc.api.model.ChatResponse;
import com.loongc.api.model.CompletionRequest;
import com.loongc.api.model.CompletionResponse;
import com.loongc.settings.LoongCSettings;
import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 客户端，支持流式响应
 * @author 水龙吟
 * @date 2026-05-24
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
        LoongCSettings settings = LoongCSettings.getInstance();
        if (!settings.isConfigured()) {
            callback.onError(new IllegalStateException("请先配置 DeepSeek API Key（Settings -> LoongC）"));
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
                System.out.println("DeepSeek SSE connection opened");
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
                        System.out.println("choice.getFinish_reason() + "+choice.getFinish_reason());
                        if (choice.getFinish_reason() != null) {
                            // 最后一帧：先回调 usage（如果有），再回调 onComplete
                            if (chatResponse.getUsage() != null) {
                                callback.onUsage(chatResponse.getUsage());
                            }
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
                System.err.println("DeepSeek SSE failure: " + errorMsg);
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
        LoongCSettings settings = LoongCSettings.getInstance();
        if (!settings.isConfigured()) {
            return "错误：请先配置 DeepSeek API Key（Settings -> LoongC）";
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

    /**
     * FIM 流式补全（/v1/completions 接口 + SSE）
     *
     * 格式：<|fim_prefix|>{before}<|fim_suffix|>{after}<|fim_middle|>
     * 适用于 qwen2.5-coder、DeepSeek-Coder 等原生支持 FIM 的模型。
     *
     * @param before      光标前的代码
     * @param after       光标后的代码（可为空字符串）
     * @param callback    流式回调（每个 token 调用 onToken；完成调用 onComplete；出错调用 onError）
     * @return 可立即调用 cancel() 的 OkHttp Call，供外部快速取消
     */
    public Call streamFIM(String before, String after, FIMCallback callback) {
        LoongCSettings s = LoongCSettings.getInstance();

        String apiKey  = s.getEffectiveCompletionApiKey();
        String apiBase = s.getEffectiveCompletionApiBase();
        String model   = s.getCompletionModel();

        // 构建 FIM prompt
        String fimPrompt = "<|fim_prefix|>" + before
                + "<|fim_suffix|>" + (after != null ? after : "")
                + "<|fim_middle|>";

        CompletionRequest req = new CompletionRequest();
        req.setModel(model);
        req.setPrompt(fimPrompt);
        req.setStream(true);
        req.setMax_tokens(s.getCompletionMaxTokens());
        req.setTemperature(s.getCompletionTemperature());
        req.setLogRequests(true);
        req.setLogResponses(true);
        // 阻止模型继续输出 FIM 特殊 token 之外的内容
        req.setStop(Arrays.asList("<|fim_pad|>", "<|endoftext|>", "<|fim_prefix|>", "<|fim_suffix|>"));

        RequestBody body = RequestBody.create(GSON.toJson(req), JSON);
        Request httpReq = new Request.Builder()
                .url(apiBase + "/v1/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        Call call = httpClient.newCall(httpReq);

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        factory.newEventSource(httpReq, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    callback.onComplete();
                    return;
                }
                try {
                    CompletionResponse resp = GSON.fromJson(data, CompletionResponse.class);
                    if (resp != null && resp.getChoices() != null && !resp.getChoices().isEmpty()) {
                        CompletionResponse.Choice choice = resp.getChoices().get(0);
                        String token = choice.getText();
                        if (token != null && !token.isEmpty()) {
                            callback.onToken(token);
                        }
                        if (choice.getFinish_reason() != null) {
                            callback.onComplete();
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("FIM SSE 解析失败: " + data, e);
                }
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                if (t instanceof java.io.IOException && "Canceled".equals(t.getMessage())) {
                    return; // 主动取消，不报错
                }
                String msg = t != null ? t.getMessage() : "unknown";
                LOG.warn("FIM SSE 请求失败: " + msg);
                callback.onError(t != null ? t : new RuntimeException(msg));
            }

            @Override public void onOpen(EventSource e, Response r) {}
            @Override public void onClosed(EventSource e) {}
        });

        return call;
    }

    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * FIM 流式回调接口
     */
    public interface FIMCallback {
        /** 每收到一个 token（可能是多个字符）时调用 */
        void onToken(String token);
        /** 生成完成时调用 */
        void onComplete();
        /** 发生错误时调用 */
        void onError(Throwable t);
    }

    /**
     * 流式响应回调接口
     */
    public interface StreamCallback {
        void onMessage(String content);
        void onComplete();
        void onError(Throwable error);
        /**
         * 收到 token 用量统计时回调（通常在最后一帧，即 finish_reason != null 时触发）
         * 默认空实现，不需要 usage 的调用方无需覆盖
         */
        default void onUsage(ChatResponse.Usage usage) {}
    }
}
