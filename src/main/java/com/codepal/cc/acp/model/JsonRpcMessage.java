package com.codepal.cc.acp.model;

/**
 * JSON-RPC 2.0 消息基类。
 *
 * <p>ACP 协议基于 JSON-RPC 2.0，所有消息都包含 "jsonrpc": "2.0"。
 */
public class JsonRpcMessage {

    protected String jsonrpc;

    public JsonRpcMessage() {
        this.jsonrpc = "2.0";
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    /**
     * 从原始 JSON 行判断消息类型。
     */
    public static MessageType classify(String jsonLine) {
        if (jsonLine == null || jsonLine.trim().isEmpty()) {
            return MessageType.UNKNOWN;
        }
        boolean hasId = jsonLine.contains("\"id\"");
        boolean hasMethod = jsonLine.contains("\"method\"");

        if (hasMethod && hasId) {
            return MessageType.REQUEST;
        } else if (hasMethod) {
            return MessageType.NOTIFICATION;
        } else if (hasId) {
            return MessageType.RESPONSE;
        }
        return MessageType.UNKNOWN;
    }

    public enum MessageType {
        REQUEST,
        RESPONSE,
        NOTIFICATION,
        UNKNOWN
    }
}
