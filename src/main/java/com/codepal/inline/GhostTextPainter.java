package com.codepal.inline;

import java.awt.*;

/**
 * 幽灵文本绘制工具：解决编辑器字体（JetBrains Mono 等编程字体）不含 CJK 字形，
 * 中文在幽灵文字里显示为方块（tofu）的问题。
 *
 * <p>原理：编辑器自身渲染管线有字体回退链（CJK 回退到雅黑等），但 Inlay 的
 * {@link com.intellij.openapi.editor.EditorCustomElementRenderer} 用
 * {@code Graphics.drawString} 裸画时不经过该回退链。这里按“字符能否被编辑器字体
 * 显示”把文本切段：可显示段（ASCII 等）仍用编辑器字体保持等宽观感，
 * 其余段（CJK）用 Java 逻辑字体 SansSerif（复合字体，按平台映射到含 CJK 的物理字体）绘制。
 *
 * @author 水龙吟
 * @date 2026-09-11
 */
final class GhostTextPainter {

    private GhostTextPainter() {}

    /** CJK 回退字体：Java 逻辑字体（复合字体），沿用编辑器字体的字号与斜体风格 */
    static Font fallbackFont(Font base) {
        return new Font(Font.SANS_SERIF, base.getStyle(), base.getSize());
    }

    /** 编辑器字体能否完整显示 s */
    private static boolean canDisplay(Font font, String s) {
        return font.canDisplayUpTo(s) == -1;
    }

    /**
     * 混排宽度：与 {@link #draw} 的分段逻辑一致，保证 calcWidthInPixels 不截断。
     * 整串均可显示时等价于 fm.stringWidth(s)。
     */
    static int stringWidth(Component c, Font base, String s) {
        if (canDisplay(base, s)) {
            return c.getFontMetrics(base).stringWidth(s);
        }
        Font fb = null;
        int w = 0;
        int i = 0, n = s.length();
        while (i < n) {
            boolean baseCan = base.canDisplay(s.charAt(i));
            int j = i + 1;
            while (j < n && base.canDisplay(s.charAt(j)) == baseCan) j++;
            String run = s.substring(i, j);
            Font f = baseCan ? base : (fb != null ? fb : (fb = fallbackFont(base)));
            w += c.getFontMetrics(f).stringWidth(run);
            i = j;
        }
        return w;
    }

    /**
     * 分段绘制：编辑器字体可显示的段用它绘制，其余段用回退字体绘制。
     * 返回绘制结束的 x 坐标。
     */
    static int draw(Graphics g, Component c, Font base, String s, int x, int y) {
        if (canDisplay(base, s)) {
            g.setFont(base);
            g.drawString(s, x, y);
            return x + c.getFontMetrics(base).stringWidth(s);
        }
        Font fb = null;
        int cx = x;
        int i = 0, n = s.length();
        while (i < n) {
            boolean baseCan = base.canDisplay(s.charAt(i));
            int j = i + 1;
            while (j < n && base.canDisplay(s.charAt(j)) == baseCan) j++;
            String run = s.substring(i, j);
            Font f = baseCan ? base : (fb != null ? fb : (fb = fallbackFont(base)));
            g.setFont(f);
            g.drawString(run, cx, y);
            cx += c.getFontMetrics(f).stringWidth(run);
            i = j;
        }
        return cx;
    }
}
