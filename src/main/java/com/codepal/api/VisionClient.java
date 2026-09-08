package com.codepal.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.codepal.model.ModelConfig;
import com.codepal.settings.CPSettings;
import com.codepal.api.AnthropicClient;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 视觉模型客户端（一次性请求，非流式）。
 *
 * <p>主模型通过 view_image 工具间接"看"图片：本类把图片（base64）与文字 prompt 一起
 * 发给独立配置的视觉模型，返回其文字描述/答案。
 *
 * <p>按视觉模型的 apiFormat 路由：
 * <ul>
 *   <li>openai     —— OpenAI 兼容（content 数组 + image_url block）</li>
 *   <li>anthropic  —— Anthropic 原生 Messages API（content 数组 + image block）</li>
 * </ul>
 */
public class VisionClient {
    private static final Logger LOG = Logger.getInstance(VisionClient.class);
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 读取图片文件并用视觉模型描述其内容。
     *
     * @param imagePath 图片绝对路径
     * @param mimeType  图片 MIME（如 image/png），可空（按扩展名推断）
     * @param question  针对图片的问题，可空（通用描述）
     * @return 视觉模型返回的文字描述
     */
    public String describe(String imagePath, String mimeType, String question) throws IOException {
        long t0 = System.currentTimeMillis();
        System.out.println("[VisionClient] describe 开始 thread=" + Thread.currentThread().getName()
                + " imagePath=" + imagePath);
        ModelConfig model = CPSettings.getInstance().getEffectiveVisionModel();
        if (model == null) {
            System.out.println("[VisionClient] 视觉模型未启用/未配置，立即返回错误（耗时 " + (System.currentTimeMillis() - t0) + "ms）");
            return "错误：视觉模型未启用或未配置（请在 CP 设置中开启「视觉模型」并填写 API Key）。";
        }

        byte[] bytes;
        String mediaType;
        try {
            // 发送前压缩，避免整图 base64 过大（参考 auto-dev ImageCompressor）
            long tComp = System.currentTimeMillis();
            bytes = com.codepal.util.ImageCompressor.compress(Paths.get(imagePath).toFile());
            System.out.println("[VisionClient] 图片压缩完成 耗时=" + (System.currentTimeMillis() - tComp)
                    + "ms 压缩后=" + bytes.length + "字节");
            mediaType = "image/jpeg";
        } catch (Exception e) {
            // 压缩失败则退回原图
            System.out.println("[VisionClient] 压缩失败（退回原图）: " + e.getMessage());
            try {
                bytes = Files.readAllBytes(Paths.get(imagePath));
            } catch (IOException io) {
                return "错误：无法读取图片文件 —— " + imagePath;
            }
            mediaType = (mimeType != null && !mimeType.isEmpty()) ? mimeType : guessMime(imagePath);
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);

        String prompt = (question != null && !question.trim().isEmpty())
                ? question
                : "请详细描述这张图片的内容，包括其中的文字、界面、图表、代码或任何关键信息。";

        String url;
        if (ModelConfig.FORMAT_ANTHROPIC.equals(model.getApiFormat())) {
            // 与 AnthropicClient 一致：用 normalizeAnthropicUrl 容错拼接 /v1/messages
            String raw = model.getApiBase();
            if (raw == null || raw.trim().isEmpty()) raw = "https://api.anthropic.com/v1/messages";
            url = AnthropicClient.normalizeAnthropicUrl(raw);
        } else {
            // OpenAI 兼容：基址自动补 /v1/chat/completions（对末尾斜杠 / 已含后缀都容错，
            // 避免拼接出 .../v1/chat/completions/v1/chat/completions 这类路径重复错误）
            String raw = model.getApiBase();
            if (raw == null || raw.trim().isEmpty()) raw = "https://api.openai.com";
            url = normalizeOpenAiUrl(raw);
        }

        Request.Builder reqBuilder = new Request.Builder().url(url).post(
                RequestBody.create(buildBody(model, prompt, base64, mediaType), JSON));

        if (ModelConfig.FORMAT_ANTHROPIC.equals(model.getApiFormat())) {
            reqBuilder.header("x-api-key", model.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json");
        } else {
            reqBuilder.header("Authorization", "Bearer " + model.getApiKey())
                    .header("Content-Type", "application/json");
        }

        System.out.println("[VisionClient] 发起 HTTP 请求 url=" + url + " model=" + model.getName()
                + " 累计耗时=" + (System.currentTimeMillis() - t0) + "ms");
        try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
            System.out.println("[VisionClient] HTTP 请求完成 耗时=" + (System.currentTimeMillis() - t0)
                    + "ms HTTP=" + resp.code());
            if (!resp.isSuccessful() || resp.body() == null) {
                String body = resp.body() != null ? resp.body().string() : "";
                // 完整错误响应打到日志，方便排查；同时解析出人类可读的失败原因展示给用户
                System.out.println("[VisionClient] 视觉模型返回错误 HTTP=" + resp.code() + " body=" + body);
                LOG.warn("VisionClient 视觉模型请求失败 HTTP=" + resp.code() + " body=" + body);
                String reason = extractErrorReason(body);
                return "错误：视觉模型请求失败（HTTP " + resp.code() + "）：" + reason
                        + "\n原始响应：" + (body == null ? "" : body.trim());
            }
            String json = resp.body().string();
            String parsed = parseText(model.getApiFormat(), json);
            System.out.println("[VisionClient] 解析完成 总耗时=" + (System.currentTimeMillis() - t0)
                    + "ms 返回长度=" + parsed.length());
            return parsed;
        }
    }

