package com.codepal.model;

/**
 * 消息实体 — 对应 messages 表（消息头，不含内容，内容下沉到 MessagePartEntity）
 */
public class ChatMessageEntity {

    public String id;
    public String sessionId;
    public String role;          // user / assistant（不再有 tool 角色）
    public int seq;              // 会话内全局序号
    public String modelId;       // 仅 assistant 消息
    public int tokens;
    public int qaRound;          // QA 分组
    public long createdAt;
    public long updatedAt;
    public String tokenUsage;    // JSON，token 用量明细
    public String meta;          // JSON，预留扩展

    public ChatMessageEntity() {}

    /** 最小构造：user 消息 */
    public ChatMessageEntity(String id, String sessionId, String role, String content) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /** 兼容旧构造器（tool 相关字段已废弃，仅保留签名兼容） */
    @Deprecated
    public ChatMessageEntity(String id, String sessionId, String role, String content,
                             String toolCallsJson, String toolCallId, String name, String reasoningContent) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public ChatMessageEntity withRound(int qaRound) { this.qaRound = qaRound; return this; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /** @deprecated use getSessionId() */
    @Deprecated
    public String getConversationId() { return sessionId; }
    /** @deprecated use setSessionId() */
    @Deprecated
    public void setConversationId(String id) { this.sessionId = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }

    public int getQaRound() { return qaRound; }
    public void setQaRound(int qaRound) { this.qaRound = qaRound; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(String tokenUsage) { this.tokenUsage = tokenUsage; }

    public String getMeta() { return meta; }
    public void setMeta(String meta) { this.meta = meta; }

    // ── 以下为旧字段兼容桩，标记 @Deprecated 引导编译期发现调用点 ──

    /** @deprecated 内容已迁移到 MessagePartEntity，不再存消息表 */
    @Deprecated
    public String getContent() { return null; }
    @Deprecated
    public void setContent(String content) { /* no-op */ }

    /** @deprecated 工具调用已迁移到 MessagePartEntity.meta */
    @Deprecated
    public String getToolCallsJson() { return null; }
    @Deprecated
    public void setToolCallsJson(String json) { /* no-op */ }

    /** @deprecated 工具调用 ID 已迁移到 MessagePartEntity.meta */
    @Deprecated
    public String getToolCallId() { return null; }
    @Deprecated
    public void setToolCallId(String id) { /* no-op */ }

    /** @deprecated 工具名已迁移到 MessagePartEntity.meta */
    @Deprecated
    public String getName() { return null; }
    @Deprecated
    public void setName(String name) { /* no-op */ }

    /** @deprecated 思考过程已迁移到 MessagePartEntity(kind=thinking) */
    @Deprecated
    public String getReasoningContent() { return null; }
    @Deprecated
    public void setReasoningContent(String content) { /* no-op */ }

    @Override
    public String toString() {
        return "ChatMessageEntity{" +
                "id='" + id + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", role='" + role + '\'' +
                ", seq=" + seq +
                ", qaRound=" + qaRound +
                '}';
    }
}