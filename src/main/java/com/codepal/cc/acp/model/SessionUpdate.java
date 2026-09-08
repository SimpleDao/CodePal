package com.codepal.cc.acp.model;

import java.util.List;

/**
 * ACP session/update 通知体，Agent 用此向 Client 推送实时状态。
 */
public class SessionUpdate {

    private String sessionUpdate;
    private ContentBlock content;
    private String toolCallId;
    private String title;
    private String kind;
    private String status;
    private List<PlanEntry> entries;

    public boolean isTextChunk() {
        return "agent_message_chunk".equals(sessionUpdate);
    }

    public boolean isThoughtChunk() {
        return "agent_thought_chunk".equals(sessionUpdate);
    }

    public boolean isToolCall() {
        return "tool_call".equals(sessionUpdate);
    }

    public boolean isToolCallUpdate() {
        return "tool_call_update".equals(sessionUpdate);
    }

    public boolean isPlan() {
        return "plan".equals(sessionUpdate);
    }

    public String extractText() {
        if (content != null && "text".equals(content.getType())) {
            return content.getText();
        }
        return "";
    }

    public String getSessionUpdate() { return sessionUpdate; }
    public void setSessionUpdate(String sessionUpdate) { this.sessionUpdate = sessionUpdate; }
    public ContentBlock getContent() { return content; }
    public void setContent(ContentBlock content) { this.content = content; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<PlanEntry> getEntries() { return entries; }
    public void setEntries(List<PlanEntry> entries) { this.entries = entries; }

    public static class PlanEntry {
        private String content;
        private String priority;
        private String status;

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
