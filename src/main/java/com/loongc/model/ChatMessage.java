package com.loongc.model;

import java.util.List;

/**
 * 聊天消息模型
 * 支持 Function Calling：role 可为 "system" / "user" / "assistant" / "tool"
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ChatMessage {
    private String role;
    private String content;
    // DeepSeek reasoning 模型的思考过程（流式时出现在 delta 中，非流式时出现在 message 中）
    private String reasoning_content;
    // 用于 assistant 角色的工具调用请求
    private List<ToolCall> tool_calls;
    // 用于 tool 角色的消息
    private String tool_call_id;
    private String name;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    // 使用工具调用的 assistant 消息
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        ChatMessage msg = new ChatMessage();
        msg.role = "assistant";
        msg.content = null; // 有 tool_calls 时 content 可为 null
        msg.tool_calls = toolCalls;
        return msg;
    }

    // tool 角色的消息（工具执行结果）
    public static ChatMessage toolResult(String toolCallId,String toolName, String result) {
        ChatMessage msg = new ChatMessage();
        msg.role = "tool";
        msg.name = toolName;
        msg.tool_call_id = toolCallId;
        msg.content = result;
        return msg;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getReasoning_content() { return reasoning_content; }
    public void setReasoning_content(String reasoning_content) { this.reasoning_content = reasoning_content; }
    public List<ToolCall> getTool_calls() { return tool_calls; }
    public void setTool_calls(List<ToolCall> tool_calls) { this.tool_calls = tool_calls; }
    public String getTool_call_id() { return tool_call_id; }
    public void setTool_call_id(String tool_call_id) { this.tool_call_id = tool_call_id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", content='" + content + '\'' +
                ", reasoning_content='" + reasoning_content + '\'' +
                ", tool_calls=" + tool_calls +
                ", tool_call_id='" + tool_call_id + '\'' +
                ", name='" + name + '\'' +
                '}';
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部类：工具调用定义
    // ─────────────────────────────────────────────────────────────────────────
    public static class ToolCall {
        private String id;
        private String type = "function";
        private Function function;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Function getFunction() { return function; }
        public void setFunction(Function function) { this.function = function; }

        public static class Function {
            private String name;
            private String arguments; // JSON 字符串

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getArguments() { return arguments; }
            public void setArguments(String arguments) { this.arguments = arguments; }

            @Override
            public String toString() {
                return "Function{" +
                        "name='" + name + '\'' +
                        ", arguments='" + arguments + '\'' +
                        '}';
            }
        }

        @Override
        public String toString() {
            return "ToolCall{" +
                    "id='" + id + '\'' +
                    ", type='" + type + '\'' +
                    ", function=" + function +
                    '}';
        }
    }


}
