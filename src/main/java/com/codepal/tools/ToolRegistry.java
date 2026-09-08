package com.codepal.tools;

import com.intellij.openapi.project.Project;
import com.codepal.agent.subagent.SubAgentManager;
import com.codepal.model.ChatRequest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心 —— 统一管理所有工具
 *
 * <p>借鉴 AutoDev 的 ToolRegistry 设计，负责：
 * <ul>
 *   <li>内置工具的注册和发现</li>
 *   <li>MCP 工具的动态注册/注销</li>
 *   <li>SubAgent 工具的自动注册</li>
 *   <li>提供工具查询、搜索、分类接口</li>
 *   <li>与 ToolIndex 集成，支持 BM25 检索</li>
 * </ul>
 *
 * @author CP Multi-Agent
 */
public class ToolRegistry {

    private final Project project;
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ToolIndex toolIndex = new ToolIndex();
    private boolean initialized = false;
    private SubAgentManager subAgentManager;

    public ToolRegistry(Project project) {
        this.project = project;
    }

    public synchronized void initialize() {
        if (initialized) return;

        registerBuiltinTools();

        initialized = true;
    }

    public void setSubAgentManager(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    private void registerBuiltinTools() {
        List<ChatRequest.ToolDefinition> defs = ToolDefinitions.getAllTools();
        Map<String, Set<String>> keywordsMap = ToolDefinitions.getToolKeywordsMap();

        for (ChatRequest.ToolDefinition def : defs) {
            String name = def.getFunction().getName();
            Tool.RiskLevel level = getRiskLevel(name);
            Set<String> keywords = keywordsMap.getOrDefault(name, Set.of());

            Tool tool = new BuiltinTool(name, def.getFunction().getDescription(),
                    keywords, level, def, project);
            registerTool(tool);
        }
    }

    private Tool.RiskLevel getRiskLevel(String toolName) {
        return switch (toolName) {
                         case "search_agent", "locate_code_by_symbol", "view_file_outline",
                              "read_file_range", "list_files", "search_tool", "code_review",
                 "validate_code", "todo" -> Tool.RiskLevel.READ_ONLY;
            case "edit_file", "write_file", "create_new_file", "create_directory",
                 "delete_file" -> Tool.RiskLevel.WRITE;
            case "run_command" -> Tool.RiskLevel.WRITE;
            case "view_image" -> Tool.RiskLevel.READ_ONLY;
            default -> Tool.RiskLevel.READ_ONLY;
        };
    }

    public void registerTool(Tool tool) {
        if (tool == null || !tool.isAvailable()) return;
        tools.put(tool.getName(), tool);
        toolIndex.register(tool.getName(), tool.getKeywords(), tool.getDescription());
    }

    public void unregisterTool(String name) {
        tools.remove(name);
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public List<ChatRequest.ToolDefinition> getAllToolDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .collect(Collectors.toList());
    }

    public List<Tool> getToolsByRiskLevel(Tool.RiskLevel level) {
        return tools.values().stream()
                .filter(t -> t.getRiskLevel() == level)
                .collect(Collectors.toList());
    }

    public List<String> searchTools(String query, int maxResults) {
        return toolIndex.search(query, maxResults);
    }

    public List<Tool> searchToolObjects(String query, int maxResults) {
        List<String> names = toolIndex.search(query, maxResults);
        List<Tool> result = new ArrayList<>();
        for (String name : names) {
            Tool tool = tools.get(name);
            if (tool != null) result.add(tool);
        }
        return result;
    }

    public String getToolDescription(String name) {
        return toolIndex.getDescription(name);
    }

    public int size() {
        return tools.size();
    }

    public ToolIndex getToolIndex() {
        return toolIndex;
    }

    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalTools", tools.size());

        Map<Tool.RiskLevel, Long> byRisk = tools.values().stream()
                .collect(Collectors.groupingBy(Tool::getRiskLevel, Collectors.counting()));
        status.put("readOnlyTools", byRisk.getOrDefault(Tool.RiskLevel.READ_ONLY, 0L));
        status.put("writeTools", byRisk.getOrDefault(Tool.RiskLevel.WRITE, 0L));
        status.put("dangerousTools", byRisk.getOrDefault(Tool.RiskLevel.DANGEROUS, 0L));

        List<String> toolNames = new ArrayList<>(tools.keySet());
        Collections.sort(toolNames);
        status.put("toolNames", toolNames);

        return status;
    }

    public void shutdown() {
        tools.clear();
        initialized = false;
    }

    /**
     * 内置工具包装类 —— 委托给 ToolExecutor 执行
     */
    private static class BuiltinTool implements Tool {
        private final String name;
        private final String description;
        private final Set<String> keywords;
        private final RiskLevel riskLevel;
        private final ChatRequest.ToolDefinition definition;
        private final Project project;

        BuiltinTool(String name, String description, Set<String> keywords,
                    RiskLevel riskLevel, ChatRequest.ToolDefinition definition, Project project) {
            this.name = name;
            this.description = description;
            this.keywords = keywords;
            this.riskLevel = riskLevel;
            this.definition = definition;
            this.project = project;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Set<String> getKeywords() {
            return keywords;
        }

        @Override
        public RiskLevel getRiskLevel() {
            return riskLevel;
        }

        @Override
        public ChatRequest.ToolDefinition getDefinition() {
            return definition;
        }

        @Override
        public String execute(com.google.gson.JsonObject params, Project project) {
            return execute(params, project, false);
        }

        @Override
        public String execute(com.google.gson.JsonObject params, Project project, boolean skipConfirmation) {
            return execute(params, project, skipConfirmation, null);
        }

        @Override
        public String execute(com.google.gson.JsonObject params, Project project,
                              boolean skipConfirmation, ToolConfirmManager manager) {
            com.codepal.model.ChatMessage.ToolCall tc =
                    new com.codepal.model.ChatMessage.ToolCall();
            com.codepal.model.ChatMessage.ToolCall.Function func =
                    new com.codepal.model.ChatMessage.ToolCall.Function();
            func.setName(name);
            func.setArguments(params.toString());
            tc.setFunction(func);
            return ToolExecutor.execute(tc, project, skipConfirmation, manager);
        }
    }
}
