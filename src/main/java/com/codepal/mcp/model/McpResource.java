package com.codepal.mcp.model;

/**
 * MCP Resource 定义 — MCP 服务器提供的可读数据源。
 */
public class McpResource {

    private String uri;
    private String name;
    private String description;
    private String mimeType;

    public McpResource() {}

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
