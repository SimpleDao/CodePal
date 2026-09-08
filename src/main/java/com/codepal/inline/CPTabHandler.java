package com.codepal.inline;

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
 * 兼容性注意：IDEA 2024+ 中 EditorActionHandler 构造函数可能要求不同的参数。
 * 添加无参构造函数作为备选，并通过 getDelegate() 获取原处理器。
 */
public class CPTabHandler extends EditorActionHandler {

    /** 原始 Tab 处理器（缩进等），接受时不调用它 */
    private final EditorActionHandler originalHandler;

    /** 无参构造函数 — 用于平台反射实例化（IDEA 2024+ 兼容） */
    public CPTabHandler() {
        this(null);
    }

    public CPTabHandler(EditorActionHandler originalHandler) {
        // 使用无参构造函数以保持对早期版本的兼容
        // 在 IDEA 2024+ 中 EditorActionHandler(boolean) 可能不存在
        super();
        this.originalHandler = originalHandler;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor,
                                        @NotNull Caret caret,
                                        DataContext dataContext) {
        // 有幽灵文本：本 handler 处理
        if (CPInlineManager.getInstance().hasActiveCompletion(editor)) {
            return true;
        }
        // 无幽灵文本：交给原 Tab 处理器判断
        EditorActionHandler delegate = getDelegate();
        if (delegate != null) {
            return delegate.isEnabled(editor, caret, dataContext);
        }
        return false;
    }

    @Override
    protected void doExecute(@NotNull Editor editor,
                             @Nullable Caret caret,
                             DataContext dataContext) {
        if (CPInlineManager.getInstance().hasActiveCompletion(editor)) {
            // 接受幽灵文本：在当前 EDT 线程同步写入文档
            CPInlineManager.getInstance().acceptCompletion();
        } else {
            EditorActionHandler delegate = getDelegate();
            if (delegate != null) {
                // 无幽灵文本：走原来的 Tab 逻辑（缩进 / 代码补全选中等）
                delegate.execute(editor, caret, dataContext);
            }
        }
    }

    /**
     * 获取原始处理器。如果构造时未传入，尝试从平台 EditorActionManager 获取。
     */
    private EditorActionHandler getDelegate() {
        if (originalHandler != null) return originalHandler;
        try {
            com.intellij.openapi.editor.actionSystem.EditorActionManager mgr =
                    com.intellij.openapi.editor.actionSystem.EditorActionManager.getInstance();
            if (mgr == null) return null;
            EditorActionHandler handler = mgr.getActionHandler("EditorTab");
            if (handler != null && handler != this) {
                return handler;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