    private String buildBody(ModelConfig model, String prompt, String base64, String mediaType) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.getName());

        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt);
        content.add(textPart);

        if (ModelConfig.FORMAT_ANTHROPIC.equals(model.getApiFormat())) {
            body.addProperty("max_tokens", Math.max(1, model.getMaxOutput()));
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image");
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", mediaType);
            source.addProperty("data", base64);
            imgPart.add("source", source);
            content.add(imgPart);
        } else {
            body.addProperty("max_tokens", Math.max(1, model.getMaxOutput()));
            body.addProperty("temperature", model.getTemperature());
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:" + mediaType + ";base64," + base64);
            imgPart.add("image_url", imageUrl);
            content.add(imgPart);
        }

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        messages.add(msg);
        body.add("messages", messages);
        return GSON.toJson(body);
    }

    /**
     * 从错误响应体里提取人类可读的失败原因。
     * 兼容 OpenAI（{"error":{"message":...,"type":...}}）与 Anthropic（{"error":"..."} 或 {"error":{"message":...}}）等常见结构；
     * 非 JSON / 解析失败时回退到原始响应体（去掉多余空白），绝不直接丢弃原因。
     */
    private String extractErrorReason(String body) {
        if (body == null || body.isBlank()) return "(服务器未返回错误详情)";
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root != null && root.has("error")) {
                JsonElement err = root.get("error");
                if (err.isJsonObject()) {
                    JsonObject eo = err.getAsJsonObject();
                    String msg = eo.has("message") && !eo.get("message").isJsonNull()
                            ? eo.get("message").getAsString() : null;
                    String type = eo.has("type") && !eo.get("type").isJsonNull()
                            ? eo.get("type").getAsString() : null;
                    if (msg != null && !msg.isBlank()) {
                        return (type != null && !type.isBlank()) ? (type + "：" + msg) : msg;
                    }
                    if (type != null && !type.isBlank()) return type;
                    return eo.toString();
                } else if (err.isJsonPrimitive()) {
                    return err.getAsString();
                }
            }
        } catch (Exception ignored) {
            // 解析失败则回退到原始响应
        }
        return body.trim();
    }

    private String parseText(String apiFormat, String json) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return "错误：视觉模型返回空响应。";
            if (ModelConfig.FORMAT_ANTHROPIC.equals(apiFormat)) {
                StringBuilder sb = new StringBuilder();
                if (root.has("content")) {
                    for (JsonElement e : root.getAsJsonArray("content")) {
                        if (e.isJsonObject() && "text".equals(e.getAsJsonObject().get("type").getAsString())) {
                            sb.append(e.getAsJsonObject().get("text").getAsString());
                        }
                    }
                }
                return sb.toString();
            } else {
                if (root.has("choices")) {
                    JsonArray choices = root.getAsJsonArray("choices");
                    if (!choices.isEmpty()) {
                        JsonObject msg = choices.get(0).getAsJsonObject()
                                .getAsJsonObject("message");
                        return msg.has("content") && !msg.get("content").isJsonNull()
                                ? msg.get("content").getAsString() : "";
                    }
                }
                if (root.has("error")) {
                    return "错误：视觉模型返回错误 —— " + root.getAsJsonObject("error").toString();
                }
                return "错误：视觉模型返回格式无法解析 —— " + json;
            }
        } catch (Exception e) {
            LOG.warn("VisionClient parse failed: " + json, e);
            return "错误：解析视觉模型响应失败 —— " + e.getMessage();
        }
    }

    /**
     * 规范 OpenAI 兼容端点 URL。用户填写的 apiBase 可能是「基址」（如 https://api.openai.com）、
     * 带末尾斜杠、甚至是完整路径（如 https://my-proxy/api-openai/v1/chat/completions）。
     * 对重复/缺失的后缀都容错，避免拼接出 /v1/chat/completions/v1/chat/completions 这类 404 错误。
     */
    public static String normalizeOpenAiUrl(String apiBase) {
        String u = apiBase != null ? apiBase.trim() : "";
        if (u.isEmpty()) return "https://api.openai.com/v1/chat/completions";
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1/chat/completions")) return u;
        return u + "/v1/chat/completions";
    }

    private static String guessMime(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }
}
