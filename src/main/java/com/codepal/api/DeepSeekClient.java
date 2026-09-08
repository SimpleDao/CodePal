package com.codepal.api;

import com.codepal.enums.EnumsThinkingIntensity;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;
import com.codepal.model.CompletionRequest;
import com.codepal.model.CompletionResponse;
import com.codepal.settings.CPSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSerializationContext;
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

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ChatMessage.class, new ChatMessageSerializer())
            .create();

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
     * 发送流式聊天请求（支持工具调用）— 使用完整的 ChatRequest 对象
     * 适用于需要自定义模型参数的场景（子智能体、压缩智能体等）
     */
    public void streamChat(ChatRequest request, StreamCallback callback) {
        CPSettings settings = CPSettings.getInstance();
        if (!settings.isConfigured()) {
            callback.onError(new IllegalStateException("请先配置 DeepSeek API Key（Settings -> CP）"));
            return;
        }

        String jsonBody = GSON.toJson(request);
        System.out.println("jsonBody = \n"+jsonBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);

        // 端点拼接容错：用户可能填了带尾斜杠的基址、已含版本段的路径（/v1、/v3、/v4…），
        // 甚至完整端点。用正则识别「末段版本号」，避免拼出 .../v4/v1/chat/completions 这类 404。
        // 例：https://open.bigmodel.cn/api/paas/v4 → .../api/paas/v4/chat/completions
        //     https://api.deepseek.com          → .../api.deepseek.com/v1/chat/completions
        //     .../v1                            → .../v1/chat/completions
        //     .../chat/completions              → 原样使用
        String baseUrl = settings.getChatApiBase();
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.deepseek.com";
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        final String endpoint;
        if (baseUrl.matches(".*(/chat/completions)$")) {
            endpoint = baseUrl;                                    // 已是完整端点，原样使用
        } else if (baseUrl.matches("^.*/v\\d+$")) {
            endpoint = baseUrl + "/chat/completions";              // 末段就是版本号（任意 vN），只补后半段
        } else {
            endpoint = baseUrl + "/v1/chat/completions";           // 无版本段，补全默认 /v1 前缀
        }
        System.out.println("endpoint = \n"+endpoint);
        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + settings.getChatApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        currentEventSource = factory.newEventSource(httpRequest, new EventSourceListener() {
            // 累积本轮 SSE 中的 tool_calls（DeepSeek 流式下 tool_calls 可能分多帧返回）
            private final java.util.List<ChatMessage.ToolCall> accumulatedToolCalls = new java.util.ArrayList<>();
            // 流是否已结束的防重入标志
            private volatile boolean isFinished = false;
            // 调试：记录响应帧原始 JSON，流结束时打印总结构（不逐 token 刷屏）
            private volatile String lastSseData = null;
            private volatile String lastUsageData = null;

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
                lastSseData = data; // 调试：记录最近一帧
                try {
                    ChatResponse chatResponse = GSON.fromJson(data, ChatResponse.class);
                    if (chatResponse != null && chatResponse.getChoices() != null
                            && !chatResponse.getChoices().isEmpty()) {
                        ChatResponse.Choice choice = chatResponse.getChoices().get(0);
                        ChatMessage delta = choice.getDelta();
                        if (delta != null) {
                            // DeepSeek reasoning 模型：先返回 reasoning_content，再返回 content
                            if (delta.getReasoning_content() != null) {
                                callback.onReasoning(delta.getReasoning_content());
                            }
                            if (delta.getContent() != null) {
                                callback.onMessage(delta.getContent());
                            }
                            if (delta.getTool_calls() != null && !delta.getTool_calls().isEmpty()) {
                                // ★ 首次检测到 tool_calls 时立即通知 UI（参数传输期间可能耗时数秒）
                                if (accumulatedToolCalls.isEmpty()) {
                                    callback.onToolCallPreparing();
                                }
                                for (ChatMessage.ToolCall deltaCall : delta.getTool_calls()) {
                                    // 按 OpenAI 规范用 index 归位：index 缺失时退化为"追加到最后一条"
                                    int idx = deltaCall.getIndex() != null
                                            ? deltaCall.getIndex()
                                            : accumulatedToolCalls.size();
                                    while (accumulatedToolCalls.size() <= idx) {
                                        ChatMessage.ToolCall placeholder = new ChatMessage.ToolCall();
                                        placeholder.setIndex(accumulatedToolCalls.size());
                                        accumulatedToolCalls.add(placeholder);
                                    }
                                    ChatMessage.ToolCall target = accumulatedToolCalls.get(idx);

                                    // id / type / function.name 在首帧到达
                                    if (deltaCall.getId() != null) target.setId(deltaCall.getId());
                                    if (deltaCall.getType() != null) target.setType(deltaCall.getType());
                                    if (deltaCall.getFunction() != null) {
                                        if (target.getFunction() == null) {
                                            target.setFunction(new ChatMessage.ToolCall.Function());
                                        }
                                        if (deltaCall.getFunction().getName() != null) {
                                            target.getFunction().setName(deltaCall.getFunction().getName());
                                        }
                                        // arguments 可能出现在任意帧（包括首帧之前），始终追加
                                        if (deltaCall.getFunction().getArguments() != null) {
                                            String deltaArgs = deltaCall.getFunction().getArguments();
                                            String oldArgs = target.getFunction().getArguments();
                                            target.getFunction().setArguments((oldArgs == null ? "" : oldArgs)
                                                    + deltaArgs);
                                            // ★ 真实流式写入可视化：把本帧新增参数增量推给 UI，
                                            //   由 ChatPanel 边收边把已生成的 file_content 铺进工具卡片。
                                            callback.onToolArgsDelta(idx, deltaArgs);
                                        }
                                    }
                                }
                            }
                        }
                        if (choice.getFinish_reason() != null) {
                            ChatResponse.Usage usage = resolveUsage(data, chatResponse.getUsage());
                            if (usage != null) {
                                lastUsageData = data; // 调试：记录含 usage 的总结构帧
                                callback.onUsage(usage);
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
                // ★ 调试：流结束时打印本次响应的总结构（含 usage / finish_reason 的最后一帧）
                String total = lastUsageData != null ? lastUsageData : lastSseData;
                if (total != null) {
                    System.out.println("=== DeepSeek SSE 响应总结构 ===");
                    System.out.println(total);
                }

                // ★ 根因修复：仅当真正累积到工具调用时才回调 onToolCalls。
                // 之前 finish_reason="tool_calls" 但 accumulatedToolCalls 为空时也会回调空列表，
                // 导致上层创建 content=null 且无 tool_calls 的非法 assistant 消息，污染后续每次请求。
                if (!accumulatedToolCalls.isEmpty()) {
                    callback.onToolCalls(accumulatedToolCalls);
                } else if ("tool_calls".equals(finishReason)) {
                    // 模型声明要调用工具却没解析出任何调用（流式分片异常），按正常完成处理，避免产出非法消息
                    LOG.warn("DeepSeek 返回 finish_reason=tool_calls 但未解析到任何工具调用，按 onComplete 处理");
                    callback.onComplete();
                } else if ("length".equals(finishReason)) {
                    callback.onError(new RuntimeException("模型生成内容超过单次最大 Token 限制。"));
                } else if ("content_filter".equals(finishReason)) {
                    callback.onError(new RuntimeException("生成内容涉嫌敏感信息，已被安全策略拦截。"));
                } else {
                    callback.onComplete();
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
                System.out.println("DeepSeek SSE connection closed");
            }
        });
    }

    /**
     * 发送流式聊天请求（支持工具调用）— 使用默认配置（主智能体）
     * 查看工作空间的vue项目
     */
    public void streamChat(List<ChatMessage> messages,
                           List<ChatRequest.ToolDefinition> tools,
                           StreamCallback callback) {
        CPSettings settings = CPSettings.getInstance();

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getChatModelName());
        request.setMessages(messages);
        request.setStream(true);
        request.setMax_tokens(settings.getChatMaxOutput());
        request.setTemperature(settings.getChatTemperature());
        request.setThinking(true);
        request.setReasoning_effort(EnumsThinkingIntensity.High.getCode());
        if (tools != null && !tools.isEmpty()) {
            request.setTools(tools);
            request.setTool_choice("auto");
        }

        streamChat(request, callback);
    }

    /**
     * FIM 流式补全（/v1/completions 接口 + SSE）
     *
     * DeepSeek-Coder FIM 格式：<｜fim▁begin｜>{prefix}<｜fim▁hole｜>{suffix}<｜fim▁end｜>
     *
     * @param before      光标前的代码
     * @param after       光标后的代码（可为空字符串）
     * @param callback    流式回调
     * @return 可立即调用 cancel() 的 OkHttp Call
     */
    public Call streamFIM(String before, String after, FIMCallback callback) {
        CPSettings s = CPSettings.getInstance();

        String apiKey  = s.getEffectiveCompletionApiKey();
        String apiBase = s.getEffectiveCompletionApiBase();
        String model   = s.getCompletionModelName();

        String fimPrompt = "<｜fim▁begin｜>" + before
                + "<｜fim▁hole｜>"
                + "<｜fim▁end｜>" + (after != null ? after : "");

        CompletionRequest req = new CompletionRequest();
        req.setModel(model);
        req.setPrompt(fimPrompt);
        req.setStream(true);
        req.setMax_tokens(s.getCompletionMaxTokens());
        req.setTemperature(s.getCompletionTemperature());
        req.setLogRequests(true);
        req.setLogResponses(true);
        req.setStop(Arrays.asList("<｜fim▁begin｜>", "<｜fim▁hole｜>", "<｜fim▁end｜>", "<｜end▁of▁sentence｜>"));

        RequestBody body = RequestBody.create(GSON.toJson(req), JSON);
        // FIM 端点拼接容错（与 streamChat 同策略，正则识别任意 vN 版本段）：
        // 避免 .../v4/v1/completions 这类重复拼接 404
        String fimBase = (apiBase == null || apiBase.isBlank()) ? "https://api.deepseek.com" : apiBase;
        while (fimBase.endsWith("/")) fimBase = fimBase.substring(0, fimBase.length() - 1);
        final String fimEndpoint;
        if (fimBase.matches(".*(/completions)$")) {
            fimEndpoint = fimBase;                                 // 已是完整端点
        } else if (fimBase.matches("^.*/v\\d+$")) {
            fimEndpoint = fimBase + "/completions";                // 末段是版本号（任意 vN），只补后半段
        } else {
            fimEndpoint = fimBase + "/v1/completions";             // 无版本段，补默认 /v1
        }
        Request httpReq = new Request.Builder()
                .url(fimEndpoint)
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
     * 两层兼容解析 token 用量：优先用 Gson 已映射的 OpenAI 字段
     * （prompt_tokens/completion_tokens），若缺失再从原始 JSON 回退解析
     * Anthropic 命名（input_tokens/output_tokens）——部分 OpenAI 兼容代理
     * （如 xAI 网关）usage 用 Anthropic 命名，否则圆环百分比读成 0。
     */
    private static ChatResponse.Usage resolveUsage(String data, ChatResponse.Usage gsonUsage) {
        int prompt = 0, completion = 0;
        if (gsonUsage != null) {
            prompt = gsonUsage.getPromptTokens();
            completion = gsonUsage.getCompletionTokens();
        }
        if (prompt == 0 && completion == 0) {
            try {
                JsonObject root = GSON.fromJson(data, JsonObject.class);
                if (root != null && root.has("usage")) {
                    JsonObject u = root.getAsJsonObject("usage");
                    prompt = readInt(u, "prompt_tokens", readInt(u, "input_tokens", 0));
                    completion = readInt(u, "completion_tokens", readInt(u, "output_tokens", 0));
                }
            } catch (Exception ignored) { }
        }
        if (prompt == 0 && completion == 0) return gsonUsage; // 仍无有效用量则不报
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setTotalTokens(gsonUsage != null && gsonUsage.getTotalTokens() > 0
                ? gsonUsage.getTotalTokens() : prompt + completion);
        // ★ 缓存命中/未命中（OpenAI 字段）必须从 gsonUsage 一并带过去：
        //   此前这里 new 新对象只拷了 prompt/completion/total，缓存字段全丢，
        //   导致 updateTokenStats 的 sessionCacheHitTokens/Miss 恒为 0，
        //   token 面板「缓存命中/未命中」行永远不渲染（usage 帧明明有值）。
        if (gsonUsage != null) {
            usage.setPromptCacheHitTokens(gsonUsage.getPromptCacheHitTokens());
            usage.setPromptCacheMissTokens(gsonUsage.getPromptCacheMissTokens());
        }
        return usage;
    }

    private static int readInt(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
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
         * 首次检测到 tool_calls 参数正在传输时触发（用于参数传输期间展示友好提示）。
         * 默认空实现，不需要的调用方无需覆盖。
         */
        default void onToolCallPreparing() {}

        /**
         * 工具参数增量到达时回调（真实流式写入可视化用）。
         * 在每帧 arguments 累积后回调；index 为工具调用序号，deltaArgs 为本帧新增的原始参数字符串。
         * 默认空实现，不需要的调用方无需覆盖。
         */
        default void onToolArgsDelta(int index, String deltaArgs) {}
        /**
         * 收到 token 用量统计时回调（通常在最后一帧，即 finish_reason != null 时触发）
         * 默认空实现，不需要 usage 的调用方无需覆盖
         */
        default void onUsage(ChatResponse.Usage usage) {}
    }

    /**
     * ChatMessage 序列化：主模型自带视觉(inlineVision)且带图片附件时，
     * 把 content 序列化为 OpenAI 多模态数组（text + image_url），否则按普通文本序列化。
     */
    private static class ChatMessageSerializer implements JsonSerializer<ChatMessage> {
        @Override
        public JsonElement serialize(ChatMessage src, java.lang.reflect.Type typeOfSrc,
                                     JsonSerializationContext context) {
            JsonObject o = new JsonObject();
            o.addProperty("role", src.getRole());
            if (src.isInlineVision() && src.hasAttachments()) {
                JsonArray parts = new JsonArray();
                if (src.getContent() != null && !src.getContent().isEmpty()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("type", "text");
                    t.addProperty("text", src.getContent());
                    parts.add(t);
                }
                for (ChatMessage.Attachment a : src.getAttachments()) {
                    String b64 = ChatMessage.encodeBase64(a.getPath());
                    if (b64 == null) continue;
                    String mime = (a.getMimeType() != null && !a.getMimeType().isEmpty())
                            ? a.getMimeType() : "image/png";
                    JsonObject img = new JsonObject();
                    img.addProperty("type", "image_url");
                    JsonObject iu = new JsonObject();
                    iu.addProperty("url", "data:" + mime + ";base64," + b64);
                    img.add("image_url", iu);
                    parts.add(img);
                }
                o.add("content", parts);
            } else {
                o.addProperty("content", src.getContent());
            }
            if (src.getReasoning_content() != null) {
                o.addProperty("reasoning_content", src.getReasoning_content());
            }
            if (src.getTool_calls() != null) {
                o.add("tool_calls", context.serialize(src.getTool_calls()));
            }
            if (src.getTool_call_id() != null) {
                o.addProperty("tool_call_id", src.getTool_call_id());
            }
            if (src.getName() != null) {
                o.addProperty("name", src.getName());
            }
            return o;
        }
    }
}
