package com.codepal.inline;

import com.codepal.api.DeepSeekClient;
import com.codepal.settings.CPSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.concurrency.AppExecutorUtil;
import okhttp3.Call;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * CP 内联补全管理器（单例）
 *
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 改进点（对照 Copilot 最佳实践）：
 * 1. 使用 ScheduledExecutorService 替代 Timer，精确取消防抖任务
 * 2. 使用 FIM 格式 + /v1/completions 接口，避免模型输出整个类
 * 3. SSE 流式渲染：每个 token 到达后立即更新幽灵文本
 * 4. 复用同一 Inlay（updateText/updateLines），消除闪烁
 * 5. 触发策略：所有插入操作统一防抖；删除操作只清除幽灵文本
 * 6. 文档快照机制：EDT 上预读取 before/after 文本，后台线程不直接访问 Document
 * 7. 请求版本号：每次触发递增，过期 token 直接忽略
 * 8. OkHttp Call.cancel() 快速取消进行中的 HTTP 请求
 * 9. accepting 标志：写入文档时屏蔽 documentChanged 误触发
 */
public class CPInlineManager {

    private static final Logger LOG = Logger.getInstance(CPInlineManager.class);

    /** 光标前上下文最大字符数 */
    private static final int MAX_BEFORE = 600;
    /** 光标后上下文最大字符数（FIM suffix 用） */
    private static final int MAX_AFTER  = 200;
    /** 用于 appendToken 文档校验的前缀长度 */
    private static final int PREFIX_SNAPSHOT_LEN = 80;

    // ── 单例 ───────────────────────────────────────────────
    private static final CPInlineManager INSTANCE = new CPInlineManager();
    public static CPInlineManager getInstance() { return INSTANCE; }
    private CPInlineManager() {}

    // ── 基础设施 ─────────────────────────────────────────
    private final DeepSeekClient client = new DeepSeekClient();

    /**
     * 使用 IDEA 内置的 AppExecutorUtil 调度器，避免自己管理 Thread 造成内存泄漏。
     * 轻量级防抖定时任务不需要 Task.Backgroundable，但必须用平台提供的线程池。
     */
    private final ScheduledExecutorService scheduler =
            AppExecutorUtil.getAppScheduledExecutorService();

    /** 当前等待中的防抖 Future */
    private ScheduledFuture<?> pendingDebounce;

    /** 当前进行中的 HTTP 请求（可 cancel） */
    private Call activeCall;

    /** 请求版本号，每次触发递增，用于过滤过期 token */
    private int requestVersion = 0;

    // ── 幽灵文本状态（EDT 访问） ───────────────────────────
    private Inlay<CPInlayRenderer> activeInlineInlay;
    private Inlay<CPBlockRenderer> activeBlockInlay;
    /** 当前已积累的补全文本（流式追加） */
    private final StringBuilder        completionBuffer = new StringBuilder();
    /** 补全时的光标 offset */
    private int                        activeOffset;
    /** 当前激活的编辑器 */
    private Editor                     activeEditor;
    /** 是否正在写入（acceptCompletion），屏蔽 documentChanged */
    private boolean                    accepting = false;
    /** 当前活跃请求的版本号（用于 appendToken 校验） */
    private int                        activeRequestVersion = -1;
    /** 当前活跃请求发起时的文档前缀快照（用于 appendToken 校验） */
    private String                     activePrefixSnapshot = "";

    // ── 公开 API ───────────────────────────────────────────────

