package com.codepal.mcp.client;

import com.codepal.mcp.config.McpServerConfig;
import com.codepal.mcp.model.McpTool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 管理器 — 管理多个 MCP Server 的连接。
 */
public class McpClientManager {

    private static final McpClientManager INSTANCE = new McpClientManager();

    public static McpClientManager getInstance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpClient> toolRouter = new ConcurrentHashMap<>();
    private volatile List<McpTool> cachedTools = Collections.emptyList();
    private volatile boolean started = false;

    private McpClientManager() {}

    // ==================== Server 管理 ====================

    public void registerServer(McpServerConfig config) {
        McpClient client = new McpClient(config.getName(), config);
        clients.put(config.getName(), client);
    }

    public void removeServer(String name) {
        McpClient client = clients.remove(name);
        if (client != null) {
            client.stop();
            toolRouter.entrySet().removeIf(e -> e.getValue() == client);
            rebuildCachedTools();
        }
    }

    public void startServer(String name) throws Exception {
        McpClient client = clients.get(name);
        if (client == null) throw new IllegalArgumentException("Server not found: " + name);
        client.start();
        List<McpTool> tools = client.listTools();
        for (McpTool tool : tools) toolRouter.put(tool.getName(), client);
        rebuildCachedTools();
    }

    public void stopServer(String name) {
        McpClient client = clients.get(name);
        if (client == null) return;
        client.stop();
        toolRouter.entrySet().removeIf(e -> e.getValue() == client);
        rebuildCachedTools();
    }

    private void rebuildCachedTools() {
        List<McpTool> all = new ArrayList<>();
        for (McpClient c : clients.values()) {
            if (c.isRunning()) {
                try { all.addAll(c.listTools()); } catch (Exception ignored) {}
            }
        }
        cachedTools = Collections.unmodifiableList(all);
    }

    public void startAll() {
        started = true;
        List<McpTool> allTools = new ArrayList<>();
        System.out.println("[MCP] startAll: " + clients.size() + " servers registered");
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().start();
                List<McpTool> tools = entry.getValue().listTools();
                for (McpTool tool : tools) toolRouter.put(tool.getName(), entry.getValue());
                allTools.addAll(tools);
                System.out.println("[MCP] Server '" + entry.getKey() + "' started, "
                        + tools.size() + " tools: "
                        + tools.stream().map(McpTool::getName).toList());
            } catch (Exception e) {
                String advice = diagnoseCommand(entry.getValue(), e);
                System.err.println("[MCP] Failed to start '" + entry.getKey() + "': " + e.getMessage());
                if (advice != null) System.err.println("[MCP]   " + advice);
            }
        }
        cachedTools = Collections.unmodifiableList(allTools);
        System.out.println("[MCP] startAll done: " + allTools.size() + " tools, "
                + toolRouter.size() + " routes");
    }

    public void stopAll() {
        started = false;
        for (McpClient client : clients.values()) client.stop();
        toolRouter.clear();
        cachedTools = Collections.emptyList();
    }

    public void restartAll() {
        stopAll();
        startAll();
    }

    // ==================== 工具查询 ====================

    public List<McpTool> getAllTools() { return cachedTools; }

    public List<Map<String, Object>> getAllToolsAsFunctions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpTool tool : cachedTools) list.add(tool.toFunctionDefinition());
        return list;
    }

    public boolean isMcpTool(String toolName) { return toolRouter.containsKey(toolName); }

    // ==================== 工具调用 ====================

    public String callTool(String toolName, Map<String, Object> arguments) {
        McpClient client = toolRouter.get(toolName);
        if (client == null) return "Error: MCP tool not found: " + toolName;
        try {
            var result = client.callTool(toolName, arguments);
            return result.extractText();
        } catch (Exception e) {
            return "Error calling MCP tool '" + toolName + "': " + e.getMessage();
        }
    }

    // ==================== 诊断 ====================

    private String diagnoseCommand(McpClient client, Exception e) {
        String cmd = null;
        try {
            cmd = client.getServerName();
        } catch (Exception ignored) {}
        String msg = e.getMessage() != null ? e.getMessage() : "";
        boolean isNotFound = msg.contains("Cannot run program") || msg.contains("CreateProcess error=2");
        if (isNotFound) return "命令未找到，请确保 Node.js 已安装且 npx 在 PATH 中";
        return null;
    }

    // ==================== 状态 ====================

    public boolean isStarted() { return started; }
    public int getServerCount() { return clients.size(); }
    public Set<String> getServerNames() { return Collections.unmodifiableSet(clients.keySet()); }
    public McpClient getClient(String name) { return clients.get(name); }

    @Override
    public String toString() {
        return "McpClientManager{servers=" + clients.keySet() + ", tools=" + cachedTools.size() + "}";
    }
}
