package com.loongc.inline;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tab 键拦截处理器
 *
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 逻辑：
 * - 当前编辑器有活跃幽灵文本 → 接受补全，消费 Tab 事件（不传递给下游缩进处理）
 * - 无幽灵文本 → 调用原 Tab 处理（正常缩进/补全列表选中）
 */
public class LoongCTabHandler extends EditorActionHandler {

    /** 原始 Tab 处理器（缩进等），接受时不调用它 */
    private final EditorActionHandler originalHandler;

    public LoongCTabHandler(EditorActionHandler originalHandler) {
        this.originalHandler = originalHandler;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor,
                                        @NotNull Caret caret,
                                        DataContext dataContext) {
        // 有活跃幽灵文本时，本 Handler 生效
        if (LoongCInlineManager.getInstance().hasActiveCompletion(editor)) {
            return true;
        }
        // 否则让原 Handler 决定
        return originalHandler != null && originalHandler.isEnabled(editor, caret, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor,
                             @Nullable Caret caret,
                             DataContext dataContext) {
        if (LoongCInlineManager.getInstance().hasActiveCompletion(editor)) {
            // 接受幽灵文本，将代码写入文档
            LoongCInlineManager.getInstance().acceptCompletion();
        } else if (originalHandler != null) {
            // 无幽灵文本，走原来的 Tab 逻辑（缩进）
            originalHandler.execute(editor, caret, dataContext);
        }
    }
}