    /**
     * 文档变化时调用。
     *
     * 触发规则：
     * • isDeletion=true  （backspace/delete）→ 仅清除幽灵文本，不启动防抖，不发请求
     * • isDeletion=false （输入字符/回车/粘贴）→ 统一防抖；停顿超过 completionDelayMs 后触发
     *
     * 关键设计：
     * 1. 防抖到期后通过 invokeLater 在 EDT 上重新读取最新光标位置和文档快照。
     *    不能在 documentChanged 时刻预读：IDEA 处理 Enter 时会先插入 \n 再触发自动缩进，
     *    两次 documentChanged 之间快照不稳定，导致 appendToken 的 prefixSnapshot 校验失败，
     *    所有 token 被丢弃（这是 Enter 无法显示幽灵补全的根本原因）。
     * 2. Enter 也走统一防抖，而非立即触发。
     *    原因：IDEA 处理回车时会依次触发多个 documentChanged，统一防抖可合并为一次触发。
     */
    public void onDocumentChanged(@NotNull Editor editor, int caretOffset, boolean isDeletion) {
        if (accepting) return;

        // 立刻取消进行中的 HTTP 请求，并清除当前幽灵文本
        cancelActiveCall();
        dismissCompletion();

        if (isDeletion) {
            // 删除操作（backspace/delete）：清除幽灵文本即可，不启动补全
            return;
        }

        CPSettings s = CPSettings.getInstance();
        if (!s.isEnableSmartAutoComplete()) return;
        String effectiveKey = s.getEffectiveCompletionApiKey();
        if (effectiveKey == null || effectiveKey.trim().isEmpty()) return;

        cancelDebounce();

        final Editor capturedEditor = editor;

        // 所有插入操作（普通字符、enter、粘贴）统一防抖
        long delayMs = s.getCompletionDelayMs();
        pendingDebounce = scheduler.schedule(() -> {
            // 防抖到期后，在 EDT 上重新读取当前最新的光标位置和文档快照。
            // 此时 Enter 的自动缩进已全部完成，光标和文档内容均已稳定。
            ApplicationManager.getApplication().invokeLater(() -> {
                if (accepting) return;

                int currentOffset = capturedEditor.getCaretModel().getOffset();
                Document doc = capturedEditor.getDocument();
                String before         = getTextBefore(doc, currentOffset);
                String after          = getTextAfter(doc, currentOffset);
                String prefixSnapshot = getPrefixSnapshot(doc, currentOffset);

                triggerFIMCompletion(capturedEditor, currentOffset, before, after, prefixSnapshot);
            }, ModalityState.any());
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Tab 键接受补全：同步写入（在 EDT 上执行）
     */
    public boolean acceptCompletion() {
        if (completionBuffer.length() == 0 || activeEditor == null) return false;

        final String text   = completionBuffer.toString();
        final int    offset = activeOffset;
        final Editor editor = activeEditor;

        // 先清空状态
        completionBuffer.setLength(0);
        activeEditor = null;
        activeRequestVersion = -1;
        activePrefixSnapshot = "";
        disposeInlaysSync();

        accepting = true;
        try {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    Document doc = editor.getDocument();
                    if (offset <= doc.getTextLength()) {
                        doc.insertString(offset, text);
                        editor.getCaretModel().moveToOffset(offset + text.length());
                    }
                } catch (Exception e) {
                    LOG.warn("acceptCompletion 写入失败: " + e.getMessage());
                }
            });
        } finally {
            accepting = false;
        }
        return true;
    }

    /**
     * 清除当前幽灵文本
     */
    public void dismissCompletion() {
        cancelDebounce();
        completionBuffer.setLength(0);
        activeEditor = null;
        activeRequestVersion = -1;
        activePrefixSnapshot = "";
        disposeInlaysSync();
    }

    /**
     * 当前编辑器是否有活跃幽灵文本
     */
    public boolean hasActiveCompletion(@Nullable Editor editor) {
        return completionBuffer.length() > 0
                && activeEditor != null
                && activeEditor == editor;
    }

    // ── 内部：触发 FIM 流式补全 ───────────────────────────

