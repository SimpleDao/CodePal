package com.codepal.model;

/**
 * 会话实体 — 对应 sessions 表
 */
public class ChatSessionEntity {

    public String id;
    public String title;
    public String mode;
    public String modelId;
    public String preview;
    public boolean pinned;
    public long createdAt;
    public long updatedAt;
    public String projectPath;

    // 非持久化字段：消息计数
    private int messageCount;

    public ChatSessionEntity() {}

    public ChatSessionEntity(String id, String title) {
        this.id = id;
        this.title = title;
        this.mode = "chat";
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public ChatSessionEntity(String id, String title, long createdAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public ChatSessionEntity(String id, String title, long createdAt, int messageCount) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.messageCount = messageCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    /** @deprecated use getTitle() */
    @Deprecated
    public String getName() { return title; }
    /** @deprecated use setTitle() */
    @Deprecated
    public void setName(String name) { this.title = name; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    /** @deprecated use getMessageCount() */
    @Deprecated
    public int getChatMessageCount() { return messageCount; }
    /** @deprecated use setMessageCount() */
    @Deprecated
    public void setChatMessageCount(int count) { this.messageCount = count; }

    @Override
    public String toString() {
        return "ChatSessionEntity{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", mode='" + mode + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}