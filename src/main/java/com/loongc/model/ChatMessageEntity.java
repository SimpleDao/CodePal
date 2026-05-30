package com.loongc.model;

import java.sql.Timestamp;

public class ChatMessageEntity {

    public String id;
    public String conversationId;
    public String role;
    public String content;
    private String toolCallsJson;
    private String toolCallId;
    private String name;
    public String reasoningContent; // 增加思考过程字段
    public Timestamp createdAt;
    public ChatMessageEntity(String id,String conversationId,String role, String content,
                             String toolCallsJson,String toolCallId, String name,String reasoningContent) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.name = name;
        this.reasoningContent = reasoningContent;
        this.createdAt = new Timestamp(System.currentTimeMillis());
    }

    public ChatMessageEntity(String id,String role, String content,String toolCallsJson,String toolCallId, String name, String reasoningContent,Timestamp createdAt) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.name = name;
        this.reasoningContent = reasoningContent;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public void setToolCallsJson(String toolCallsJson) {
        this.toolCallsJson = toolCallsJson;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "ChatMessageEntity{" +
                "id='" + id + '\'' +
                ", conversationId='" + conversationId + '\'' +
                ", role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", toolCallsJson='" + toolCallsJson + '\'' +
                ", toolCallId='" + toolCallId + '\'' +
                ", name='" + name + '\'' +
                ", reasoningContent='" + reasoningContent + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
