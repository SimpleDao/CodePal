package com.codepal.agent;

import com.intellij.openapi.project.Project;
import com.codepal.cc.CCConfig;
import com.codepal.cc.acp.AcpConnection;
import com.codepal.cc.acp.model.StopReason;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ChatResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ACP (Claude Code) 后端实现
 *
 * <p>封装 Claude Code ACP 协议调用，实现 AgentBackend 接口。
 *
 * <p>注意：ACP 协议与标准 OpenAI 格式差异较大，需要做较多适配。
 * 当前版本为骨架实现，详细逻辑待逐步从 ChatPanel 迁移。
 *
 * @author CP Refactor
 */
public class AcpBackend implements AgentBackend {

    private final Project project;
    private AcpConnection acpConnection;
    private CompletableFuture<StopReason> acpPromptFuture;
    private boolean craftMode = false;

    public AcpBackend(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "ACP (Claude Code)";
    }

    @Override
    public boolean isAvailable() {
        return CCConfig.getInstance().isAcpAvailable();
    }

    @Override
    public CompletableFuture<Void> streamChat(
            List<ChatMessage> messages,
            List<ChatRequest.ToolDefinition> tools,
            StreamCallback callback
    ) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            ensureConnected();
            acpConnection.setCraftMode(craftMode);

            acpConnection.setNotificationHandler(msg -> {
                // TODO: 处理 ACP 通知，转换为 StreamCallback
            });

            acpConnection.setUsageHandler(usage -> {
                // TODO: 处理 usage 回调
            });

            // 获取最后一条用户消息
            String promptText = "";
            if (messages != null && !messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("user".equals(messages.get(i).getRole())) {
                        promptText = messages.get(i).getContent();
                        break;
                    }
                }
            }

            acpPromptFuture = acpConnection.sendPrompt(promptText);
            acpPromptFuture.thenAccept(stopReason -> {
                if (stopReason == StopReason.END_TURN) {
                    callback.onComplete();
                    future.complete(null);
                } else {
                    callback.onComplete();
                    future.complete(null);
                }
            }).exceptionally(ex -> {
                callback.onError(ex);
                future.completeExceptionally(ex);
                return null;
            });

        } catch (Exception e) {
            callback.onError(e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void cancelCurrent() {
        if (acpConnection != null) {
            acpConnection.cancelPrompt();
        }
        if (acpPromptFuture != null) {
            acpPromptFuture.cancel(true);
        }
    }

    @Override
    public void setCraftMode(boolean craftMode) {
        this.craftMode = craftMode;
        if (acpConnection != null) {
            acpConnection.setCraftMode(craftMode);
        }
    }

    @Override
    public boolean isCraftMode() {
        return craftMode;
    }

    private void ensureConnected() throws Exception {
        if (acpConnection != null && acpConnection.isRunning() && acpConnection.isInitialized()) {
            return;
        }
        if (acpConnection != null) {
            acpConnection.stop();
        }
        CCConfig config = CCConfig.getInstance();
        String cwd = project.getBasePath();
        if (cwd == null) cwd = System.getProperty("user.dir");
        config.setWorkingDirectory(cwd);

        acpConnection = new AcpConnection(config.buildAcpCommand());
        acpConnection.setCraftMode(craftMode);
        acpConnection.start();
        acpConnection.initialize();
        acpConnection.newSession(cwd);
    }

    public void stop() {
        if (acpConnection != null) {
            acpConnection.stop();
            acpConnection = null;
        }
    }

    public AcpConnection getConnection() {
        return acpConnection;
    }
}
