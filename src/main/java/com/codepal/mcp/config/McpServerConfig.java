package com.codepal.mcp.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * MCP Server 配置模型。
 *
 * <p>支持 stdio（本地进程启动）和远程连接：
 * <pre>{@code
 * // 本地进程
 * npx -y @modelcontextprotocol/server-brave-search
 * // 远程连接
 * npx -y mcp-remote https://mcp.tavily.com/mcp/?tavilyApiKey=xxx
 * }</pre>
 */
public class McpServerConfig {

    private static final Gson GSON = new Gson();

    private String name;
    private String command;
    private List<String> args = new ArrayList<>();
    private String workingDir;
    private Map<String, String> env = new HashMap<>();
    private boolean enabled = false;

    // ── 预设模板 ──

    public static McpServerConfig braveSearch() {
        McpServerConfig cfg = new McpServerConfig();
        cfg.name = "brave-search";
        cfg.command = "npx";
        cfg.args = List.of("-y", "@modelcontextprotocol/server-brave-search");
        cfg.env = new HashMap<>();
        cfg.env.put("BRAVE_API_KEY", "");
        return cfg;
    }

    public static McpServerConfig tavilySearch() {
        McpServerConfig cfg = new McpServerConfig();
        cfg.name = "tavily-search";
        cfg.command = "npx";
        cfg.args = List.of("-y", "tavily-mcp");
        cfg.env = new HashMap<>();
        cfg.env.put("TAVILY_API_KEY", "");
        return cfg;
    }

    // ── JSON 解析 ──

    public static McpServerConfig fromJson(JsonObject obj) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(getStr(obj, "name"));
        cfg.setCommand(getStr(obj, "command"));
        if (obj.has("args") && obj.get("args").isJsonArray()) {
            List<String> args = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray("args")) args.add(e.getAsString());
            cfg.setArgs(args);
        }
        if (obj.has("workingDir")) cfg.setWorkingDir(obj.get("workingDir").getAsString());
        if (obj.has("env") && obj.get("env").isJsonObject()) {
            Map<String, String> env = new HashMap<>();
            JsonObject envObj = obj.getAsJsonObject("env");
            for (String key : envObj.keySet()) env.put(key, envObj.get(key).getAsString());
            cfg.setEnv(env);
        }
        if (obj.has("enabled")) cfg.setEnabled(obj.get("enabled").getAsBoolean());
        return cfg;
    }

    public static McpServerConfig fromJson(String json) {
        return fromJson(GSON.fromJson(json, JsonObject.class));
    }

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    // ── 序列化 ──

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("command", command != null ? command : "");
        obj.addProperty("enabled", enabled);
        if (workingDir != null && !workingDir.isEmpty()) obj.addProperty("workingDir", workingDir);
        JsonArray arr = new JsonArray();
        for (String a : args) arr.add(a);
        obj.add("args", arr);
        JsonObject envObj = new JsonObject();
        for (Map.Entry<String, String> e : env.entrySet()) envObj.addProperty(e.getKey(), e.getValue());
        obj.add("env", envObj);
        return obj;
    }

    public String toJsonString() {
        return GSON.toJson(toJson());
    }

    // ── 工具 ──

    public String[] toCommandArray() {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        return cmd.toArray(new String[0]);
    }

    /** 返回完整的连接命令字符串（便于显示） */
    public String toCommandString() {
        StringBuilder sb = new StringBuilder(command);
        for (String a : args) sb.append(" ").append(a);
        return sb.toString();
    }

    public McpServerConfig copy() {
        McpServerConfig cp = new McpServerConfig();
        cp.name = this.name;
        cp.command = this.command;
        cp.args = new ArrayList<>(this.args);
        cp.workingDir = this.workingDir;
        cp.env = new HashMap<>(this.env);
        cp.enabled = this.enabled;
        return cp;
    }

    // ── getter / setter ──

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public String toString() {
        return "McpServerConfig{'" + name + "', " + toCommandString() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof McpServerConfig)) return false;
        return Objects.equals(name, ((McpServerConfig) o).name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
