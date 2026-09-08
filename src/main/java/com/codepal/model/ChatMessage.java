package com.codepal.model;

import java.util.List;

/**
 * 聊天消息模型
 * 支持 Function Calling：role 可为 "system" / "user" / "assistant" / "tool"
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ChatMessage {
    private String role;
    private String content;
    // DeepSeek reasoning 模型的思考过程（流式时出现在 delta 中，非流式时出现在 message 中）
    private String reasoning_content;
    // 用于 assistant 角色的工具调用请求
    private List<ToolCall> tool_calls;
    // 用于 tool 角色的消息
    private String tool_call_id;
    private String name;
    private transient int qaRound; // QA 分组 ID（不序列化到 API）

    // 图片附件（视觉模型用）。transient：不进发给主模型的 API 请求，
    // 主模型通过 view_image 工具间接使用，而非直接读取字节。
    private transient List<Attachment> attachments;

    // 主模型自带视觉能力（supportsVision）时，附图以多模态 content 直传主模型，
    // 而非走 view_image 子智能体。transient：仅运行时用于决定序列化方式，不进 API 请求体。
    private transient boolean inlineVision = false;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // 使用工具调用的 assistant 消息
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        ChatMessage msg = new ChatMessage();
        msg.role = "assistant";
        msg.content = null; // 有 tool_calls 时 content 可为 null
        msg.tool_calls = toolCalls;
        return msg;
    }

    // tool 角色的消息（工具执行结果）
    public static ChatMessage toolResult(String toolCallId,String toolName, String result) {
        ChatMessage msg = new ChatMessage();
        msg.role = "tool";
        msg.name = toolName;
        msg.tool_call_id = toolCallId;
        msg.content = result;
        return msg;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getReasoning_content() { return reasoning_content; }
    public void setReasoning_content(String reasoning_content) { this.reasoning_content = reasoning_content; }
    public List<ToolCall> getTool_calls() { return tool_calls; }
    public void setTool_calls(List<ToolCall> tool_calls) { this.tool_calls = tool_calls; }
    public String getTool_call_id() { return tool_call_id; }
    public void setTool_call_id(String tool_call_id) { this.tool_call_id = tool_call_id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getQaRound() { return qaRound; }
    public void setQaRound(int qaRound) { this.qaRound = qaRound; }

    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) { this.attachments = attachments; }
    public boolean hasAttachments() { return attachments != null && !attachments.isEmpty(); }

    public boolean isInlineVision() { return inlineVision; }
    public void setInlineVision(boolean inlineVision) { this.inlineVision = inlineVision; }

    /** 读取图片文件为 base64（用于多模态直传主模型）；发送前先压缩，避免整图 base64 过大。失败返回 null */
    public static String encodeBase64(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] bytes;
            if (f.exists()) {
                try {
                    bytes = com.codepal.util.ImageCompressor.compress(f); // 优先压缩后编码
                } catch (Exception ignore) {
                    bytes = java.nio.file.Files.readAllBytes(f.toPath()); // 压缩失败则退回原图
                }
            } else {
                bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            }
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", reasoning_content='" + reasoning_content + '\'' +
                ", tool_calls=" + tool_calls +
                ", tool_call_id='" + tool_call_id + '\'' +
                ", name='" + name + '\'' +
                '}';
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部类：工具调用定义
    // ─────────────────────────────────────────────────────────────────────────
    public static class ToolCall {
        private String id;
        private String type = "function";
        private Function function;
        // OpenAI/DeepSeek 流式分片用 index 区分并行工具调用；也用于把"只有 arguments、无 id/name"的续帧归位到正确的调用
        private Integer index;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Function getFunction() { return function; }
        public void setFunction(Function function) { this.function = function; }
        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }

        public static class Function {
            private String name;
            private String arguments; // JSON 字符串

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getArguments() { return arguments; }
            public void setArguments(String arguments) { this.arguments = arguments; }

            @Override
            public String toString() {
                return "Function{" +
                        "name='" + name + '\'' +
                        ", arguments='" + arguments + '\'' +
                        '}';
            }
        }

        @Override
        public String toString() {
            return "ToolCall{" +
                    "id='" + id + '\'' +
                    ", type='" + type + '\'' +
                    ", function=" + function +
                    '}';
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部类：图片附件（视觉模型用）
    // ─────────────────────────────────────────────────────────────────────────
    public static class Attachment {
        private String fileName;
        private String path;     // 落盘的临时文件路径（绝对路径）
        private String mimeType; // 如 image/png

        public Attachment() {}

        public Attachment(String fileName, String path, String mimeType) {
            this.fileName = fileName;
            this.path = path;
            this.mimeType = mimeType;
        }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    }

}
