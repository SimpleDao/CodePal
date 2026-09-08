package com.codepal.mcp.model;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP Tool 定义 — 对应 MCP 协议 tools/list 返回的工具描述。
 */
public class McpTool {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;
    private String serverName;

    public McpTool() {}

    public McpTool(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /** 转换为 DeepSeek API function-calling 格式 */
    public Map<String, Object> toFunctionDefinition() {
        Map<String, Object> function = new HashMap<>();
        function.put("name", name);
        function.put("description", description != null ? description : "");
        function.put("parameters", inputSchema != null ? inputSchema : Map.of());

        Map<String, Object> def = new HashMap<>();
        def.put("type", "function");
        def.put("function", function);
        return def;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    @Override
    public String toString() {
        return "McpTool{name='" + name + "', server='" + serverName + "'}";
    }
}
