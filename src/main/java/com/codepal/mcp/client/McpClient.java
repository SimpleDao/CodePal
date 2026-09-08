package com.codepal.mcp.client;

import com.google.gson.*;
import com.codepal.mcp.config.McpServerConfig;
import com.codepal.mcp.model.McpContent;
import com.codepal.mcp.model.McpResource;
import com.codepal.mcp.model.McpTool;
import com.codepal.mcp.model.McpToolResult;
import com.codepal.mcp.transport.McpTransport;
import com.codepal.mcp.transport.StdioTransport;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP JSON-RPC Client — 与单个 MCP Server 通信。
 */
public class McpClient {

    private static final Gson GSON = new Gson();

    private final String serverName;
    private final McpTransport transport;
    private Thread readerThread;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private final ConcurrentMap<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private JsonObject serverCapabilities;
    private JsonObject serverInfo;

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    public McpClient(String serverName, McpServerConfig config) {
        this(serverName, new StdioTransport(config.toCommandArray(), config.getWorkingDir()));
    }

    // ==================== 生命周期 ====================

    public void start() throws Exception {
        transport.start();
        readerThread = new Thread(this::readLoop, "MCP-" + serverName + "-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // initialize 握手
        JsonObject initParams = new JsonObject();
        initParams.addProperty("protocolVersion", "2024-11-05");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        capabilities.add("resources", new JsonObject());
        initParams.add("capabilities", capabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "CP-MCP");
        clientInfo.addProperty("version", "1.0.0");
        initParams.add("clientInfo", clientInfo);

        JsonObject initResult = sendRequest("initialize", initParams);
        if (initResult.has("result")) {
            JsonObject result = initResult.getAsJsonObject("result");
            serverCapabilities = result.getAsJsonObject("capabilities");
            serverInfo = result.getAsJsonObject("serverInfo");
        }
        sendNotification("notifications/initialized", null);
        initialized = true;
    }

    public void stop() {
        initialized = false;
        for (CompletableFuture<JsonObject> f : pending.values()) {
            f.completeExceptionally(new IOException("Client stopped"));
        }
        pending.clear();
        transport.stop();
    }

    public boolean isRunning() { return transport.isRunning(); }
    public boolean isInitialized() { return initialized; }
    public String getServerName() { return serverName; }
    public JsonObject getServerCapabilities() { return serverCapabilities; }
    public JsonObject getServerInfo() { return serverInfo; }

    // ==================== 协议方法 ====================

    public List<McpTool> listTools() throws Exception {
        JsonObject result = sendRequest("tools/list", null);
        return parseTools(result);
    }

    public List<McpResource> listResources() throws Exception {
        JsonObject result = sendRequest("resources/list", null);
        return parseResources(result);
    }

    public String readResource(String uri) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        JsonObject result = sendRequest("resources/read", params);
        return parseResourceContent(result);
    }

