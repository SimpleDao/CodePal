package com.codepal.inline;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * 幽灵文本渲染器
 * 将 AI 补全建议以灰色文字渲染在光标位置右侧（同行）
 * 支持 updateText() 增量更新，避免反复销毁重建 Inlay（参考 Copilot 最佳实践）
 * @author 水龙吟
 * @date 2026-05-24
 */
public class CPInlayRenderer implements EditorCustomElementRenderer {

    /** 本行要显示的文本（仅第一行内容，后续行由 CPBlockRenderer 负责） */
    private volatile String text;
    private final Editor editor;

    public CPInlayRenderer(@NotNull String text, @NotNull Editor editor) {
        this.text   = text;
        this.editor = editor;
    }

    public String getText() { return text; }

    /**
     * 增量更新文本内容，配合 Inlay.update() 使用，避免销毁重建
     */
    public void updateText(@NotNull String newText) {
        this.text = newText;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        FontMetrics fm = getFontMetrics();
        return fm != null ? fm.stringWidth(text) : 0;
    }

    @Override
    public void paint(@NotNull Inlay inlay,
                      @NotNull Graphics g,
                      @NotNull Rectangle targetRegion,
                      @NotNull TextAttributes textAttributes) {
        Font font = getEditorFont();
        if (font != null) {
            g.setFont(font);
        }
        // 使用编辑器行注释颜色（通常是灰色），与 Copilot 幽灵文本视觉一致
        Color ghostColor = getGhostColor();
        g.setColor(ghostColor);
        FontMetrics fm = g.getFontMetrics();
        int baseline = targetRegion.y + targetRegion.height - fm.getDescent();
        g.drawString(text, targetRegion.x, baseline);
    }

    private FontMetrics getFontMetrics() {
        Font font = getEditorFont();
        if (font == null) return null;
        // 使用编辑器 JComponent 获取 FontMetrics
        return editor.getComponent().getFontMetrics(font);
    }

    private Font getEditorFont() {
        try {
            return EditorColorsManager.getInstance()
                    .getGlobalScheme()
                    .getFont(EditorFontType.ITALIC);
        } catch (Exception e) {
            return null;
        }
    }

    private Color getGhostColor() {
        try {
            // 尝试取编辑器主题的行注释颜色
            Color c = EditorColorsManager.getInstance()
                    .getGlobalScheme()
                    .getDefaultForeground();
            // 将前景色透明度降为 50%，形成灰色幽灵效果
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 120);
        } catch (Exception e) {
            return new Color(128, 128, 128, 128);
        }
    }
}
