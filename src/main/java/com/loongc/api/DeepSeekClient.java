package com.loongc.api;

import com.loongc.model.ChatMessage;
import com.loongc.model.ChatRequest;
import com.loongc.model.ChatResponse;
import com.loongc.model.CompletionRequest;
import com.loongc.model.CompletionResponse;
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

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;
    private okhttp3.sse.EventSource currentEventSource;
    public DeepSeekClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 发送流式聊天请求 （支持工具调用）
     * 查看工作空间的vue项目
     */
    public void streamChat(List<ChatMessage> messages,
                           List<ChatRequest.ToolDefinition> tools,
                           StreamCallback callback) {
        LoongCSettings settings = LoongCSettings.getInstance();
        if (!settings.isConfigured()) {
            callback.onError(new IllegalStateException("请先配置 DeepSeek API Key（Settings -> LoongC）"));
            return;
        }

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getChatModelName());
        request.setMessages(messages);
        request.setStream(true);
        request.setMax_tokens(settings.getChatMaxTokens());
        request.setTemperature(settings.getChatTemperature());
        request.setThinking(true);
        if (tools != null && !tools.isEmpty()) {
            request.setTools(tools);
            request.setTool_choice("auto");
        }

        String jsonBody = GSON.toJson(request);
        System.out.println("jsonBody = \n"+jsonBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);


        Request httpRequest = new Request.Builder()
                .url(settings.getChatApiBase() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + settings.getChatApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        currentEventSource = factory.newEventSource(httpRequest, new EventSourceListener() {
            // 累积本轮 SSE 中的 tool_calls（DeepSeek 流式下 tool_calls 可能分多帧返回）
            private final java.util.List<ChatMessage.ToolCall> accumulatedToolCalls = new java.util.ArrayList<>();
            // 1. 新增：流是否已结束的防重入标志
            private volatile boolean isFinished = false;

            @Override
            public void onOpen(EventSource eventSource, Response response) {
                System.out.println("DeepSeek SSE connection opened");
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                //[DONE] 流完全结束可以关闭
                if ("[DONE]".equals(data)) {
                    finishStream(null);
                    return;
                }
                try {
                    ChatResponse chatResponse = GSON.fromJson(data, ChatResponse.class);
//                    System.out.println("chatResponse = \n"+chatResponse);
                    if (chatResponse != null && chatResponse.getChoices() != null
                            && !chatResponse.getChoices().isEmpty()) {
                        ChatResponse.Choice choice = chatResponse.getChoices().get(0);
                        ChatMessage delta = choice.getDelta();
                        if (delta != null) {
                            // DeepSeek reasoning 模型：先返回 reasoning_content，再返回 content
                            if (delta.getReasoning_content() != null) {
                                callback.onReasoning(delta.getReasoning_content());
                                //System.out.printf("delta.getReasoning_content() = %s%n",delta.getReasoning_content());
                            }
                            if (delta.getContent() != null) {
                                callback.onMessage(delta.getContent());
                                //System.out.printf("delta.getContent() = %s%n",delta.getContent());
                            }
                            if (delta.getTool_calls() != null && !delta.getTool_calls().isEmpty()) {
                                for (ChatMessage.ToolCall deltaCall : delta.getTool_calls()) {
                                    // 1. 判断这一帧是不是一个新工具调用的起点（有 id 或者有 function name）

                                    boolean isNewCall = deltaCall.getId() != null ||
                                            (deltaCall.getFunction() != null && deltaCall.getFunction().getName() != null);

                                    if (isNewCall) {
                                        System.out.printf("deltaCall.getFunction() = %s%n",deltaCall.getFunction().getName());
                                        // 如果是新工具，直接放入累积列表
                                        accumulatedToolCalls.add(deltaCall);
                                    } else {
                                        // 2. 如果是参数碎片（没有 id/name，只有 arguments），追加到最新一个工具的 arguments 中
                                        if (!accumulatedToolCalls.isEmpty()) {
                                            ChatMessage.ToolCall lastCall = accumulatedToolCalls.get(accumulatedToolCalls.size() - 1);
                                            if (deltaCall.getFunction() != null && deltaCall.getFunction().getArguments() != null) {
                                                if (lastCall.getFunction() != null) {
                                                    String oldArgs = lastCall.getFunction().getArguments();
                                                    // 增量拼接字符串
                                                    lastCall.getFunction().setArguments((oldArgs == null ? "" : oldArgs) + deltaCall.getFunction().getArguments());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        //System.out.println("choice.getFinish_reason() + "+choice.getFinish_reason());
                        //截断原因：tool_calls error cancelled  如果遇到工具调用，这里也会被执行
                        if (choice.getFinish_reason() != null) {
                            // 最后一帧：先回调 usage（如果有），再回调 onComplete
                            if (chatResponse.getUsage() != null) {
                                callback.onUsage(chatResponse.getUsage());
                            }
                            finishStream(choice.getFinish_reason());
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SSE data: " + data, e);
                }
            }

            //流结束 或者 被某种原因截断
            private void finishStream(String finishReason) {
                System.out.println("finishStream = "+finishReason);
                if (isFinished) return;
                isFinished = true;

                if ("tool_calls".equals(finishReason) || !accumulatedToolCalls.isEmpty()) {
                    callback.onToolCalls(accumulatedToolCalls);
                } else if ("length".equals(finishReason)) {
                    callback.onError(new RuntimeException("模型生成内容超过单次最大 Token 限制。"));
                } else if ("content_filter".equals(finishReason)) {
                    callback.onError(new RuntimeException("生成内容涉嫌敏感信息，已被安全策略拦截。"));
                } else {
                    // 包含 "stop" 或 null 兜底
                    callback.onComplete();
                }
//                if (!accumulatedToolCalls.isEmpty()) {
//                    callback.onToolCalls(accumulatedToolCalls);  //有工具调用
//                } else {
//                    callback.onComplete();
//                }
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
                System.out.println("DeepSeek SSE connection closed");
            }
        });
    }

    /**
     * 兼容旧接口：不带工具的流式聊天
     */
//    public void streamChat(List<ChatMessage> messages, StreamCallback callback) {
//        streamChat(messages, null, callback);
//    }

    /**
     * 非流式聊天请求
     */
    public String chat(List<ChatMessage> messages) {
        LoongCSettings settings = LoongCSettings.getInstance();
        if (!settings.isConfigured()) {
            return "错误：请先配置 DeepSeek API Key（Settings -> LoongC）";
        }

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getChatModelName());
        request.setMessages(messages);
        request.setStream(false);
        request.setMax_tokens(settings.getChatMaxTokens());
        request.setTemperature(settings.getChatTemperature());

        String jsonBody = GSON.toJson(request);
        RequestBody body = RequestBody.create(jsonBody, JSON);

        Request httpRequest = new Request.Builder()
                .url(settings.getChatApiBase() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + settings.getChatApiKey())
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
        String model   = s.getCompletionModelName();

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
     * 取消当前正在进行的流式请求
     */
    public void cancelCurrentStream() {
        if (currentEventSource != null) {
            currentEventSource.cancel();
            currentEventSource = null;
            LOG.info("Stream cancelled by user");
        }
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
        /** 收到普通文本流式块 */
        void onMessage(String content);
        /** 收到思考过程流式块（DeepSeek reasoning 模型特有） */
        void onReasoning(String reasoning);
        /** 流式结束且无工具调用时触发 */
        void onComplete();
        /** 流式结束但检测到工具调用时触发 */
        void onToolCalls(List<ChatMessage.ToolCall> toolCalls);
        /** 发生错误时触发 */
        void onError(Throwable error);
        /**
         * 收到 token 用量统计时回调（通常在最后一帧，即 finish_reason != null 时触发）
         * 默认空实现，不需要 usage 的调用方无需覆盖
         */
        default void onUsage(ChatResponse.Usage usage) {}
    }
}