    /**
     * 触发 FIM 补全请求。
     *
     * @param editor          编辑器
     * @param caretOffset     补全触发时的光标位置
     * @param before          光标前文本（EDT 上预读的快照）
     * @param after           光标后文本（EDT 上预读的快照）
     * @param prefixSnapshot  光标前缀快照（用于 token 到达时校验）
     */
    private void triggerFIMCompletion(@NotNull Editor editor, int caretOffset,
                                      @NotNull String before, @NotNull String after,
                                      @NotNull String prefixSnapshot) {
        try {
            // 递增请求版本号，并立即记录为当前活跃版本
            final int thisRequestVersion = ++requestVersion;
            activeRequestVersion = thisRequestVersion;
            System.out.println("[CP] triggerFIMCompletion 开始，ver=" + thisRequestVersion
                    + " offset=" + caretOffset);

            activeCall = client.streamFIM(before, after, new DeepSeekClient.FIMCallback() {
                /** 流式：每收到一个 token 就更新幽灵文本 */
                @Override
                public void onToken(String token) {
                    System.out.println("[CP] onToken raw=[" + token.replace("\r","↵r").replace("\n","↵n") + "]");
                    ApplicationManager.getApplication().invokeLater(
                            () -> appendToken(editor, caretOffset, token, thisRequestVersion, prefixSnapshot),
                            ModalityState.any() // 确保无论 IDEA 处于什么状态都能刷新 UI
                    );
                }

                @Override
                public void onComplete() {
                    System.out.println("[CP] FIM 补全完成，共 " + completionBuffer.length() + " 字符");
                }

                @Override
                public void onError(Throwable t) {
                    System.out.println("[CP] FIM 补全出错: " + (t != null ? t.getMessage() : "unknown"));
                }
            });
        } catch (Exception e) {
            System.out.println("[CP] triggerFIMCompletion 异常: " + e.getMessage());
            LOG.warn("triggerFIMCompletion 异常: " + e.getMessage(), e);
        }
    }

