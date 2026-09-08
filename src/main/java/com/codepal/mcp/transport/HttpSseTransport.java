package com.codepal.mcp.transport;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP SSE 传输 — 通过 Streamable HTTP + SSE 与远程 MCP Server 通信。
 *
 * <p>MCP 规范支持两种 SSE 模式：
 * <ul>
 *   <li>SSE 模式：GET /sse 建立 SSE 连接接收消息，POST /message 发送消息</li>
 *   <li>Streamable HTTP：POST /mcp 发送 JSON-RPC，服务器可能返回 SSE 流</li>
 * </ul>
 */
public class HttpSseTransport implements McpTransport {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String endpoint; // /mcp 或 /message

    public HttpSseTransport(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        this.endpoint = this.baseUrl.endsWith("/sse") ? this.baseUrl : this.baseUrl + "/sse";
    }

    @Override
    public void start() throws IOException {
        running.set(true);
        executor.submit(this::sseLoop);
    }

    private void sseLoop() {
        while (running.get()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .timeout(java.time.Duration.ofSeconds(300))
                        .build();

                HttpResponse<InputStream> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() != 200) {
                    System.err.println("[MCP:http] SSE connect failed: HTTP " + resp.statusCode());
                    Thread.sleep(5000);
                    continue;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder data = new StringBuilder();
                    String eventType = null;

                    while (running.get() && (line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            String d = line.substring(5).trim();
                            if (data.length() > 0) data.append("\n");
                            data.append(d);
                        } else if (line.startsWith("event:")) {
                            eventType = line.substring(6).trim();
                        } else if (line.isEmpty() && data.length() > 0) {
                            String eventData = data.toString();
                            if ("message".equals(eventType) || eventType == null) {
                                messageQueue.offer(eventData);
                            }
                            data.setLength(0);
                            eventType = null;
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("[MCP:http] SSE error: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    @Override
    public void send(String json) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/message"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isEmpty()) {
                // 有些服务器直接在 POST 响应中返回结果
                messageQueue.offer(resp.body());
            }
        } catch (InterruptedException e) {
            throw new IOException("HTTP send interrupted", e);
        } catch (Exception e) {
            throw new IOException("HTTP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String readLine() throws IOException {
        try {
            String msg = messageQueue.poll(5, TimeUnit.SECONDS);
            return msg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String getType() {
        return "http-sse";
    }
}
