package com.codepal.mcp.model;

/**
 * MCP Content 类型 — tools/call 返回的结果内容块。
 */
public class McpContent {

    private String type;
    private String text;
    private String data;
    private String mimeType;

    public McpContent() {}

    public static McpContent text(String text) {
        McpContent c = new McpContent();
        c.type = "text";
        c.text = text;
        return c;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    @Override
    public String toString() {
        if ("text".equals(type)) return text != null ? text : "";
        return "[" + type + "]";
    }
}
