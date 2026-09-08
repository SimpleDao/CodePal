package com.codepal.cc.acp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.codepal.cc.acp.model.JsonRpcMessage;
import com.codepal.cc.acp.model.StopReason;

import javax.swing.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ACP JSON-RPC 连接 — 管理 Claude Code ACP Server 子进程，
 * 通过 stdin/stdout 发送/接收 NDJSON 格式的 JSON-RPC 消息。
 */
public class AcpConnection {

    private static final Gson GSON = new Gson();

    private final String[] commandArgs;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private Thread readerThread;

    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final ConcurrentMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    private Consumer<JsonObject> notificationHandler;
    private Consumer<JsonObject> agentRequestHandler;
    private Consumer<JsonObject> usageHandler;

    private volatile boolean running = false;
    private volatile boolean initialized = false;
    private boolean craftMode = false;  // Plan=false 只读 / Craft=true 自动接受修改
    private String sessionId;
    /** 当前会话实际已应用的模式，用于避免每次 prompt 重复下发 set_mode；null=会话尚未应用过 */
    private Boolean sessionAppliedCraftMode;

    public AcpConnection(String[] commandArgs) {
        this.commandArgs = commandArgs;
    }

    // ==================== 生命周期 ====================

    /** 启动 ACP Server 子进程（必须在后台线程调用） */
    public void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(commandArgs);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        running = true;

