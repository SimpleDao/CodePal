package com.codepal.model;

/**
 * 消息内容分片实体 — 对应 message_parts 表
 *
 * <p>一条消息由一个或多个 part 按 seq 排列组成：
 * <pre>
 *   user 消息:    [text]                         — 用户输入
 *   assistant 消息: [thinking] [text] [tool] [thinking] [tool] [text] ...  — 交替出现
 * </pre>
 *
 * <p>kind 取值：
 * <ul>
 *   <li>thinking — 思考过程，content 存文本，meta 为 NULL</li>
 *   <li>text     — 正文回复，content 存文本，meta 为 NULL</li>
 *   <li>tool     — 工具调用，content 为 NULL，meta 存 JSON：
 *       <code>{"toolName":"...","toolInput":"...","toolOutput":"...","toolStatus":"done"}</code></li>
 *   <li>error    — 异常信息，content 存错误描述，meta 为 NULL</li>
 * </ul>
 */
public class MessagePartEntity {

    public String id;
    public String messageId;
    public String sessionId;     // 冗余，方便跨消息直接查 parts
    public String kind;          // thinking / text / tool / error
    public String content;       // thinking/text/error 时存文本；tool 时为 NULL
    public String meta;          // tool 时存 JSON 元数据；其他 kind 为 NULL
    public String status;        // pending / done
    public int seq;              // 消息内排序
    public long createdAt;

    public MessagePartEntity() {}

    /** 快捷构造：text part */
    public static MessagePartEntity text(String messageId, String sessionId, String content, int seq) {
        MessagePartEntity p = new MessagePartEntity();
        p.id = java.util.UUID.randomUUID().toString().replace("-", "");
        p.messageId = messageId;
        p.sessionId = sessionId;
        p.kind = "text";
        p.content = content;
        p.status = "done";
        p.seq = seq;
        p.createdAt = System.currentTimeMillis();
        return p;
    }

    /** 快捷构造：thinking part */
    public static MessagePartEntity thinking(String messageId, String sessionId, String content, int seq) {
        MessagePartEntity p = new MessagePartEntity();
        p.id = java.util.UUID.randomUUID().toString().replace("-", "");
        p.messageId = messageId;
        p.sessionId = sessionId;
        p.kind = "thinking";
        p.content = content;
        p.status = "done";
        p.seq = seq;
        p.createdAt = System.currentTimeMillis();
        return p;
    }

    /** 快捷构造：tool part */
    public static MessagePartEntity tool(String messageId, String sessionId, String toolName,
                                          String toolInput, String toolOutput, String toolStatus, int seq) {
        MessagePartEntity p = new MessagePartEntity();
        p.id = java.util.UUID.randomUUID().toString().replace("-", "");
        p.messageId = messageId;
        p.sessionId = sessionId;
        p.kind = "tool";
        p.meta = "{\"toolName\":\"" + escapeJson(toolName) + "\"" +
                 ",\"toolInput\":" + (toolInput != null ? toolInput : "{}") +
                 ",\"toolOutput\":" + (toolOutput != null ? "\"" + escapeJson(toolOutput) + "\"" : "null") +
                 ",\"toolStatus\":\"" + escapeJson(toolStatus) + "\"}";
        p.status = toolStatus;
        p.seq = seq;
        p.createdAt = System.currentTimeMillis();
        return p;
    }

    /** 快捷构造：error part */
    public static MessagePartEntity error(String messageId, String sessionId, String errorMsg, int seq) {
        MessagePartEntity p = new MessagePartEntity();
        p.id = java.util.UUID.randomUUID().toString().replace("-", "");
        p.messageId = messageId;
        p.sessionId = sessionId;
        p.kind = "error";
        p.content = errorMsg;
        p.status = "done";
        p.seq = seq;
        p.createdAt = System.currentTimeMillis();
        return p;
    }

    /**
     * 从 tool part 的 meta JSON 中安全提取 toolInput 字符串。
     * <p>存储形态是「内嵌 JSON 对象」（如 {"file_path":"A.java","start_line":"1"}），
     * 直接对 JsonObject 调 getAsString() 会抛 UnsupportedOperationException 且被静默吞掉，
     * 导致历史回显丢失参数信息（标题只剩「读取」「编辑」等裸动词）。
     * 此方法兼容「内嵌对象」与「字符串」两种形态。
     */
    public static String extractToolInput(com.google.gson.JsonObject meta) {
        if (meta == null || !meta.has("toolInput")) return "{}";
        com.google.gson.JsonElement el = meta.get("toolInput");
        if (el == null || el.isJsonNull()) return "{}";
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMeta() { return meta; }
    public void setMeta(String meta) { this.meta = meta; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "MessagePartEntity{" +
                "id='" + id + '\'' +
                ", kind='" + kind + '\'' +
                ", seq=" + seq +
                ", status='" + status + '\'' +
                '}';
    }
}