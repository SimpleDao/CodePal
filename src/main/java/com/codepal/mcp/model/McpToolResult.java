package com.codepal.mcp.model;

import java.util.List;

/**
 * MCP Tool 调用结果。
 */
public class McpToolResult {

    private List<McpContent> content;
    private boolean isError;
    private String rawJson;

    public McpToolResult() {}

    public static McpToolResult success(String text) {
        McpToolResult r = new McpToolResult();
        r.content = List.of(McpContent.text(text));
        r.isError = false;
        return r;
    }

    public static McpToolResult error(String text) {
        McpToolResult r = new McpToolResult();
        r.content = List.of(McpContent.text(text));
        r.isError = true;
        return r;
    }

    public String extractText() {
        if (content == null || content.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (McpContent c : content) {
            if ("text".equals(c.getType()) && c.getText() != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(c.getText());
            }
        }
        return sb.toString();
    }

    public List<McpContent> getContent() { return content; }
    public void setContent(List<McpContent> content) { this.content = content; }
    public boolean isError() { return isError; }
    public void setError(boolean error) { isError = error; }
    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    @Override
    public String toString() {
        return extractText();
    }
}
