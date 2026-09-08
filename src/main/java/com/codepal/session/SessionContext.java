package com.codepal.session;

import com.codepal.memory.ConversationManager;
import com.codepal.ui.ChatWebView;
import com.codepal.ui.StreamRenderController;

import java.util.HashMap;
import java.util.Map;

/**
 * 每个标签会话的独立上下文。
 * <p>
 * 封装一个标签所需的所有可变状态，使多个标签可以同时流式聊天、互不干扰。
 * <ul>
 *   <li>{@link ChatWebView} — 独立的 JBCefBrowser 实例，DOM 互不污染</li>
 *   <li>{@link ConversationManager} — 独立的消息历史，切换标签不清空</li>
 *   <li>{@link StreamRenderController} — 独立的流式渲染状态，支持并发流式</li>
 *   <li>token 统计 — 每个标签独立累计</li>
 *   <li>流式写入可视化状态 — 每个标签独立</li>
 *   <li>Plan 模式文件状态 — 每个标签独立</li>
 * </ul>
 *
 * @author CodePal Tab Isolation
 */
public class SessionContext {

    private String sessionId;  // 非 final：临时会话落库后需要更新为真实 sessionId
    private final ChatWebView chatWebView;
    private final ConversationManager conversationManager;
    private final StreamRenderController streamRenderController;

    // ── token 统计（每标签独立）──
    private int sessionPromptTokens = 0;
    private int sessionCompletionTokens = 0;
    private int sessionCacheHitTokens = 0;
    private int sessionCacheMissTokens = 0;
    private long lastPromptTokens = 0;
    private long lastCompletionTokens = 0;
    private long lastCacheHitTokens = 0;
    private long lastCacheMissTokens = 0;

    // ── 流式写入可视化状态（每标签独立）──
    private boolean writeStreamCardActive = false;
    private int streamWriteCardIndex = -1;
    private final Map<Integer, StringBuilder> streamWriteRawArgsByIndex = new HashMap<>();
    private int streamWriteShownLen = 0;
    private String streamWriteTitle = "";
    private int streamWriteRemovedLines = 0;
    private boolean streamWriteIsEdit = false;
    private String streamWriteOriginal = "";

    // ── Plan 模式文件状态（每标签独立）──
    private final java.util.LinkedHashMap<String, Object> planFileStates = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> planFilesWithCards = new java.util.HashSet<>();
    private boolean planCollecting = false;
    private boolean todoCompletionSummaryAppended = false;

    // ── 压缩/中断状态（每标签独立）──
    private boolean compressionHintShown = false;
    private boolean taskWasInterrupted = false;
    private String pendingLoadingMsgKey = "ai_loading";
    private boolean isCompressing = false;

    // ── 输入草稿（每标签独立）──
    private String inputDraft = "";

    public SessionContext(String sessionId, ChatWebView chatWebView,
                          ConversationManager conversationManager,
                          StreamRenderController streamRenderController) {
        this.sessionId = sessionId;
        this.chatWebView = chatWebView;
        this.conversationManager = conversationManager;
        this.streamRenderController = streamRenderController;
    }

    // ── Getters ──

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public ChatWebView getChatWebView() { return chatWebView; }
    public ConversationManager getConversationManager() { return conversationManager; }
    public StreamRenderController getStreamRenderController() { return streamRenderController; }

    public int getSessionPromptTokens() { return sessionPromptTokens; }
    public void setSessionPromptTokens(int v) { this.sessionPromptTokens = v; }
    public void addSessionPromptTokens(int v) { this.sessionPromptTokens += v; }

    public int getSessionCompletionTokens() { return sessionCompletionTokens; }
    public void setSessionCompletionTokens(int v) { this.sessionCompletionTokens = v; }
    public void addSessionCompletionTokens(int v) { this.sessionCompletionTokens += v; }

    public int getSessionCacheHitTokens() { return sessionCacheHitTokens; }
    public void setSessionCacheHitTokens(int v) { this.sessionCacheHitTokens = v; }
    public void addSessionCacheHitTokens(int v) { this.sessionCacheHitTokens += v; }

    public int getSessionCacheMissTokens() { return sessionCacheMissTokens; }
    public void setSessionCacheMissTokens(int v) { this.sessionCacheMissTokens = v; }
    public void addSessionCacheMissTokens(int v) { this.sessionCacheMissTokens += v; }

