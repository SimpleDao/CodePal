package com.codepal.model;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 聊天请求模型
 * 支持 tools / tool_choice 字段用于 Function Calling
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ChatRequest {
    private String model;
    private List<ChatMessage> messages;
    private boolean stream = true;
    private Integer max_tokens;
    private Double temperature;
    /** 工具定义列表，传递给 LM 模型让其自主决定是否调用 */
    private List<ToolDefinition> tools;
    /** 强制模型调用工具的策略，默认 auto */
    private String tool_choice = "auto";

    private Map<String,Object> thinking;
    private String reasoning_effort;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    public Integer getMax_tokens() { return max_tokens; }
    public void setMax_tokens(Integer max_tokens) { this.max_tokens = max_tokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public List<ToolDefinition> getTools() { return tools; }
    public void setTools(List<ToolDefinition> tools) { this.tools = tools; }
    public String getTool_choice() { return tool_choice; }
    public void setTool_choice(String tool_choice) { this.tool_choice = tool_choice; }

    public Map<String, Object> getThinking() {
        return thinking;
    }

    public void setThinking(boolean flag) {
        if(flag){
            this.thinking = Map.of("type", "enabled");
        } else {
            this.thinking = Map.of("type", "disabled");
        }
    }

    public String getReasoning_effort() { return reasoning_effort; }
    public void setReasoning_effort(String reasoning_effort) { this.reasoning_effort = reasoning_effort; }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部类：工具定义
    // ─────────────────────────────────────────────────────────────────────────
    public static class ToolDefinition {
        private String type = "function";
        private FunctionDef function;

        public ToolDefinition(FunctionDef function) {
            this.function = function;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public FunctionDef getFunction() { return function; }
        public void setFunction(FunctionDef function) { this.function = function; }

        public static class FunctionDef {
            private String name;
            private String description;
            private Map<String, Object> parameters; // JSON Schema

            public FunctionDef(String name, String description, Map<String, Object> parameters) {
                this.name = name;
                this.description = description;
                this.parameters = parameters;
            }

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getDescription() { return description; }
            public void setDescription(String description) { this.description = description; }
            public Map<String, Object> getParameters() { return parameters; }
            public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
        }
    }
}