        Thread stderrReader = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[CC-ACP] " + line);
                }
            } catch (IOException ignored) {}
        }, "CC-ACP-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        readerThread = new Thread(this::readLoop, "CC-ACP-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void stop() {
        running = false;
        initialized = false;

        for (CompletableFuture<JsonObject> f : pendingRequests.values()) {
            f.completeExceptionally(new IOException("Connection closed"));
        }
        pendingRequests.clear();

        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
        try { if (stdout != null) stdout.close(); } catch (IOException ignored) {}

        // 会话已关闭，清理会话态，下次 newSession 会重新按当前模式创建
        sessionId = null;
        sessionAppliedCraftMode = null;
    }

    public boolean isRunning() { return running && process != null && process.isAlive(); }
    public boolean isInitialized() { return initialized; }
    public String getSessionId() { return sessionId; }

    // ==================== stdout 读取循环 ====================

    private void readLoop() {
        try {
            String line;
            while (running && (line = stdout.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                handleMessage(line);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[CC-ACP] Read error: " + e.getMessage());
            }
        } finally {
            running = false;
            initialized = false;
        }
    }

    private void handleMessage(String jsonLine) {
        try {
            JsonObject msg = JsonParser.parseString(jsonLine).getAsJsonObject();
            JsonRpcMessage.MessageType type = JsonRpcMessage.classify(jsonLine);

            // 打印所有收到的消息（调试用）
            String method = msg.has("method") ? msg.get("method").getAsString() : "";
            System.out.println("[CC-ACP] ← RECV " + type + " | " + method + " | " + jsonLine);

            switch (type) {
                case RESPONSE -> handleResponse(msg);
                case NOTIFICATION -> handleNotification(msg);
                case REQUEST -> handleAgentRequest(msg);
                default -> System.err.println("[CC-ACP] Unknown message: " + jsonLine);
            }
        } catch (Exception e) {
            System.err.println("[CC-ACP] Parse error: " + e.getMessage());
        }
    }

    private void handleResponse(JsonObject msg) {
        if (!msg.has("id")) return;
        int id = msg.get("id").getAsInt();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);
        if (future != null) {
            if (msg.has("error")) {
                String errMsg = msg.getAsJsonObject("error").get("message").getAsString();
                future.completeExceptionally(new RuntimeException("ACP Error: " + errMsg));
            } else {
                future.complete(msg);
            }
        }
    }

    private void handleNotification(JsonObject msg) {
        if (notificationHandler != null) {
            notificationHandler.accept(msg);
        }
    }

    private void handleAgentRequest(JsonObject msg) {
        if (agentRequestHandler != null) {
            agentRequestHandler.accept(msg);
        } else {
            String method = msg.get("method").getAsString();
            if ("session/request_permission".equals(method)) {
                int id = msg.get("id").getAsInt();
                JsonObject result = new JsonObject();
                result.addProperty("outcome", "approved");
                sendResponse(id, result);
            }
        }
    }

    // ==================== 发送 ====================

    public JsonObject sendRequest(String method, JsonObject params) throws Exception {
        return sendRequest(method, params, 300);
    }

    public JsonObject sendRequest(String method, JsonObject params, int timeoutSeconds) throws Exception {
        int id = requestIdCounter.getAndIncrement();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        sendRaw(request.toString());

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("ACP request timeout: " + method);
        }
    }

    public void sendNotification(String method, JsonObject params) {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        if (params != null) {
            notification.add("params", params);
        }
        sendRaw(notification.toString());
    }

    public void sendResponse(int requestId, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", requestId);
        response.add("result", result);
        System.out.println("[CC-ACP] sendResponse id=" + requestId + ": " + response);
        sendRaw(response.toString());
    }

    private synchronized void sendRaw(String json) {
        System.out.println("[CC-ACP] SEND → " + json);
        if (!running || stdin == null) {
            System.err.println("[CC-ACP] sendRaw dropped (running=" + running
                    + ", stdin=" + (stdin != null) + "): " + json);
            return;
        }
        try {
            stdin.write(json);
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            System.err.println("[CC-ACP] Write error: " + e.getMessage());
            running = false;
        }
    }

    // ==================== 高级协议方法 ====================

    public JsonObject initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);

        JsonObject clientCapabilities = new JsonObject();
        // prompt 能力
        JsonObject promptCaps = new JsonObject();
        promptCaps.addProperty("image", true);
        promptCaps.addProperty("embeddedContext", true);
        clientCapabilities.add("promptCapabilities", promptCaps);
        // MCP 能力
        clientCapabilities.add("mcpCapabilities", new JsonObject());
        // 权限/审批能力 — 声明客户端将自动处理权限请求
        JsonObject approvalCaps = new JsonObject();
        approvalCaps.addProperty("autoApprove", false);
        clientCapabilities.add("approvalCapabilities", approvalCaps);
        params.add("clientCapabilities", clientCapabilities);
        // Plan 模式：只读规划 / Craft 模式：可编辑但需确认
        params.addProperty("clientMode", craftMode ? "acceptEdits" : "plan");

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "CP-CC");
        clientInfo.addProperty("title", "CP Claude Code Panel");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);
        initialized = true;
        return result;
    }

    public String newSession(String workingDir) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDir);
        params.add("mcpServers", new com.google.gson.JsonArray());
        // permissionMode: Craft 自动接受修改(acceptEdits) / Plan 只读规划(plan)
        params.addProperty("permissionMode", craftMode ? "acceptEdits" : "plan");

        JsonObject result = sendRequest("session/new", params);
        if (result.has("result")) {
            JsonObject r = result.getAsJsonObject("result");
            if (r.has("sessionId")) {
                sessionId = r.get("sessionId").getAsString();
                // 会话已按当前 craftMode 创建，记录已应用模式
                sessionAppliedCraftMode = craftMode;
                return sessionId;
            }
        }
        throw new RuntimeException("session/new response missing sessionId");
    }

    /**
     * session/prompt — 发送用户消息（非阻塞，结果通过 future 返回）。
     * 响应中的 usage 字段通过 usageHandler 回调上报。
     */
    public CompletableFuture<StopReason> sendPrompt(String text) {
        int id = requestIdCounter.getAndIncrement();

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", "session/prompt");

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        com.google.gson.JsonArray prompt = new com.google.gson.JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        prompt.add(textBlock);
        params.add("prompt", prompt);
        request.add("params", params);

        CompletableFuture<StopReason> resultFuture = new CompletableFuture<>();
        CompletableFuture<JsonObject> rawFuture = new CompletableFuture<>();
        pendingRequests.put(id, rawFuture);

        rawFuture.thenAccept(response -> {
            // 解析 usage 字段并上报
            if (response.has("result") && usageHandler != null) {
                JsonObject r = response.getAsJsonObject("result");
                if (r.has("usage")) {
                    usageHandler.accept(r.getAsJsonObject("usage"));
                }
            }
            if (response.has("result")) {
                JsonObject r = response.getAsJsonObject("result");
                String sr = r.has("stopReason") ? r.get("stopReason").getAsString() : "end_turn";
                resultFuture.complete(StopReason.fromString(sr));
            } else {
                resultFuture.complete(StopReason.END_TURN);
            }
        }).exceptionally(ex -> {
            resultFuture.completeExceptionally(ex);
            return null;
        });

        sendRaw(request.toString());
        return resultFuture;
    }

    public void cancelPrompt() {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        sendNotification("session/cancel", params);
    }

    // ==================== 回调设置 ====================

    public void setNotificationHandler(Consumer<JsonObject> handler) {
        this.notificationHandler = handler;
    }

    public void setAgentRequestHandler(Consumer<JsonObject> handler) {
        this.agentRequestHandler = handler;
    }

    public void setCraftMode(boolean craftMode) {
        this.craftMode = craftMode;
        // 会话已建立且模式发生变化时，实时切换运行中会话的权限模式
        //（中途切 Plan/Craft 立即生效，不必重建会话；sessionId 为 null 时跳过）。
        if (sessionId != null && running && initialized
                && (sessionAppliedCraftMode == null || sessionAppliedCraftMode != craftMode)) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("sessionId", sessionId);
                params.addProperty("modeId", craftMode ? "acceptEdits" : "plan");
                sendRequest("session/set_mode", params, 10);
                sessionAppliedCraftMode = craftMode;
            } catch (Exception e) {
                System.err.println("[CC-ACP] session/set_mode failed: " + e.getMessage());
            }
        }
    }

    public void setUsageHandler(Consumer<JsonObject> handler) {
        this.usageHandler = handler;
    }
}
