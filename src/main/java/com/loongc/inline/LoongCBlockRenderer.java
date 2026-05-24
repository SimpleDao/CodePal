package com.loongc.inline;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * 多行幽灵文本块渲染器
 * 用于 InlayModel.addBlockElement，在光标行下方渲染补全的后续行
 * @author 水龙吟
 * @date 2026-05-24
 */
public class LoongCBlockRenderer implements EditorCustomElementRenderer {

    /** 后续各行文本（不含第一行，第一行由 LoongCInlayRenderer 显示） */
    private volatile String[] lines;
    private final Editor   editor;

    public LoongCBlockRenderer(@NotNull String[] lines, @NotNull Editor editor) {
        this.lines  = lines;
        this.editor = editor;
    }

    public String[] getLines() { return lines; }

    /**
     * 增量更新后续行内容，配合 Inlay.update() 使用
     */
    public void updateLines(@NotNull String[] newLines) {
        this.lines = newLines;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        FontMetrics fm = getFontMetrics();
        if (fm == null) return 0;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, fm.stringWidth(line));
        }
        return maxWidth;
    }

    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        int lineHeight = editor.getLineHeight();
        return lineHeight * lines.length;
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
        g.setColor(getGhostColor());

        FontMetrics fm = g.getFontMetrics();
        int lineHeight = editor.getLineHeight();
        int x = targetRegion.x;

        for (int i = 0; i < lines.length; i++) {
            int y = targetRegion.y + lineHeight * (i + 1) - fm.getDescent();
            g.drawString(lines[i], x, y);
        }
    }

    private FontMetrics getFontMetrics() {
        Font font = getEditorFont();
        if (font == null) return null;
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
            Color c = EditorColorsManager.getInstance()
                    .getGlobalScheme()
                    .getDefaultForeground();
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 120);
        } catch (Exception e) {
            return new Color(128, 128, 128, 128);
        }
    }
}
