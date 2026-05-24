package com.loongc.inline;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import org.jetbrains.annotations.NotNull;

/**
 * 编辑器生命周期监听器
 * 为每个编辑器挂载文档变化、光标移动、选区变化监听，驱动幽灵文本补全
 * @author 水龙吟
 * @date 2026-05-24
 */
public class LoongCEditorListener implements EditorFactoryListener {

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();

        // 1. 文档内容变化 → 区分插入 / 删除，驱动防抖逻辑
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                int caretOffset = editor.getCaretModel().getOffset();

                CharSequence newFrag = e.getNewFragment();
                // 新内容为空、旧内容非空 → backspace / delete 删除操作
                boolean isDeletion = (newFrag == null || newFrag.length() == 0)
                        && e.getOldLength() > 0;

                LoongCInlineManager.getInstance()
                        .onDocumentChanged(editor, caretOffset, isDeletion);
            }
        });

        // 2. 光标移动 → 清除幽灵文本
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                if (LoongCInlineManager.getInstance().hasActiveCompletion(editor)) {
                    LoongCInlineManager.getInstance().dismissCompletion();
                }
            }
        });

        // 3. 选区变化 → 清除幽灵文本
        editor.getSelectionModel().addSelectionListener(new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent e) {
                if (LoongCInlineManager.getInstance().hasActiveCompletion(editor)) {
                    LoongCInlineManager.getInstance().dismissCompletion();
                }
            }
        });
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        if (LoongCInlineManager.getInstance()
                .hasActiveCompletion(event.getEditor())) {
            LoongCInlineManager.getInstance().dismissCompletion();
        }
    }
}
