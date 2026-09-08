package com.codepal.ui;

import com.codepal.utils.MarkdownUtil;

import java.awt.Color;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式渲染控制器
 *
 * <p>职责：
 * <ul>
 *   <li>管理流式输出状态（AI文本、思考过程）</li>
 *   <li>流式阶段始终以 Markdown 渲染（气泡永不回退为纯文本，避免纯文本↔格式化
 *       来回切换导致的闪烁/跳动），并用 ~30fps 节流合并突发 chunk</li>
 *   <li>协调 ChatWebView 的流式 DOM 操作</li>
 *   <li>流代数管理（防止旧回调干扰新请求）</li>
 * </ul>
 *
 * <p>借鉴 yours_agent 的责任链模式中的 LlmInvokeProcessor 思想，
 * 将流式渲染逻辑从 UI 面板中解耦。
 *
 * @author CP Refactor
 */
public class StreamRenderController {

    /** 流式渲染节流间隔（毫秒），约 30fps：合并短时间内到达的多个 chunk，避免长文本下每 chunk 全量重建 DOM */
    private static final long STREAM_RENDER_INTERVAL_MS = 33;
    private static final ScheduledExecutorService RENDER_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CP-StreamRender");
                t.setDaemon(true);
                return t;
            });

    private final ChatWebView chatWebView;

    /** 回合看门狗：检测模型 / 工具长时间无进展，区分 busy / stuck，硬超时触发显式错误 */
    private final TurnWatchdog watchdog;
    /** 硬超时回调（由 ChatPanel 注入，复用 onError 收尾：停轮播 + 错误气泡 + 复原发送按钮） */
    private Runnable turnTimeoutCallback;

    private volatile int streamGeneration = 0;

    /** 跨线程可见性：流回调线程写、RENDER_SCHEDULER 渲染线程与 EDT 读；
     *  非 volatile 会导致渲染线程读到陈旧快照（显示被截断），而 EDT finalize 读到完整值 → 库完整、窗口截断 */
    private volatile boolean isReceiving = false;
    private volatile String currentAiRawText = "";
    private volatile boolean aiStreamStarted = false;
    private volatile boolean responseHadToolCalls = false;

    /** 上次渲染时间戳（节流用），跨线程（EDT重置 / 渲染线程读改写） */
    private volatile long lastRenderTs = 0;
    /** 是否已预约一次 trailing 渲染（节流窗口内被跳过的尾部，必须在窗口末补一次） */
    private final AtomicBoolean renderPending = new AtomicBoolean(false);
    /** 当前 pending 的 trailing 渲染任务（用于 finalize 时取消，杜绝 finalize 之后迟到 render 复活光标），跨线程可见 */
    private volatile ScheduledFuture<?> pendingRenderFuture = null;

    private volatile String currentReasoningText = "";
    private final StringBuilder currentReasoning = new StringBuilder();
    private boolean isReasoningCollapsed = true;
    /** JS侧是否存在未finalize的思考块（即使内容为空也要追踪，避免"思考中..."残留），跨线程可见 */
    private volatile boolean reasoningBlockOpen = false;

    public StreamRenderController(ChatWebView chatWebView) {
        this.chatWebView = chatWebView;
        this.watchdog = new TurnWatchdog(
                entered -> chatWebView.showStuckState(entered),
                () -> { if (turnTimeoutCallback != null) turnTimeoutCallback.run(); });
    }

    /** 注入硬超时回调（由 ChatPanel 在构造后设置，复用 onError 收尾逻辑） */
    public void setTurnTimeoutCallback(Runnable callback) {
        this.turnTimeoutCallback = callback;
    }

    // ── 回合看门狗接口 ──
    /** 回合开始：softMs 内无进展→切「已等待 Xs」stuck 态；hardMs 内无进展→硬超时等价 onError */
    public void startWatchdog() {
        watchdog.start(30_000, 300_000);
    }

    /** 回合结束（onComplete / onError / 用户停止）：幂等 */
    public void stopWatchdog() {
        watchdog.stop();
    }

    /** 报告一次真实进展（流式 chunk / 思考 chunk / 新一轮模型调用） */
    public void petWatchdog() {
        watchdog.pet();
    }

    /** 工具开始执行：暂停计时（已知在忙，不算卡） */
    public void pauseWatchdog() {
        watchdog.pause();
    }

    /** 工具结果返回：恢复计时 */
    public void resumeWatchdog() {
        watchdog.resume();
    }

    public int getStreamGeneration() {
        return streamGeneration;
    }

    public int incrementGeneration() {
        return ++streamGeneration;
    }

    public boolean isReceiving() {
        return isReceiving;
    }

    public void setReceiving(boolean receiving) {
        isReceiving = receiving;
    }

    public boolean isAiStreamStarted() {
        return aiStreamStarted;
    }

    public String getCurrentAiRawText() {
        return currentAiRawText;
    }

    public String getCurrentReasoningText() {
        return currentReasoningText;
    }

    public StringBuilder getCurrentReasoning() {
        return currentReasoning;
    }

    public boolean isReasoningCollapsed() {
        return isReasoningCollapsed;
    }

    public void setReasoningCollapsed(boolean collapsed) {
        isReasoningCollapsed = collapsed;
    }

    public boolean hasResponseHadToolCalls() {
        return responseHadToolCalls;
    }

    public void setResponseHadToolCalls(boolean hadToolCalls) {
        this.responseHadToolCalls = hadToolCalls;
    }

    public boolean isCurrentGeneration(int gen) {
        return gen == streamGeneration;
    }

    /**
     * 公开：取消所有 pending 的 trailing 渲染并 bump 代数。
     * 供走 sealStream 路径（工具调用边界/停止/ACP 直接收尾）的调用方在收尾前调用，
     * 防止 finalize/seal 之后迟到的 renderAiStream 把光标复活。
     */
    public void cancelPendingRenderAndBump() {
        cancelPendingRender();
        incrementGeneration();
    }

    public void resetStream() {
        currentAiRawText = "";
        currentReasoningText = "";
        currentReasoning.setLength(0);
        aiStreamStarted = false;
        responseHadToolCalls = false;
        reasoningBlockOpen = false;
        lastRenderTs = 0;
        renderPending.set(false);
        if (pendingRenderFuture != null) {
            try { pendingRenderFuture.cancel(false); } catch (Exception ignored) { }
            pendingRenderFuture = null;
        }
    }

    public void resetAll() {
        resetStream();
        isReceiving = false;
        isReasoningCollapsed = true;
    }

    /** 立即创建 AI 气泡加载占位（模型推理延迟时提升体感），不消费 aiStreamStarted */
    public void startAiStreamLoading() {
        chatWebView.startAiStreamLoading("ai_loading");
    }

    /** 使用自定义提示词分类 */
    public void startAiStreamLoading(String msgKey) {
        chatWebView.startAiStreamLoading(msgKey);
        // 新一轮模型调用：真实进展且结束「工具执行暂停」态（恢复计时）
        watchdog.resume();
    }

    public void appendStreamChunk(String chunk) {
        // 空/null chunk 直接忽略：否则 aiStreamStarted 被置 true 但 currentAiRawText 仍为空，
        // 工具调用边界时 onToolCalls 会误走 sealStream+cancelPendingRenderAndBump 分支，
        // bump 代数导致工具 lambda 被误判为旧轮次而静默返回（view_image 卡死根因）。
        if (chunk == null || chunk.isEmpty()) return;
        currentAiRawText += chunk;
        if (!aiStreamStarted) {
            aiStreamStarted = true;
            chatWebView.startAiStream();
        }
        // 每次真实流式进展都 pet 看门狗（防止模型流式阶段断线却永远轮播）
        watchdog.pet();
        // 流式阶段始终以 Markdown 渲染（不再混排纯文本），因此气泡不会在
        // 「纯文本↔格式化」之间来回切换 —— 这正是之前长回复"闪烁/不稳定"的根因。
        // 用 ~30fps 节流合并突发 chunk，并对被跳过的尾部做 trailing 渲染，避免丢字。
        chatWebView.appendAiChunk(chunk);
        scheduleStreamRender();
    }

    /** 节流调度：leading（立即渲染一次）+ trailing（窗口末补一次），保证尾部不丢且渲染频率受控 */
    private void scheduleStreamRender() {
        int gen = streamGeneration; // 捕获当前代数，防止延迟的 trailing 渲染污染新请求的会话
        long now = System.currentTimeMillis();
        long elapsed = now - lastRenderTs;
        if (elapsed >= STREAM_RENDER_INTERVAL_MS) {
            lastRenderTs = now;
            doRender(gen);
        } else if (renderPending.compareAndSet(false, true)) {
            long delay = STREAM_RENDER_INTERVAL_MS - elapsed;
            // 保存 future，finalize 时可 cancel，避免 finalize 之后 trailing 执行 renderAiStream 复活光标
            pendingRenderFuture = RENDER_SCHEDULER.schedule(() -> {
                renderPending.set(false);
                pendingRenderFuture = null;
                lastRenderTs = System.currentTimeMillis();
                doRender(gen);
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** 取消所有 pending 的 trailing 渲染（finalize 时调用），从源头阻止 finalize 之后的迟到 render */
    private void cancelPendingRender() {
        renderPending.set(false);
        if (pendingRenderFuture != null) {
            try { pendingRenderFuture.cancel(false); } catch (Exception ignored) { }
            pendingRenderFuture = null;
        }
    }

    private void doRender(int gen) {
        // 代数已变更（新请求已开始 / 用户已停止）→ 丢弃这次过期渲染，避免写入新气泡
        if (!isCurrentGeneration(gen)) return;
        // executeJavaScript 内部已 post 到 CEF UI 线程，后台线程调用安全
        updateStreamRender();
    }

    public void updateStreamRender() {
        if (currentAiRawText.isEmpty()) return;
        String bodyHtml = MarkdownUtil.toHtmlFragment(currentAiRawText);
        chatWebView.renderAiStreamHtml(bodyHtml);
    }

    /** Reasoning 不创建文本气泡，只写思考区。文本气泡由 appendStreamChunk 在有内容时创建。 */
    public void appendReasoningChunk(String chunk) {
        if (chunk == null) return;
        // 纯空白chunk：如果当前没有已打开的思考块，跳过（避免创建空的"思考中..."块）；
        // 如果已有块打开，仍然追加（保留段落间换行/缩进）
        if (!reasoningBlockOpen && chunk.trim().isEmpty()) return;
        currentReasoningText += chunk;
        reasoningBlockOpen = true;
        chatWebView.appendReasoning(chunk);
        watchdog.pet(); // 思考 chunk 也是真实进展
    }

    /** 强制finalize思考块（即使内容为空，也确保"思考中..."状态被关闭） */
    public void finalizeReasoningIfOpen() {
        if (reasoningBlockOpen) {
            chatWebView.finalizeReasoning();
            reasoningBlockOpen = false;
        }
    }

    public boolean isReasoningBlockOpen() {
        return reasoningBlockOpen;
    }

    public void finalizeAiMessage(Color cardBg) {
        finalizeReasoningIfOpen();

        if (currentAiRawText.isEmpty()) {
            // ★ 即使内容为空也要调 JS 端 finalize——让 clearStatusTimer 必触发 + 设 lcStreamFinalized=true。
            // 否则 reasoning-only / 空响应路径下 JS 端流式状态没收尾，下一轮 stream 的 chunk 渲染会
            // 找不到活气泡（用户看到「按钮已还原但轮播不停 + 后续消息发出去没回复」）。
            chatWebView.finalizeAiMessage("", "", false);
        } else {
            boolean dark = MarkdownUtil.isDarkTheme();
            String rawMd = currentAiRawText;
            String html = MarkdownUtil.toHtml(rawMd, dark, cardBg);
            String bodyHtml = MarkdownUtil.toHtmlFragment(rawMd);
            chatWebView.finalizeAiMessage(bodyHtml, rawMd);
        }

        // 根因修复：finalize 时取消所有 pending 的 trailing 渲染任务，让 Java 侧
        // 不再有 renderAiStream 被 fire 进 CEF 队列；同时 bump generation + 置
        // renderPending，对已经在飞行中的任务做双保险（doRender 会因代数不匹配丢弃）。
        // 这样 finalize 移除光标后，绝不会有迟到的 renderAiStream 再 append 光标。
        cancelPendingRender();
        incrementGeneration();
    }

    public void appendError(String errorMessage) {
        currentAiRawText += "\n\n**[错误]** " + errorMessage;
        if (!aiStreamStarted) {
            aiStreamStarted = true;
            chatWebView.startAiStream();
        }
        updateStreamRender();
    }
}