    public McpToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", GSON.toJsonTree(arguments));
        try {
            JsonObject result = sendRequest("tools/call", params);
            return parseToolResult(result);
        } catch (Exception e) {
            return McpToolResult.error("Tool call failed: " + e.getMessage());
        }
    }

    // ==================== JSON-RPC 通信 ====================

    private void readLoop() {
        try {
            String line;
            while (transport.isRunning() && (line = transport.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                    handleMessage(msg);
                } catch (JsonParseException e) {
                    System.err.println("[MCP:" + serverName + "] Parse error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (transport.isRunning())
                System.err.println("[MCP:" + serverName + "] Read error: " + e.getMessage());
        }
    }

    private void handleMessage(JsonObject msg) {
        if (msg.has("id") && !msg.has("method")) {
            int id = msg.get("id").getAsInt();
            CompletableFuture<JsonObject> f = pending.remove(id);
            if (f != null) {
                if (msg.has("error")) {
                    String errMsg = msg.getAsJsonObject("error").get("message").getAsString();
                    f.completeExceptionally(new RuntimeException(errMsg));
                } else {
                    f.complete(msg);
                }
            }
        }
    }

    private JsonObject sendRequest(String method, JsonObject params) throws Exception {
        return sendRequest(method, params, 60);
    }

    private JsonObject sendRequest(String method, JsonObject params, int timeoutSec) throws Exception {
        int id = requestId.getAndIncrement();
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        req.addProperty("method", method);
        if (params != null) req.add("params", params);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        sendRaw(req.toString());

        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RuntimeException("MCP request timeout: " + method);
        }
    }

    private void sendNotification(String method, JsonObject params) {
        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", method);
        if (params != null) notif.add("params", params);
        sendRaw(notif.toString());
    }

    private void sendRaw(String json) {
        if (!transport.isRunning()) return;
        try {
            transport.send(json);
        } catch (IOException e) {
            System.err.println("[MCP:" + serverName + "] Write error: " + e.getMessage());
        }
    }

    // ==================== 解析 ====================

    @SuppressWarnings("unchecked")
    private List<McpTool> parseTools(JsonObject response) {
        List<McpTool> tools = new ArrayList<>();
        if (!response.has("result")) return tools;
        JsonObject result = response.getAsJsonObject("result");
        if (!result.has("tools")) return tools;
        for (JsonElement e : result.getAsJsonArray("tools")) {
            JsonObject t = e.getAsJsonObject();
            McpTool tool = new McpTool();
            tool.setName(t.get("name").getAsString());
            tool.setDescription(t.has("description") ? t.get("description").getAsString() : "");
            if (t.has("inputSchema")) {
                tool.setInputSchema(GSON.fromJson(t.get("inputSchema"), Map.class));
            }
            tool.setServerName(serverName);
            tools.add(tool);
        }
        return tools;
    }

    private List<McpResource> parseResources(JsonObject response) {
        List<McpResource> resources = new ArrayList<>();
        if (!response.has("result")) return resources;
        JsonObject result = response.getAsJsonObject("result");
        if (!result.has("resources")) return resources;
        for (JsonElement e : result.getAsJsonArray("resources")) {
            JsonObject r = e.getAsJsonObject();
            McpResource res = new McpResource();
            res.setUri(r.get("uri").getAsString());
            res.setName(r.get("name").getAsString());
            if (r.has("description")) res.setDescription(r.get("description").getAsString());
            if (r.has("mimeType")) res.setMimeType(r.get("mimeType").getAsString());
            resources.add(res);
        }
        return resources;
    }

    private String parseResourceContent(JsonObject response) {
        if (!response.has("result")) return "";
        JsonObject result = response.getAsJsonObject("result");
        if (!result.has("contents")) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : result.getAsJsonArray("contents")) {
            JsonObject c = e.getAsJsonObject();
            if ("text".equals(c.get("type").getAsString()) && c.has("text")) {
                sb.append(c.get("text").getAsString());
            }
        }
        return sb.toString();
    }

    private McpToolResult parseToolResult(JsonObject response) {
        McpToolResult r = new McpToolResult();
        r.setRawJson(response.toString());
        if (!response.has("result")) {
            r.setError(true);
            r.setContent(List.of(McpContent.text("No result")));
            return r;
        }
        JsonObject result = response.getAsJsonObject("result");
        r.setError(result.has("isError") && result.get("isError").getAsBoolean());
        if (result.has("content")) {
            List<McpContent> contents = new ArrayList<>();
            for (JsonElement e : result.getAsJsonArray("content")) {
                JsonObject c = e.getAsJsonObject();
                McpContent content = new McpContent();
                content.setType(c.get("type").getAsString());
                if (c.has("text")) content.setText(c.get("text").getAsString());
                if (c.has("data")) content.setData(c.get("data").getAsString());
                if (c.has("mimeType")) content.setMimeType(c.get("mimeType").getAsString());
                contents.add(content);
            }
            r.setContent(contents);
        } else {
            r.setContent(List.of(McpContent.text("")));
        }
        return r;
    }

    @Override
    public String toString() {
        return "McpClient{" + serverName + "}";
    }
}