    /**
     * 每个 token 到达后调用（EDT 上）：追加到 buffer 并增量更新 Inlay
     *
     * @param editor          编辑器
     * @param offset          请求时的光标位置
     * @param token           模型返回的 token
     * @param requestVersion  该 token 属于的请求版本号
     * @param prefixSnapshot  请求时的文档前缀快照
     */
    private void appendToken(@NotNull Editor editor, int offset, @NotNull String token,
                             int requestVersion, @NotNull String prefixSnapshot) {
        // 1. 如果正在接受补全，忽略
        if (accepting) return;

        // 2. 版本号校验：如果这个 token 属于一个已被取消的请求，忽略
        if (requestVersion != activeRequestVersion) {
            System.out.println("[CP] appendToken 丢弃过期token, reqVer=" + requestVersion
                    + " activeVer=" + activeRequestVersion + " token=[" + token + "]");
            return;
        }

        // 3. 编辑器校验
        if (activeEditor != null && activeEditor != editor) {
            System.out.println("[CP] appendToken editor 不匹配，丢弃 token=[" + token + "]");
            return;
        }

        // 4. 光标位置校验
        int currentCaretOffset = editor.getCaretModel().getOffset();
        if (currentCaretOffset != offset) {
            System.out.println("[CP] appendToken 光标已移动: 期望=" + offset
                    + " 当前=" + currentCaretOffset + "，dismissCompletion，token=[" + token + "]");
            dismissCompletion();
            return;
        }

        // 5. 文档前缀校验：确保文档内容没有在请求过程中被修改
        Document doc = editor.getDocument();
        if (offset > doc.getTextLength()) {
            System.out.println("[CP] appendToken offset 越界: offset=" + offset
                    + " docLen=" + doc.getTextLength());
            dismissCompletion();
            return;
        }
        String currentPrefix = getPrefixSnapshot(doc, offset);
        if (!currentPrefix.equals(prefixSnapshot)) {
            System.out.println("[CP] appendToken prefixSnapshot 不匹配，dismissCompletion");
            System.out.println("[CP]   期望prefix=[" + prefixSnapshot.replace("\n", "↵").replace("\r", "↵") + "]");
            System.out.println("[CP]   当前prefix=[" + currentPrefix.replace("\n", "↵").replace("\r", "↵") + "]");
            dismissCompletion();
            return;
        }

        // 6. 消灭导致渲染崩溃的 \r（回车符）
        String sanitizedToken = token.replace("\r", "");
        System.out.println("[CP] appendToken sanitizedToken=[" + sanitizedToken + "]");
        completionBuffer.append(sanitizedToken);
        activeOffset = offset;
        activeEditor = editor;
        activeRequestVersion = requestVersion;
        activePrefixSnapshot = prefixSnapshot;

        // 7. 如果积累的 buffer 全是空白字符，先不渲染，等待第一个实质性字符
        // 避免创建宽度为 0 的隐形 Inlay（IDEA 缓存后续 updateText 无法撑开）
        String full = completionBuffer.toString();
        if (full.trim().isEmpty()) {
            System.out.println("[CP] appendToken buffer 全空白，等待实质性字符，当前buffer=[" + full.replace("\n","↵") + "]");
            return;
        }

        // 8. 剥离模型重复输出的开头换行符（FIM 前缀重叠）
        // Enter 后光标已在新行，模型往往会再吐一个 \n，导致渲染错位
        int stripped = 0;
        while (full.startsWith("\n")) {
            full = full.substring(1);
            completionBuffer.deleteCharAt(0);
            stripped++;
        }
        if (stripped > 0) {
            System.out.println("[CP] appendToken 剥离了 " + stripped + " 个开头 \\n");
        }

        // 9. 拆分为第一行 + 后续行
        String[] allLines  = full.split("\n", -1);
        String   firstLine = allLines[0];
        String[] restLines = allLines.length > 1
                ? Arrays.copyOfRange(allLines, 1, allLines.length)
                : new String[0];

        System.out.println("[CP] appendToken 渲染: firstLine=[" + firstLine + "] restLines=" + restLines.length);

        InlayModel inlayModel = editor.getInlayModel();

        // ── 行内 Inlay：复用或新建 ────────────────────────
        if (activeInlineInlay != null && activeInlineInlay.isValid()) {
            // 复用：只更新文本，避免闪烁
            activeInlineInlay.getRenderer().updateText(firstLine);
            activeInlineInlay.update();
        } else if (!firstLine.isEmpty()) {
            activeInlineInlay = (Inlay<CPInlayRenderer>) addInlineElementCompat(
                    inlayModel, offset, true, new CPInlayRenderer(firstLine, editor));
        }

        // ── 块 Inlay：复用或新建/销毁 ────────────────────
        if (restLines.length > 0) {
            int lineEnd = doc.getLineEndOffset(doc.getLineNumber(offset));
            if (activeBlockInlay != null && activeBlockInlay.isValid()) {
                activeBlockInlay.getRenderer().updateLines(restLines);
                activeBlockInlay.update();
            }  else {
                activeBlockInlay = (Inlay<CPBlockRenderer>) addBlockElementCompat(
                        inlayModel, lineEnd, true, false, 0,
                        new CPBlockRenderer(restLines, editor));
            }
        } else if (activeBlockInlay != null) {
            // 后续行消失（例如补全只有一行）
            if (activeBlockInlay.isValid()) activeBlockInlay.dispose();
            activeBlockInlay = null;
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // InlayModel API 兼容性封装（2023.3 → 2026.1）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 兼容性添加行内 Inlay。
     * IDEA 2024+ 中 addInlineElement 签名可能变为 (int, InlayProperties, Renderer)，
     * 通过反射适配旧版本的 (int, boolean, Renderer) 签名。
     */
    @SuppressWarnings("unchecked")
    private static Inlay<?> addInlineElementCompat(InlayModel model, int offset,
                                                   boolean relatesToPrecedingText,
                                                   CPInlayRenderer renderer) {
        try {
            // 方法1：尝试旧签名（2023.3 及之前）
            Method oldMethod = InlayModel.class.getMethod(
                    "addInlineElement", int.class, boolean.class,
                    com.intellij.openapi.editor.EditorCustomElementRenderer.class);
            return (Inlay<?>) oldMethod.invoke(model, offset, relatesToPrecedingText, renderer);
        } catch (NoSuchMethodException e) {
            // 方法2：尝试新签名（2024+ 带 InlayProperties）
            try {
                Class<?> propsClass = Class.forName(
                        "com.intellij.openapi.editor.InlayProperties");
                Object props = propsClass.getDeclaredConstructor().newInstance();
                Method relatesMethod = propsClass.getMethod("relatesToPrecedingText", boolean.class);
                relatesMethod.invoke(props, relatesToPrecedingText);

                Method newMethod = InlayModel.class.getMethod(
                        "addInlineElement", int.class, propsClass,
                        com.intellij.openapi.editor.EditorCustomElementRenderer.class);
                return (Inlay<?>) newMethod.invoke(model, offset, props, renderer);
            } catch (Exception ex) {
                throw new RuntimeException("无法找到兼容的 addInlineElement 方法", ex);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 兼容性添加块级 Inlay。
     * IDEA 2024+ 中 addBlockElement 签名可能变为 (int, InlayProperties, Renderer)，
     * 通过反射适配旧版本的 (int, boolean, boolean, int, Renderer) 签名。
     */
    @SuppressWarnings("unchecked")
    private static Inlay<?> addBlockElementCompat(InlayModel model, int offset,
                                                  boolean relatesToPrecedingText,
                                                  boolean showAbove,
                                                  int priority,
                                                  CPBlockRenderer renderer) {
        try {
            // 方法1：尝试旧签名（2023.3 及之前）
            Method oldMethod = InlayModel.class.getMethod(
                    "addBlockElement", int.class, boolean.class, boolean.class, int.class,
                    com.intellij.openapi.editor.EditorCustomElementRenderer.class);
            return (Inlay<?>) oldMethod.invoke(model, offset, relatesToPrecedingText,
                    showAbove, priority, renderer);
        } catch (NoSuchMethodException e) {
            // 方法2：尝试新签名（2024+ 带 InlayProperties）
            try {
                Class<?> propsClass = Class.forName(
                        "com.intellij.openapi.editor.InlayProperties");
                Object props = propsClass.getDeclaredConstructor().newInstance();
                Method relatesMethod = propsClass.getMethod("relatesToPrecedingText", boolean.class);
                relatesMethod.invoke(props, relatesToPrecedingText);
                Method aboveMethod = propsClass.getMethod("showAbove", boolean.class);
                aboveMethod.invoke(props, showAbove);
                Method prioMethod = propsClass.getMethod("priority", int.class);
                prioMethod.invoke(props, priority);

                Method newMethod = InlayModel.class.getMethod(
                        "addBlockElement", int.class, propsClass,
                        com.intellij.openapi.editor.EditorCustomElementRenderer.class);
                return (Inlay<?>) newMethod.invoke(model, offset, props, renderer);
            } catch (Exception ex) {
                throw new RuntimeException("无法找到兼容的 addBlockElement 方法", ex);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



    // ── 工具方法 ───────────────────────────────────────────────

    /**
     * 获取光标前的文本片段（用于 FIM prefix）
     */
    private static String getTextBefore(Document doc, int offset) {
        int start = Math.max(0, offset - MAX_BEFORE);
        return doc.getText(new TextRange(start, offset));
    }

    /**
     * 获取光标后的文本片段（用于 FIM suffix）
     */
    private static String getTextAfter(Document doc, int offset) {
        int end = Math.min(doc.getTextLength(), offset + MAX_AFTER);
        return doc.getText(new TextRange(offset, end));
    }

    /**
     * 获取光标前的短前缀快照（用于 appendToken 时校验文档是否变更）
     */
    private static String getPrefixSnapshot(Document doc, int offset) {
        int start = Math.max(0, offset - PREFIX_SNAPSHOT_LEN);
        return doc.getText(new TextRange(start, offset));
    }

    private void cancelDebounce() {
        if (pendingDebounce != null && !pendingDebounce.isDone()) {
            pendingDebounce.cancel(false);
            pendingDebounce = null;
        }
    }

    private void cancelActiveCall() {
        if (activeCall != null && !activeCall.isCanceled()) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    private void disposeInlaysSync() {
        if (activeInlineInlay != null) {
            if (activeInlineInlay.isValid()) activeInlineInlay.dispose();
            activeInlineInlay = null;
        }
        if (activeBlockInlay != null) {
            if (activeBlockInlay.isValid()) activeBlockInlay.dispose();
            activeBlockInlay = null;
        }
    }
}
