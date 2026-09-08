package com.codepal.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息模型（用于 ChatWebView HTML 渲染）。
 */
final class ChatMessageVO {
    enum Type { USER, AI, TOOL }

    final Type type;
    final String html;          // 消息正文 HTML（用户消息为转义后的纯文本）
    final String rawMd;         // 原始 Markdown（用于右键"复制原始内容"）
    final String reasoning;     // 思考过程（仅 AI 消息）
    final String toolName;      // 工具名称（仅 TOOL 消息）
    final boolean streaming;    // 是否正在流式输出中

    private ChatMessageVO(Type type, String html, String rawMd, String reasoning, 
                          String toolName, boolean streaming) {
        this.type = type;
        this.html = html != null ? html : "";
        this.rawMd = rawMd != null ? rawMd : "";
        this.reasoning = reasoning != null ? reasoning : "";
        this.toolName = toolName != null ? toolName : "";
        this.streaming = streaming;
    }

    static ChatMessageVO user(String text) {
        // 纯文本转义用于 HTML innerHTML
        String safe = text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
        return new ChatMessageVO(Type.USER, safe, text, "", "", false);
    }

    static ChatMessageVO aiStreaming() {
        return new ChatMessageVO(Type.AI, "", "", "", "", true);
    }

    static ChatMessageVO aiComplete(String bodyHtml, String rawMd, String reasoning) {
        return new ChatMessageVO(Type.AI, bodyHtml, rawMd, reasoning, "", false);
    }

    static ChatMessageVO tool(String toolName) {
        return new ChatMessageVO(Type.TOOL, "", "", "", toolName, false);
    }
}
