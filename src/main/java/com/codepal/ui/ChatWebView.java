package com.codepal.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefBrowserBuilder;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandler;


import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 单实例 JBCefBrowser 聊天 WebView：
 * 一个会话一个 JBCefBrowser，消息通过 executeJavaScript 追加到 DOM。
 * 通过 HierarchyListener 确保 CEF 窗口就绪后才执行 JS。
 */
public final class ChatWebView {

    /** 工具确认回调接口 */
    public interface ToolConfirmCallback {
        void onConfirm(String toolCallId, boolean approved, boolean trusted);
    }

    /** 历史消息加载回调接口（滚动到顶部时触发） */
    public interface HistoryLoadCallback {
        void onLoadHistory();
    }

    /** 消息删除回调接口 */
    public interface MessageDeleteCallback {
        void onDeleteRound(int qaRound);
    }

    /** 模型提问回调接口 */
    public interface AskQuestionCallback {
        void onAnswer(String questionId, String answersJson);
    }

    /** 打开文件回调接口 */
    public interface OpenFileCallback {
        void onOpenFile(String filePath);
    }

    /** 回到底部按钮可见性回调：JS 滚动检测通知 Java 显示或隐藏 Swing 浮层按钮 */
    public interface ScrollBottomCallback {
        void onVisibilityChanged(boolean show);
    }

    private final JBCefBrowser browser;
    private final JBCefJSQuery copyRawQuery;
    private final JBCefJSQuery copySelQuery;
    private final JBCefJSQuery toolConfirmQuery;
    private final JBCefJSQuery askQuestionQuery;
    private final JBCefJSQuery historyLoadQuery;
    private final JBCefJSQuery deleteQuery;
    private final JBCefJSQuery openUrlQuery;
    private final JBCefJSQuery openFileQuery;
    private final JBCefJSQuery payloadQuery;
    /** 回到底部按钮可见性桥：JS 滚动检测通过此 query 通知 Java 显示/隐藏 Swing 浮层按钮 */
    private final JBCefJSQuery scrollBottomQuery;
    /** 懒加载「本轮发给模型的完整上下文」JSON：由 ChatPanel 注入，按 userMessageId 查内存快照 */
    private Function<String, String> payloadLookup;
    private ToolConfirmCallback toolConfirmCallback;
    private AskQuestionCallback askQuestionCallback;
    private HistoryLoadCallback historyLoadCallback;
    private MessageDeleteCallback deleteCallback;
    private OpenFileCallback openFileCallback;
    private ScrollBottomCallback scrollBottomCallback;
    private final JComponent component;
    private boolean isDark;
    private boolean browserReady;
    private final List<String> pendingJs = new ArrayList<>();

    public ChatWebView(boolean isDark) {
        this.isDark = isDark;
        browser = JBCefBrowser.createBuilder()
                .setOffScreenRendering(true)
                .build();

        copyRawQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        copyRawQuery.addHandler((rawMd) -> {
            if (rawMd != null && !rawMd.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(rawMd), null);
            }
            return null;
        });

        copySelQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        copySelQuery.addHandler((selText) -> {
            if (selText != null && !selText.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(selText), null);
            }
            return null;
        });

        toolConfirmQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        toolConfirmQuery.addHandler((params) -> {
            System.out.println("[ToolConfirm] JBCefJSQuery handler called, params=" + params);
            if (params != null && toolConfirmCallback != null) {
                String[] parts = params.split("\\|", 3);
                System.out.println("[ToolConfirm] parts length=" + parts.length);
                if (parts.length >= 3) {
                    String toolCallId = parts[0];
                    boolean approved = "1".equals(parts[1]);
                    boolean trusted = "1".equals(parts[2]);
                    System.out.println("[ToolConfirm] toolCallId=" + toolCallId + ", approved=" + approved + ", trusted=" + trusted);
                    toolConfirmCallback.onConfirm(toolCallId, approved, trusted);
                }
            } else {
                System.out.println("[ToolConfirm] params=" + params + ", callback=" + toolConfirmCallback);
            }
            return null;
        });

        askQuestionQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        askQuestionQuery.addHandler((params) -> {
            if (params != null && askQuestionCallback != null) {
                int sepIdx = params.indexOf('|');
                if (sepIdx > 0) {
                    String qId = params.substring(0, sepIdx);
                    String answersJson = params.substring(sepIdx + 1);
                    askQuestionCallback.onAnswer(qId, answersJson);
                }
            }
            return null;
        });

        historyLoadQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        historyLoadQuery.addHandler((params) -> {
            if (historyLoadCallback != null) {
                historyLoadCallback.onLoadHistory();
            }
            return null;
        });

        deleteQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        deleteQuery.addHandler((params) -> {
            if (deleteCallback != null && params != null) {
                try {
                    deleteCallback.onDeleteRound(Integer.parseInt(params));
                } catch (NumberFormatException ignored) {}
            }
            return null;
        });

        // 链接拦截：聊天窗内点击 http(s) 链接时，改用系统默认浏览器打开，
        // 禁止在 JCEF 内导航（否则会覆盖聊天内容且无法返回）。
        openUrlQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        openUrlQuery.addHandler((url) -> {
            if (url != null && !url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                String finalUrl = url;
                ApplicationManager.getApplication().invokeLater(() -> BrowserUtil.browse(finalUrl));
            }
            return null;
        });

        openFileQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        openFileQuery.addHandler((params) -> {
            if (openFileCallback != null && params != null) {
                openFileCallback.onOpenFile(params);
            }
            return null;
        });

        scrollBottomQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        scrollBottomQuery.addHandler((params) -> {
            if (scrollBottomCallback != null && params != null) {
                scrollBottomCallback.onVisibilityChanged("show".equals(params));
            }
            return null;
        });

        // 懒加载「本轮发送给模型的完整上下文」：JS 悬浮查看时按 userMessageId 来取。
        // JBCefJSQuery 的 handler 返回值是作为「字符串」交给页面 onSuccess 回调，异步下无法被调用方接收；
        // 因此这里不依赖 Response 回传，而是由 handler 通过 browser.executeJavaScript 主动调用页面 resolver
        // （window.__lcResolvePayload）把 JSON 交回 Promise —— 与项目其它桥「触发 Java 副作用」同一模式。
        payloadQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        payloadQuery.addHandler((params) -> {
            String msgId = null;
            String nonce = "0";
            if (params != null && !params.isEmpty()) {
                try {
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(params).getAsJsonObject();
                    if (o.has("m")) msgId = o.get("m").getAsString();
                    if (o.has("n")) nonce = o.get("n").getAsString();
                } catch (Exception ignored) { msgId = params; }
            }
            String json = (payloadLookup != null && msgId != null && !msgId.isEmpty())
                    ? payloadLookup.apply(msgId) : null;
            // payloadLookup 返回的是合法 JSON，可直接作为 JS 对象字面量传入 resolver；未记录则传 null
            String body = "window.__lcResolvePayload(" + nonce + "," + (json != null ? json : "null") + ");";
            try {
                org.cef.browser.CefBrowser cb = browser.getCefBrowser();
                cb.executeJavaScript(body, cb.getURL(), 0);
            } catch (Exception e) {
                System.err.println("[ChatWebView] payload resolve failed: " + e.getMessage());
            }
            return null;
        });

        component = browser.getComponent();
        component.setMinimumSize(new Dimension(100, 40));

        // 监听组件变为可见：仅用于移除 listener 防止重复触发；真正的 ready 信号是 CEF OnLoadEnd
        // （修复：之前用 isShowing() 当 ready，但 CEF HTML 加载是异步的，重启 IDE 后首次打开工具窗口
        //  会因 Swing 可见先于 CEF DOM 就绪，导致清空/渲染 JS 在 DOM 上找不到元素静默失败，
        //  表现为「Tab 显示会话、聊天窗却停留在欢迎页 Hey,Brother!」）
        component.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                    && component.isShowing()) {
                component.removeHierarchyListener(this::onHierarchyChanged);
                // 不再在此设 browserReady=true；由下面的 CefLoadHandler.onLoadEnd 触发
            }
        });

        // 注册 CEF 加载完成监听：HTML 真正加载完毕后置 browserReady=true 并 flush pendingJs
        try {
            // 注意：不能用 org.cef.client.CefClient（该包未暴露给插件编译期），
            // 必须用 IntelliJ 平台封装的 com.intellij.ui.jcef.JBCefClient。
            com.intellij.ui.jcef.JBCefClient client = browser.getJBCefClient();
            CefBrowser cefBrowser = browser.getCefBrowser();
            client.addLoadHandler(new CefLoadHandler() {
                @Override
                public void onLoadingStateChange(CefBrowser b, boolean isLoading,
                                                 boolean canGoBack, boolean canGoForward) { }

                @Override
                public void onLoadStart(CefBrowser b, org.cef.browser.CefFrame frame,
                                        org.cef.network.CefRequest.TransitionType transitionType) { }

                @Override
                public void onLoadEnd(CefBrowser b, org.cef.browser.CefFrame frame, int httpStatusCode) {
                    // CEF 回调通常不在 EDT，转 EDT 设 ready + flush，保证与 EDT 入队的 JS 顺序一致
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (browserReady) return;
                        browserReady = true;
                        flushPending();
                    });
                }

                @Override
                public void onLoadError(CefBrowser b, org.cef.browser.CefFrame frame, ErrorCode errorCode,
                                        String errorText, String failedUrl) {
                    // loadHTML 失败很少见；兜底释放 pendingJs，避免永久挂起
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (browserReady) return;
                        browserReady = true;
                        flushPending();
                    });
                }
            }, cefBrowser);
        } catch (Exception ex) {
            System.err.println("[ChatWebView] 注册 CefLoadHandler 失败: " + ex.getMessage());
        }

        reloadHtml();
    }

    private void onHierarchyChanged(HierarchyEvent e) {
        // lambda wrapper 供 removeHierarchyListener
    }

    public JComponent getComponent() { return component; }

    public void dispose() {
        try { copyRawQuery.dispose(); } catch (Exception ignored) {}
        try { copySelQuery.dispose(); } catch (Exception ignored) {}
        try { toolConfirmQuery.dispose(); } catch (Exception ignored) {}
        try { askQuestionQuery.dispose(); } catch (Exception ignored) {}
        try { historyLoadQuery.dispose(); } catch (Exception ignored) {}
        try { deleteQuery.dispose(); } catch (Exception ignored) {}
        try { openUrlQuery.dispose(); } catch (Exception ignored) {}
        try { openFileQuery.dispose(); } catch (Exception ignored) {}
        try { payloadQuery.dispose(); } catch (Exception ignored) {}
        try { scrollBottomQuery.dispose(); } catch (Exception ignored) {}
        try { browser.dispose(); } catch (Exception ignored) {}
    }

    /** 设置工具确认回调 */
    public void setToolConfirmCallback(ToolConfirmCallback callback) {
        this.toolConfirmCallback = callback;
    }

    /** 设置模型提问回调 */
    public void setAskQuestionCallback(AskQuestionCallback callback) {
        this.askQuestionCallback = callback;
    }

    /** 设置历史消息加载回调（滚动到顶部时触发） */
    public void setHistoryLoadCallback(HistoryLoadCallback callback) {
        this.historyLoadCallback = callback;
    }

    /** 设置消息删除回调 */
    public void setDeleteCallback(MessageDeleteCallback callback) {
        this.deleteCallback = callback;
    }

    /** 设置打开文件回调 */
    public void setOpenFileCallback(OpenFileCallback callback) {
        this.openFileCallback = callback;
    }

    /** 设置回到底部按钮可见性回调 */
    public void setScrollBottomCallback(ScrollBottomCallback callback) {
        this.scrollBottomCallback = callback;
    }

    /** 触发页面滚动到底部（供 Swing 浮层按钮点击调用） */
    public void scrollToBottom() {
        executeJs("scrollToBottom()");
    }

    /** 设置「本轮发送给模型的完整上下文」懒加载回调（按 userMessageId → JSON） */
    public void setPayloadLookup(Function<String, String> lookup) {
        this.payloadLookup = lookup;
    }

    public void setDarkTheme(boolean dark) {
        if (this.isDark == dark) return;
        this.isDark = dark;
        reloadHtml();
    }

    // ── 消息操作 ──

    public void addUserMessage(String text, String msgId) {
        String e = escapeForJs(text);
        executeJs("addUserMessage(" + e + "," + e + "," + escapeForJs(msgId) + ")");
    }

    /**
     * 渲染用户消息 HTML（已构建好：安全转义的文字 + 受控的图片 data URL）。
     * 与 addUserMessage 不同，这里不二次转义文字，调用方必须自行保证 HTML 安全。
     * @param html     直接写入 bubble.innerHTML 的内容（文字需已转义，图片用 data URL）
     * @param rawText  纯文本，用于"复制消息"
     * @param msgId    用户消息 DB id，用于挂载「查看发送给模型内容」图标
     */
    public void addUserMessageHtml(String html, String rawText, String msgId) {
        String e = escapeForJs(html);
        String r = escapeForJs(rawText == null ? "" : rawText);
        executeJs("addUserMessage(" + e + "," + r + "," + escapeForJs(msgId) + ")");
    }

    /** 加载历史消息专用：使用 DB 原始 qaRound，不自增 lcQaRound */
    public void addHistoryUserMessage(String text, int qaRound, String msgId) {
        String e = escapeForJs(text);
        executeJs("addHistoryUserMessage(" + e + "," + e + "," + qaRound + "," + escapeForJs(msgId) + ")");
    }

    /** 同步 JS 侧 lcQaRound 到指定值（历史消息加载完成后调用） */
    public void setQaRound(int round) {
        executeJs("lcQaRound=" + round);
    }

    public void startAiStream() {
        executeJs("startAiStream('')");
    }

    public void startAiStreamLoading(String msgKey) {
        // 动态注入工具特定的提示词（如 file_writing）
        String pendingRegJs = StatusMessageManager.getPendingJsRegistration();
        if (pendingRegJs != null) {
            executeJs(pendingRegJs);
        }
        executeJs("startAiStreamLoading('" + (msgKey != null ? msgKey : "ai_loading") + "')");
    }

    public void appendAiChunk(String chunk) {
        executeJs("appendAiChunk(" + escapeForJs(chunk) + ")");
    }

    public void finalizeAiMessage(String bodyHtml, String rawMarkdown) {
        finalizeAiMessage(bodyHtml, rawMarkdown, true);
    }

    /** @param addOps 是否在收尾时添加删除/复制图标。工具调用边界（整轮未结束）应传 false */
    public void finalizeAiMessage(String bodyHtml, String rawMarkdown, boolean addOps) {
        executeJs("finalizeAiMessage(" + escapeForJs(bodyHtml)
                + "," + escapeForJs(rawMarkdown) + "," + addOps + ")");
    }

    public void appendReasoning(String chunk) {
        executeJs("appendReasoning(null," + escapeForJs(chunk) + ")");
    }

    public void finalizeReasoning() {
        executeJs("finalizeReasoning(null)");
    }

    /** DeepSeek reasoner 偶发把正文放进 reasoning_content、content 为空时，把内容提升为正文渲染 */
    public void promoteReasoningToAnswer(String bodyHtml, String rawMarkdown) {
        executeJs("promoteReasoningToAnswer(" + escapeForJs(bodyHtml) + "," + escapeForJs(rawMarkdown) + ")");
    }

    public void addToolCall(String toolName, String result) {
        executeJs("addToolCall(" + escapeForJs(toolName) + ","
                + escapeForJs(result != null ? result : "") + ")");
    }

    /** ACP 路径专用：按时间线插入独立工具卡片 */
    public void appendToolCard(String name, String status) {
        executeJs("insertToolCard(" + escapeForJs(name) + ","
                + escapeForJs(status != null ? status : "pending") + ")");
    }

    /** ACP 路径专用：带详情的工具卡片（点击可展开查看） */
    public void appendToolCard(String name, String status, String detail) {
        executeJs("insertToolCard(" + escapeForJs(name) + ","
                + escapeForJs(status != null ? status : "pending") + ","
                + escapeForJs(detail != null ? detail : "") + ")");
    }

    /**
     * ACP 路径专用：支持预构建标题 HTML（nameHtml=1 时 name 参数直接作为 innerHTML 注入标题）。
     * 用于读取类平铺卡片：Java 端在标题内嵌 <a data-file> 锚点，点击跳转编辑器。
     */
    public void appendToolCard(String name, String status, String detail, boolean asNameHtml) {
        executeJs("insertToolCard(" + escapeForJs(name) + ","
                + escapeForJs(status != null ? status : "pending") + ","
                + escapeForJs(detail != null ? detail : "") + ","
                + (asNameHtml ? "1" : "0") + ")");
    }

    /** 追加文本到当前 pending 工具卡片的详情区域 */
    public void appendToPendingToolCard(String text) {
        executeJs("appendToPendingToolCard(" + escapeForJs(text) + ")");
    }

    /** 追加 HTML 到当前 pending 工具卡片的详情区域（不转义） */
    public void appendHtmlToPendingToolCard(String html) {
        executeJs("appendHtmlToPendingToolCard(" + escapeForJs(html) + ")");
    }

    /** 流式写入：把「整段文件内容」放进当前 pending 卡片的单个 <pre> 文本节点（不逐字符包 div，避免每字一行） */
    public void streamPendingToolCardText(String text) {
        executeJs("streamPendingToolCardText(" + escapeForJs(text) + ")");
    }

    /** 更新「写入 X」卡片头部的 +/− 行数统计（auto-dev 风格，对齐 CodeBuddy） */
    public void updateWriteCardStats(String name, int added, int removed) {
        executeJs("updateWriteCardStats(" + escapeForJs(name) + "," + added + "," + removed + ")");
    }

    /** 完成 pending 工具卡片，设置最终 HTML 内容，标记为已完成 */
    public void finalizePendingToolCardHtml(String html) {
        executeJs("finalizePendingToolCardHtml(" + escapeForJs(html) + ")");
    }

    /** 完成 pending 工具卡片，替换 body 整体 HTML（清掉「启动中...」占位），标记为已完成 */
    public void replacePendingToolCardBody(String html) {
        executeJs("replacePendingToolCardBody(" + escapeForJs(html) + ")");
    }

    /** 直接插入完整工具卡片 HTML（用于历史回显，不走 pending→completed 流程） */
    public void appendToolCardHtml(String html) {
        executeJs("appendHtml(" + escapeForJs(html) + ")");
    }

    /** 追加一条完整的历史消息 HTML（单帧渲染，保证一个头像；用于历史回显） */
    public void appendMessageHtml(String html) {
        executeJs("appendMessageHtml(" + escapeForJs(html) + ")");
    }

    /**
     * 历史回显：复用实时渲染链路回放单条消息记录（JSON）。
     * record = {role, qaRound, timeStr, html, raw, parts:[{kind,...}]}
     * 整条仅 1 次 CEF 调用，DOM 构建全在 JS 内（replayHistory），与聊天同一套样式。
     */
    public void replayHistory(String recordJson) {
        executeJs("replayHistory(" + recordJson + ")");
    }

    /**
     * 显示工具确认卡片（内嵌消息体）
     * @param toolCallId 工具调用ID
     * @param command 命令/操作内容描述
     * @param level 危险等级：warning / danger / info
     * @param canTrust 是否允许"本次会话不再询问"
     * @param kind 确认类型：command=终端命令，delete=删除文件操作（决定卡片标题）
     */
    public void showToolConfirm(String toolCallId, String command, String level, boolean canTrust, String kind) {
        executeJs("showToolConfirm(" + escapeForJs(toolCallId) + ","
                + escapeForJs(command) + ","
                + escapeForJs(level) + ","
                + (canTrust ? "true" : "false") + ","
                + escapeForJs(kind == null ? "" : kind) + ")");
    }

    /**
     * 显示模型提问卡片（内嵌消息流）
     * @param questionId 提问唯一ID
     * @param questionsJson 问题列表JSON（Gson序列化的List<UserQuestion>）
     */
    public void showAskUserQuestion(String questionId, String questionsJson) {
        String escapedJson = escapeForJs(questionsJson);
        executeJs("showAskUserQuestion(" + escapeForJs(questionId) + "," + escapedJson + ")");
    }

    /** 流式过程中实时渲染 Markdown：替换气泡 innerHTML 并保留光标 */
    public void renderAiStreamHtml(String bodyHtml) {
        executeJs("renderAiStream(" + escapeForJs(bodyHtml) + ")");
    }

    /** 关闭当前流式气泡（去掉光标，不触 innerHTML 替换，避免闪烁） */
    public void sealStream() {
        sealStream(true);
    }

    /** @param addOps 是否在收尾时添加删除/复制图标。工具调用边界（整轮未结束）应传 false */
    public void sealStream(boolean addOps) {
        executeJs("sealStream(" + addOps + ")");
    }

    public void clearMessages() {
        executeJs("clearMessages()");
    }

    // ── 历史懒加载 ──

    /** 向顶部前置插入更早的历史消息（复用实时 replay 链路；recordsJson = [{record},...]） */
    public void prependHistoryReplay(String recordsJson) {
        executeJs("prependHistoryReplay(" + recordsJson + ")");
    }

    /** 显示/隐藏顶部加载指示器 */
    public void showHistoryLoading(boolean show) {
        executeJs("showHistoryLoading(" + (show ? "true" : "false") + ")");
    }

    /** 通知前端是否还有更早的历史可加载 */
    public void setHasMoreHistory(boolean hasMore) {
        executeJs("setHasMoreHistory(" + (hasMore ? "true" : "false") + ")");
    }

    public void resetAiStream() {
        executeJs("resetAiStream()");
    }

    /**
     * 设置聊天区顶部/底部被 Swing 浮动面板遮挡的 inset（像素），
     * JS 会动态调整 #chat 的 padding，使消息内容始终可见、不被覆盖。
     * @param topInset 顶部面板遮挡高度（像素），0 表示无顶部遮挡
     * @param bottomInset 底部面板遮挡高度（像素），0 表示无底部遮挡
     */
    public void setOverlayInsets(int topInset, int bottomInset) {
        executeJs("setOverlayInsets(" + topInset + "," + bottomInset + ")");
    }

    /** 清除 JS 层的轮播状态计时器（停止"正在处理任务..."等轮播提示） */
    public void clearStatusTimer() {
        executeJs("clearStatusTimer()");
    }

    /**
     * 切换「已等待 Xs」stuck 琥珀态。
     * @param entered true=进入 stuck（可能卡了，提示等待时长）；false=恢复 busy 态
     */
    public void showStuckState(boolean entered) {
        executeJs("showStuckState(" + entered + ")");
    }

    public void renderTodoList(String todosJson) {
        executeJs("renderTodoList(" + escapeForJs(todosJson) + ",false)");
    }

    public void renderTodoListAndReEmit(String todosJson) {
        executeJs("renderTodoList(" + escapeForJs(todosJson) + ",true)");
    }

    public void clearTodoList() {
        executeJs("clearTodoList()");
    }

    /** 从 DOM 中移除指定轮次的消息（在 Java 侧确认删除后调用） */
    public void removeMessagesByRound(int qaRound) {
        executeJs("deleteMsgsByRound(" + qaRound + ")");
    }

    // ── 压缩状态卡片 ──

    /** 在聊天区插入/更新压缩进度卡片 */
    public void showCompressCard(String status) {
        executeJs("_showCompressCard(" + escapeForJs(status) + ")");
    }

    /** 更新压缩卡片状态（completed=true 显示对勾，failed=true 显示警告） */
    public void updateCompressCard(String status, boolean completed, boolean failed) {
        executeJs("_updateCompressCard(" + escapeForJs(status) + ","
                + (completed ? "true" : "false") + ","
                + (failed ? "true" : "false") + ")");
    }

    // ── 压缩消息视觉标记 ──

    /** 标记最后一条消息为压缩状态（半透明 + 图标） */
    public void markLastMessageCompressed() {
        executeJs("markLastMessageCompressed()");
    }

    // ── 历史摘要卡片 ──

    /** 添加可折叠的历史摘要卡片 */
    public void addSummaryCard(String title, int count, String content) {
        executeJs("addSummaryCard(" + escapeForJs(title) + "," + count + "," + escapeForJs(content) + ")");
    }

    // ── 内部 ──

    private void onBrowserReady() {
        browserReady = true;
        flushPending();
    }

    private void reloadHtml() {
        browserReady = false;
        String html = ChatHtmlTemplate.generate(isDark);
        String copyRawBridge = "window.intellijCopyRawMarkdown = function(rawMd) {" + copyRawQuery.inject("rawMd") + "};";
        String copySelBridge = "window.intellijCopySelected = function(selText) {" + copySelQuery.inject("selText") + "};";
        String confirmBridge = "window.intellijOnToolConfirm = function(params) {" + toolConfirmQuery.inject("params") + "};";
        String askQBridge = "window.intellijOnAskQuestion = function(qId,answersJson) {" + askQuestionQuery.inject("qId+'|'+answersJson") + "};";
        String historyBridge = "window.intellijLoadHistory = function() {" + historyLoadQuery.inject("") + "};";
        String deleteBridge = "window.intellijDeleteRound = function(qaRound) {" + deleteQuery.inject("qaRound") + "};";
        String openUrlBridge = "window.intellijOpenUrl = function(url) {" + openUrlQuery.inject("url") + "};";
        String openFileBridge = "window.openFile = function(path) {" + openFileQuery.inject("path") + "};";
        String scrollBottomBridge = "window.intellijScrollBottomBtn = function(state){" + scrollBottomQuery.inject("state") + "};";
        // 懒加载「本轮发给模型的完整上下文」：返回 Promise，resolve 为 JSON 对象（未记录则为 null）
        // cefQuery 是异步的，故用 nonce 关联的 resolver 把 handler 回传的 JSON 交回 Promise
        String payloadBridge = "window.intellijGetPayload = function(msgId){"
                + "return new Promise(function(resolve){"
                + "var n = ++window.__lcPayloadNonce;"
                + "window.__lcPayloadResolvers[n] = resolve;"
                + payloadQuery.inject("JSON.stringify({m:msgId, n:n})")
                + ";});};";
        html = html.replace("</body>",
                "<script>" + copyRawBridge + "\n" + copySelBridge + "\n" + confirmBridge + "\n" + askQBridge + "\n" + historyBridge + "\n" + deleteBridge + "\n" + openUrlBridge + "\n" + openFileBridge + "\n" + scrollBottomBridge + "\n" + payloadBridge + "\n</script></body>");
        browser.loadHTML(html);
    }

    private void executeJs(String js) {
        if (js == null || js.isEmpty()) return;
        if (!browserReady || browser.getCefBrowser() == null) {
            pendingJs.add(js);
            return;
        }
        doExecute(js);
    }

    private void flushPending() {
        if (pendingJs.isEmpty()) return;
        List<String> copy = new ArrayList<>(pendingJs);
        pendingJs.clear();
        for (String js : copy) {
            doExecute(js);
        }
    }

    private void doExecute(String js) {
        try {
            browser.getCefBrowser().executeJavaScript(js,
                    browser.getCefBrowser().getURL(), 0);
        } catch (Exception e) {
            System.err.println("[ChatWebView] executeJs failed: " + e.getMessage());
        }
    }

    private static String escapeForJs(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /**
     * 把「本轮回答 token 消耗」信息挂到当前正在收尾的助手消息底部（流式结束前调用，
     * 此时 lcStreamingMsgId 仍指向该助手消息；finalize 之后该 id 即被置空）。
     * json 形如 {"input":N,"output":N,"cacheHit":N,"cacheMiss":N,"total":N,"cost":"x 元"}
     */
    public void attachTokenInfo(String json) {
        if (json == null || json.isEmpty()) return;
        executeJs("attachTokenInfo(" + escapeForJs(json) + ")");
    }
}
