package com.loongc.model;

public class HistoryItem {

    private ChatSessionEntity session;
    private String title;
    private String info;

    public HistoryItem(ChatSessionEntity session, String title, String info) {
        this.session = session;
        this.title = title;
        this.info = info;
    }

    public ChatSessionEntity getSession() {
        return session;
    }

    public void setSession(ChatSessionEntity session) {
        this.session = session;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    @Override
    public String toString() {
        return "HistoryItem{" +
                "session=" + session +
                ", title='" + title + '\'' +
                ", info='" + info + '\'' +
                '}';
    }

}
