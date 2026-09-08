package com.codepal.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;
import com.codepal.model.ModelConfig;
import com.codepal.settings.CPSettings;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic 原生 Messages API 客户端（流式）。
 *
 * <p>与 DeepSeekClient（OpenAI 兼容）走不同协议：
 * <ul>
 *   <li>端点：{apiBase}（即用户填写的完整 /v1/messages URL）</li>
 *   <li>鉴权头：x-api-key + anthropic-version，而非 Bearer</li>
 *   <li>system 为独立顶层字段，content 为字符串 / content block 数组</li>
 *   <li>SSE 事件：message_start / content_block_start|delta|stop / message_delta / message_stop</li>
 *   <li>工具调用以 content_block(type=tool_use) 表达，参数以 input_json_delta 增量返回</li>
 * </ul>
 *
 * <p>映射为与 DeepSeekBackend 一致的统一 StreamCallback（onMessage / onToolCalls / onUsage / onComplete）。
 */
public class AnthropicClient {
    private static final Logger LOG = Logger.getInstance(AnthropicClient.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private Call currentCall;

    public AnthropicClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void streamChat(List<ChatMessage> messages,
                           List<ChatRequest.ToolDefinition> tools,
                           StreamCallback callback) {
        CPSettings settings = CPSettings.getInstance();
        ModelConfig model = settings.getCurrentChatModel();
        if (model == null || model.getApiKey().trim().isEmpty()) {
            callback.onError(new IllegalStateException("请先配置 Anthropic API Key（Settings -> CP）"));
            return;
        }

        JsonObject body = buildRequestBody(messages, tools, model, settings);
        RequestBody reqBody = RequestBody.create(GSON.toJson(body), JSON);

        String url = normalizeAnthropicUrl(model.getApiBase());

        final String resolvedUrl = url;
        Request httpRequest = new Request.Builder()
                .url(url)
                .header("x-api-key", model.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(reqBody)
                .build();

        ParseState state = new ParseState();
        currentCall = httpClient.newCall(httpRequest);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) return;
                callback.onError(new RuntimeException("Anthropic API 请求失败: " + e.getMessage()
                        + " [URL=" + resolvedUrl + "]", e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        String err = "";
                        try {
                            err = response.body() != null ? response.body().string() : "";
                        } catch (IOException ignored) { }
                        callback.onError(new RuntimeException("Anthropic API 请求失败: HTTP "
                                + response.code() + (err.isEmpty() ? "" : ": " + err)
                                + " [URL=" + resolvedUrl + "]"));
                        return;
                    }
                    String ct = response.header("Content-Type", "");
                    ResponseBody rb = response.body();
                    if (rb == null) {
                        callback.onError(new RuntimeException("Anthropic API 请求失败: 空响应 [URL=" + resolvedUrl + "]"));
                        return;
                    }
                    if (ct.contains("text/event-stream")) {
                        // 流式 SSE：逐行解析（代理返回 SSE 的场景）
                        BufferedSource source = rb.source();
                        StringBuilder dataBuf = new StringBuilder();
                        while (!source.exhausted()) {
                            if (state.isFinished || call.isCanceled()) break;
                            String line;
                            try {
                                line = source.readUtf8Line();
                            } catch (IOException e) {
                                break;
                            }
                            if (line == null) break;
                            if (line.startsWith("data:")) {
                                String d = line.substring(5).trim();
                                if (!d.isEmpty()) {
                                    if (dataBuf.length() > 0) dataBuf.append('\n');
                                    dataBuf.append(d);
                                }
                            } else if (line.isEmpty()) {
                                if (dataBuf.length() > 0) {
                                    String data = dataBuf.toString();
                                    dataBuf.setLength(0);
                                    if (!"[DONE]".equals(data)) {
                                        handleSseEvent(data, state, callback);
                                    }
                                }
                            }
                            if (state.isFinished) break;
                        }
                        if (!state.isFinished) {
                            finishStream(callback, state, state.accumulatedToolCalls, state.inputTokens, state.outputTokens);
                        }
                    } else {
                        // 非流式：部分代理忽略 stream 标志，直接返回单条 JSON
                        String bodyStr = rb.string();
                        JsonObject obj = GSON.fromJson(bodyStr, JsonObject.class);
                        handleCompleteMessage(obj, state, callback);
                    }
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        callback.onError(new RuntimeException("Anthropic API 请求失败: " + e.getMessage()
                                + " [URL=" + resolvedUrl + "]", e));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 规范 Anthropic 端点 URL。用户通常按 OpenAI 习惯把 apiBase 填成「基址」（如
     * https://api.anthropic.com/v1、代理基址、OpenRouter 的 /api/v1），而 Messages API 需要
     * 完整路径 .../v1/messages。此处对基址/完整路径都容错，避免直接当完整 URL 用导致 404。
     */
    public static String normalizeAnthropicUrl(String apiBase) {
        String u = apiBase != null ? apiBase.trim() : "";
        if (u.isEmpty()) return "https://api.anthropic.com/v1/messages";
        // 去掉末尾斜杠，统一判断
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/messages")) return u;
        if (u.endsWith("/v1")) return u + "/messages";
        // DeepSeek / OpenRouter 等 anthropic 兼容代理基址（如 https://api.deepseek.com/anthropic），
        // messages 路径直接挂在基址下，无 /v1 层级。
        if (u.endsWith("/anthropic")) return u + "/messages";
        return u + "/v1/messages";
    }

    private static void finishStream(StreamCallback callback, ParseState state,
                                      List<ChatMessage.ToolCall> accumulatedToolCalls,
                                      int inputTokens, int outputTokens) {
        if (state.isFinished) return;
        state.isFinished = true;
        // ★ 始终回调 onUsage（与 DeepSeekClient 一致），无论是否有工具调用。
        // 之前仅工具调用路径回调 onUsage，纯文本完成路径漏调 → 上层 lastPromptTokens/Completion 恒为 0
        // → buildAnswerTokenJson 返回 null → token 消耗图标不显示。
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(inputTokens);
        usage.setCompletionTokens(outputTokens);
        usage.setTotalTokens(inputTokens + outputTokens);
        // 缓存命中/未命中
        if (state.cacheReadTokens > 0) {
            usage.setPromptCacheHitTokens(state.cacheReadTokens);
            usage.setPromptCacheMissTokens(Math.max(0, inputTokens - state.cacheReadTokens));
        }
        callback.onUsage(usage);
        if (!accumulatedToolCalls.isEmpty()) {
            callback.onToolCalls(accumulatedToolCalls);
        } else {
            callback.onComplete();
        }
    }

    /** SSE 事件与单条 JSON 共用的解析状态（跨多事件累积工具调用 / usage / thinking 标记） */
    private static final class ParseState {
        final List<ChatMessage.ToolCall> accumulatedToolCalls = new ArrayList<>();
        ChatMessage.ToolCall activeToolCall = null;
        final StringBuilder activeArgs = new StringBuilder();
        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        // 当前 tool_use 块在「工具调用」中的序号（仅工具计数，区别于 content_block 序号），
        // 用于 onToolArgsDelta 的 index，与 OpenAI 链路一致（ChatPanel 用它识别当前流式写入卡片）。
        int activeToolIndex = -1;
        int toolOrdinal = 0;
        boolean activeIsThinking = false;
        boolean isFinished = false;
        // 调试：流结束时打印总结构（message_start 含完整 message 对象 + input usage；message_delta 含 output usage）
        String startFrame = null;
        String deltaFrame = null;
    }

    private static void handleSseEvent(String data, ParseState s, StreamCallback cb) {
        if (data == null || data.isEmpty()) return;
        try {
            JsonObject evt = GSON.fromJson(data, JsonObject.class);
            if (evt == null) return;
            String eventType = evt.has("type") ? evt.get("type").getAsString() : "";
            switch (eventType) {
                case "message_start": {
                    s.startFrame = data; // 调试：记录总结构（完整 message 对象 + input usage）
                    JsonObject msg = evt.has("message") ? evt.getAsJsonObject("message") : null;
                    if (msg != null && msg.has("usage")) {
                        s.inputTokens = readInt(msg.getAsJsonObject("usage"), "input_tokens", 0);
                        // 缓存命中（cache_read_input_tokens）
                        JsonObject usageObj = msg.getAsJsonObject("usage");
                        if (usageObj.has("cache_read_input_tokens")) {
                            s.cacheReadTokens = readInt(usageObj, "cache_read_input_tokens", 0);
                        }
                    }
                    break;
                }
                case "content_block_start": {
                    JsonObject cbObj = evt.has("content_block") ? evt.getAsJsonObject("content_block") : null;
                    if (cbObj != null) {
                        String t = readString(cbObj, "type", "");
                        if ("tool_use".equals(t)) {
                            s.activeToolCall = new ChatMessage.ToolCall();
                            s.activeToolCall.setType("function");
                            s.activeToolCall.setId(readString(cbObj, "id", ""));
                            if (cbObj.has("name")) {
                                s.activeToolCall.setFunction(new ChatMessage.ToolCall.Function());
                                s.activeToolCall.getFunction().setName(cbObj.get("name").getAsString());
                            }
                            s.activeArgs.setLength(0);
                            s.activeToolIndex = s.toolOrdinal++;
                            if (s.accumulatedToolCalls.isEmpty()) cb.onToolCallPreparing();
                        } else if ("thinking".equals(t)) {
                            s.activeIsThinking = true;
                        }
                    }
                    break;
                }
                case "content_block_delta": {
                    JsonObject delta = evt.has("delta") ? evt.getAsJsonObject("delta") : null;
                    if (delta == null) break;
                    String dType = readString(delta, "type", "");
                    if ("text_delta".equals(dType)) {
                        String text = readString(delta, "text", "");
                        if (!text.isEmpty()) cb.onMessage(text);
                    } else if ("input_json_delta".equals(dType)) {
                        String partial = readString(delta, "partial_json", "");
                        if (!partial.isEmpty()) {
                            s.activeArgs.append(partial);
                            // 实时转发工具参数增量（流式写入/编辑可视化），与 OpenAI 链路对齐
                            if (s.activeToolIndex >= 0) cb.onToolArgsDelta(s.activeToolIndex, partial);
                        }
                    } else if ("thinking_delta".equals(dType)) {
                        String t = readString(delta, "thinking", "");
                        if (!t.isEmpty()) cb.onReasoning(t);
                    }
                    break;
                }
                case "content_block_stop": {
                    if (s.activeToolCall != null) {
                        if (s.activeToolCall.getFunction() == null) {
                            s.activeToolCall.setFunction(new ChatMessage.ToolCall.Function());
                        }
                        s.activeToolCall.getFunction().setArguments(s.activeArgs.toString());
                        s.accumulatedToolCalls.add(s.activeToolCall);
                        s.activeToolCall = null;
                        s.activeArgs.setLength(0);
                        s.activeToolIndex = -1;
                    }
                    s.activeIsThinking = false;
                    break;
                }
                case "message_delta": {
                    s.deltaFrame = data; // 调试：记录含 output usage 的帧
                    JsonObject usage = evt.has("usage") ? evt.getAsJsonObject("usage") : null;
                    if (usage != null) {
                        // output_tokens：message_delta 中的 usage 是「累计」值（截至当前的总输出 token）
                        s.outputTokens = readInt(usage, "output_tokens", 0);
                        // ★ 部分 Anthropic 兼容代理（如 DeepSeek anthropic 端点）会在 message_delta
                        //   的 usage 中覆盖 input_tokens（如 DeepSeek 在 message_start 报 3146，
                        //   message_delta 中更新为 9324）。取较大值，避免回退为更小的初始值。
                        int deltaInput = readInt(usage, "input_tokens", 0);
                        if (deltaInput > s.inputTokens) {
                            s.inputTokens = deltaInput;
                        }
                        // 缓存命中/未命中（部分代理在 usage 中携带）
                        if (usage.has("cache_read_input_tokens")) {
                            int cacheRead = readInt(usage, "cache_read_input_tokens", 0);
                            if (cacheRead > 0) s.cacheReadTokens = cacheRead;
                        }
                    }
                    break;
                }
                case "message_stop": {
                    // ★ 调试：流结束时打印本次响应的总结构（不逐 token 刷屏）
                    System.out.println("=== Anthropic SSE 响应总结构 ===");
                    System.out.println("message_start: " + s.startFrame);
                    if (s.deltaFrame != null) System.out.println("message_delta(usage): " + s.deltaFrame);
                    finishStream(cb, s, s.accumulatedToolCalls, s.inputTokens, s.outputTokens);
                    break;
                }
                case "ping":
                default:
                    break;
            }
        } catch (Exception e) {
            LOG.warn("Anthropic SSE parse failed: " + data, e);
        }
    }

    /** 解析单条完整 JSON 消息（非流式响应），把 thinking/text/tool_use 块分发成统一回调 */
    private static void handleCompleteMessage(JsonObject msg, ParseState s, StreamCallback cb) {
        if (msg == null) return;
        if (msg.has("usage")) {
            JsonObject usage = msg.getAsJsonObject("usage");
            s.inputTokens = readInt(usage, "input_tokens", s.inputTokens);
            s.outputTokens = readInt(usage, "output_tokens", s.outputTokens);
        }
        if (msg.has("content") && msg.get("content").isJsonArray()) {
            for (JsonElement e : msg.getAsJsonArray("content")) {
                if (!e.isJsonObject()) continue;
                JsonObject block = e.getAsJsonObject();
                String type = readString(block, "type", "");
                if ("thinking".equals(type)) {
                    String thinking = readString(block, "thinking", "");
                    if (!thinking.isEmpty()) cb.onReasoning(thinking);
                } else if ("text".equals(type)) {
                    String text = readString(block, "text", "");
                    if (!text.isEmpty()) cb.onMessage(text);
                } else if ("tool_use".equals(type)) {
                    ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                    tc.setType("function");
                    tc.setId(readString(block, "id", ""));
                    tc.setFunction(new ChatMessage.ToolCall.Function());
                    tc.getFunction().setName(readString(block, "name", ""));
                    JsonElement input = block.has("input") ? block.get("input") : null;
                    tc.getFunction().setArguments(input != null && !input.isJsonNull() ? GSON.toJson(input) : "{}");
                    s.accumulatedToolCalls.add(tc);
                }
            }
        }
        finishStream(cb, s, s.accumulatedToolCalls, s.inputTokens, s.outputTokens);
    }

    // ── 请求体构建：OpenAI 风格 messages → Anthropic Messages API ──

    private JsonObject buildRequestBody(List<ChatMessage> messages,
                                        List<ChatRequest.ToolDefinition> tools,
                                        ModelConfig model,
                                        CPSettings settings) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.getName());
        body.addProperty("max_tokens", Math.max(1, model.getMaxOutput()));
        double temp = model.getTemperature();
        body.addProperty("temperature", temp);
        // 请求流式响应（SSE）；部分代理忽略 stream 标志时仍返回单条 JSON，由解析层兼容
        body.addProperty("stream", true);

        // system 抽出来作为顶层字段
        StringBuilder system = new StringBuilder();
        JsonArray anthropicMessages = new JsonArray();

        for (ChatMessage m : messages) {
            String role = m.getRole();
            if ("system".equals(role)) {
                if (system.length() > 0) system.append("\n\n");
                system.append(m.getContent() == null ? "" : m.getContent());
                continue;
            }
            if ("tool".equals(role)) {
                // tool 结果 → user 消息里的 tool_result block
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_result");
                block.addProperty("tool_use_id", m.getTool_call_id() == null ? "" : m.getTool_call_id());
                block.addProperty("content", m.getContent() == null ? "" : m.getContent());
                content.add(block);
                userMsg.add("content", content);
                anthropicMessages.add(userMsg);
                continue;
            }
            // user / assistant
            JsonObject am = new JsonObject();
            am.addProperty("role", role);
            if (m.getTool_calls() != null && !m.getTool_calls().isEmpty()) {
                // assistant 带工具调用 → content 为 tool_use block 数组
                JsonArray content = new JsonArray();
                for (ChatMessage.ToolCall tc : m.getTool_calls()) {
                    JsonObject block = new JsonObject();
                    block.addProperty("type", "tool_use");
                    block.addProperty("id", tc.getId() == null ? "" : tc.getId());
                    block.addProperty("name", tc.getFunction() != null && tc.getFunction().getName() != null
                            ? tc.getFunction().getName() : "");
                    block.add("input", parseJsonObject(
                            tc.getFunction() != null ? tc.getFunction().getArguments() : "{}"));
                    content.add(block);
                }
                am.add("content", content);
            } else if (m.isInlineVision() && m.hasAttachments()) {
                // 主模型自带视觉：图片作为 image block 直传
                JsonArray content = new JsonArray();
                if (m.getContent() != null && !m.getContent().isEmpty()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("type", "text");
                    t.addProperty("text", m.getContent());
                    content.add(t);
                }
                for (ChatMessage.Attachment a : m.getAttachments()) {
                    String b64 = ChatMessage.encodeBase64(a.getPath());
                    if (b64 == null) continue;
                    String mime = (a.getMimeType() != null && !a.getMimeType().isEmpty())
                            ? a.getMimeType() : "image/png";
                    JsonObject img = new JsonObject();
                    img.addProperty("type", "image");
                    JsonObject srcObj = new JsonObject();
                    srcObj.addProperty("type", "base64");
                    srcObj.addProperty("media_type", mime);
                    srcObj.addProperty("data", b64);
                    img.add("source", srcObj);
                    content.add(img);
                }
                am.add("content", content);
            } else {
                am.addProperty("content", m.getContent() == null ? "" : m.getContent());
            }
            anthropicMessages.add(am);
        }

        body.add("messages", anthropicMessages);
        if (system.length() > 0) {
            body.addProperty("system", system.toString());
        }

        if (tools != null && !tools.isEmpty()) {
            JsonArray anthropicTools = new JsonArray();
            for (ChatRequest.ToolDefinition t : tools) {
                if (t.getFunction() == null) continue;
                JsonObject at = new JsonObject();
                at.addProperty("name", t.getFunction().getName());
                at.addProperty("description", t.getFunction().getDescription() == null
                        ? "" : t.getFunction().getDescription());
                JsonElement schema = GSON.toJsonTree(t.getFunction().getParameters());
                at.add("input_schema", schema.isJsonObject() ? schema.getAsJsonObject()
                        : new JsonObject());
                anthropicTools.add(at);
            }
            if (anthropicTools.size() > 0) body.add("tools", anthropicTools);
        }

        return body;
    }

    private static JsonObject parseJsonObject(String s) {
        try {
            JsonElement e = GSON.fromJson(s == null ? "{}" : s, JsonElement.class);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            return new JsonObject();
        }
    }

    private static String readString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int readInt(JsonObject o, String key, int def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def;
    }

    public void cancelCurrentStream() {
        if (currentCall != null) {
            try { currentCall.cancel(); } catch (Exception ignored) { }
        }
    }

    // ── 流式回调接口（与 DeepSeekClient 保持一致）──
    public interface StreamCallback {
        void onMessage(String content);
        void onReasoning(String reasoning);
        void onToolCalls(List<ChatMessage.ToolCall> toolCalls);
        void onToolCallPreparing();
        /** 工具参数增量到达时回调（流式写入可视化用），默认空实现 */
        default void onToolArgsDelta(int index, String deltaArgs) {}
        void onComplete();
        void onUsage(ChatResponse.Usage usage);
        void onError(Throwable error);
    }

}
