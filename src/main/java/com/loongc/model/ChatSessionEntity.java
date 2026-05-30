package com.loongc.model;

import java.sql.Timestamp;

public class ChatSessionEntity {

    public String id;
    public String name;
    public Timestamp createdAt;
    //不存储于数据库
    private int chatMessageCount;

    public ChatSessionEntity(String id, String name) {
        this.id = id;
        this.name = name;
    }


    public ChatSessionEntity(String id, String name,Timestamp createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public ChatSessionEntity(String id, String name, Timestamp createdAt,int chatMessageCount) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.chatMessageCount = chatMessageCount;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public int getChatMessageCount() {
        return chatMessageCount;
    }

    public void setChatMessageCount(int chatMessageCount) {
        this.chatMessageCount = chatMessageCount;
    }

    @Override
    public String toString() {
        return "ChatSessionEntity{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
