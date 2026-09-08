package com.codepal.mcp.transport;

import java.io.IOException;

/**
 * MCP 传输层接口 — 抽象进程间通信方式。
 */
public interface McpTransport {

    void start() throws IOException;
    void stop();
    boolean isRunning();
    void send(String json) throws IOException;
    String readLine() throws IOException;
    String getType();
}