    public long getLastPromptTokens() { return lastPromptTokens; }
    public void setLastPromptTokens(long v) { this.lastPromptTokens = v; }

    public long getLastCompletionTokens() { return lastCompletionTokens; }
    public void setLastCompletionTokens(long v) { this.lastCompletionTokens = v; }

    public long getLastCacheHitTokens() { return lastCacheHitTokens; }
    public void setLastCacheHitTokens(long v) { this.lastCacheHitTokens = v; }

    public long getLastCacheMissTokens() { return lastCacheMissTokens; }
    public void setLastCacheMissTokens(long v) { this.lastCacheMissTokens = v; }

    // ── 流式写入状态 getters/setters ──

    public boolean isWriteStreamCardActive() { return writeStreamCardActive; }
    public void setWriteStreamCardActive(boolean v) { this.writeStreamCardActive = v; }

    public int getStreamWriteCardIndex() { return streamWriteCardIndex; }
    public void setStreamWriteCardIndex(int v) { this.streamWriteCardIndex = v; }

    public Map<Integer, StringBuilder> getStreamWriteRawArgsByIndex() { return streamWriteRawArgsByIndex; }

    public int getStreamWriteShownLen() { return streamWriteShownLen; }
    public void setStreamWriteShownLen(int v) { this.streamWriteShownLen = v; }

    public String getStreamWriteTitle() { return streamWriteTitle; }
    public void setStreamWriteTitle(String v) { this.streamWriteTitle = v; }

    public int getStreamWriteRemovedLines() { return streamWriteRemovedLines; }
    public void setStreamWriteRemovedLines(int v) { this.streamWriteRemovedLines = v; }

    public boolean isStreamWriteIsEdit() { return streamWriteIsEdit; }
    public void setStreamWriteIsEdit(boolean v) { this.streamWriteIsEdit = v; }

    public String getStreamWriteOriginal() { return streamWriteOriginal; }
    public void setStreamWriteOriginal(String v) { this.streamWriteOriginal = v; }

    // ── Plan 状态 getters ──

    @SuppressWarnings("unchecked")
    public java.util.LinkedHashMap<String, Object> getPlanFileStates() { return planFileStates; }
    public java.util.Set<String> getPlanFilesWithCards() { return planFilesWithCards; }
    public boolean isPlanCollecting() { return planCollecting; }
    public void setPlanCollecting(boolean v) { this.planCollecting = v; }
    public boolean isTodoCompletionSummaryAppended() { return todoCompletionSummaryAppended; }
    public void setTodoCompletionSummaryAppended(boolean v) { this.todoCompletionSummaryAppended = v; }

    // ── 压缩/中断状态 ──

    public boolean isCompressionHintShown() { return compressionHintShown; }
    public void setCompressionHintShown(boolean v) { this.compressionHintShown = v; }

    public boolean isTaskWasInterrupted() { return taskWasInterrupted; }
    public void setTaskWasInterrupted(boolean v) { this.taskWasInterrupted = v; }

    public String getPendingLoadingMsgKey() { return pendingLoadingMsgKey; }
    public void setPendingLoadingMsgKey(String v) { this.pendingLoadingMsgKey = v; }

    public boolean isCompressing() { return isCompressing; }
    public void setCompressing(boolean v) { this.isCompressing = v; }

    // ── 输入草稿 ──

    public String getInputDraft() { return inputDraft; }
    public void setInputDraft(String v) { this.inputDraft = v; }

    /**
     * 重置流式写入可视化状态（每轮请求开始时调用）
     */
    public void resetStreamWriteState() {
        writeStreamCardActive = false;
        streamWriteCardIndex = -1;
        streamWriteRawArgsByIndex.clear();
        streamWriteShownLen = 0;
        streamWriteTitle = "";
        streamWriteRemovedLines = 0;
        streamWriteIsEdit = false;
        streamWriteOriginal = "";
    }

    /**
     * 释放资源（关闭标签时调用）
     */
    public void dispose() {
        if (streamRenderController != null) {
            streamRenderController.stopWatchdog();
        }
        if (chatWebView != null) {
            chatWebView.dispose();
        }
    }
}
