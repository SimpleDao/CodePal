package com.codepal.mcp;

import com.codepal.mcp.client.McpClientManager;
import com.codepal.mcp.config.McpServerConfig;
import com.codepal.mcp.model.McpTool;
import com.codepal.settings.CPSettings;

import java.util.*;

/**
 * MCP 统一服务。
 * <p>JSON 编辑器直接读写 CPSettings，此服务只负责运行时管理。
 */
public class McpService {

    // ==================== 运行时管理 ====================

    /** 启动所有配置的 MCP Server */
    public static void startAllEnabled() {
        McpClientManager mgr = McpClientManager.getInstance();
        List<McpServerConfig> configs = getConfigs();
        if (configs.isEmpty()) return;

        for (McpServerConfig cfg : configs) {
            mgr.registerServer(cfg);
        }
        new Thread(mgr::startAll, "MCP-Init").start();
    }

    /** 停止所有 Server */
    public static void shutdown() {
        McpClientManager.getInstance().stopAll();
    }

    // ==================== 工具集成 ====================

    public static List<Map<String, Object>> getToolDefinitions() {
        return McpClientManager.getInstance().getAllToolsAsFunctions();
    }

    public static List<McpTool> getAllTools() {
        return McpClientManager.getInstance().getAllTools();
    }

    public static boolean isMcpTool(String toolName) {
        return McpClientManager.getInstance().isMcpTool(toolName);
    }

    public static String executeTool(String toolName, Map<String, Object> arguments) {
        return McpClientManager.getInstance().callTool(toolName, arguments);
    }

    // ==================== 配置读取 ====================

    public static List<McpServerConfig> getConfigs() {
        return CPSettings.getInstance().getMcpServers();
    }
}
