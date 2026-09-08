package com.codepal.compression;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;

/**
 * 圆形上下文进度指示器
 *
 * <p>显示当前上下文使用量占总容量的百分比，点击可触发压缩。
 * 参考 CodeBuddy 的设计风格，简约现代。
 *
 * @author 水龙吟
 */
public class ContextCircleProgress extends JButton {

    /** 上下文最大 token 数（默认 1000K = 1,000,000），可由外部按模型配置覆盖 */
    public static final long DEFAULT_MAX_CONTEXT_TOKENS = 1_000_000L;

    /** 当前上下文容量上限（分母）。默认 1M，ChatPanel 会注入用户配置的实际模型上下文上限 */
    private long maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;

    /** 圆环宽度（细线风格） */
    private static final float STROKE_WIDTH = 2f;

    /** 正方体阴影圆角 */
    private static final int CARD_ARC = 8;
    /** 阴影外边距 */
    private static final int CARD_INSET = 3;

    /** 直径 */
    private final int diameter;

    /** 当前百分比 0.0 ~ 1.0 */
    private float percentage = 0f;
    /** 当前 token 数（用于外部阈值判断） */
    private long currentTokens = 0L;

    /** 鼠标是否悬浮 */
    private boolean hover = false;

    public ContextCircleProgress(int diameter) {
        this.diameter = diameter;
        setPreferredSize(new Dimension(diameter, diameter));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusable(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("点击压缩对话历史");

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
    }

    /**
     * 设置当前 token 使用量
     */
    /** 设置当前上下文容量上限（分母），覆盖默认 1M。0/负数视为不设置 */
    public void setMaxContextTokens(long maxTokens) {
        if (maxTokens > 0) {
            this.maxContextTokens = maxTokens;
        }
    }

    /** 获取当前上下文容量上限 */
    public long getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setTokens(long tokens) {
        this.currentTokens = tokens;
        this.percentage = Math.min(1f, Math.max(0f, (float) tokens / maxContextTokens));
        updateTooltip(tokens);
        repaint();
    }

    /** 获取当前 token 使用量 */
    public long getCurrentTokens() {
        return currentTokens;
    }

    /**
     * 设置百分比（0.0 ~ 1.0）
     */
    public void setPercentage(float pct) {
        this.percentage = Math.min(1f, Math.max(0f, pct));
        long tokens = (long) (pct * maxContextTokens);
        updateTooltip(tokens);
        repaint();
    }

    private void updateTooltip(long tokens) {
        String used = formatTokens(tokens);
        String total = formatTokens(maxContextTokens);
        int pct = (int) (percentage * 100);
        setToolTipText("<html>上下文使用: <b>" + used + "</b> / " + total +
            " (" + pct + "%)<br><small>点击压缩对话历史</small></html>");
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        } else if (tokens >= 1_000) {
            return String.format("%.0fK", tokens / 1_000.0);
        } else {
            return String.valueOf(tokens);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            GraphicsUtil.setupAAPainting(g2);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            // 圆环半径（留出阴影空间）
            float outerR = Math.min(w, h) / 2f - STROKE_WIDTH - CARD_INSET;
            float innerR = outerR - STROKE_WIDTH;
            float trackDiam = outerR * 2;

            // 1) 正方体阴影（仅悬浮时，淡淡的，与圆环同心居中）
            if (hover) {
                int shadowSize = (int) trackDiam + 6;
                int sx = (w - shadowSize) / 2;
                int sy = (h - shadowSize) / 2; // 与圆环同心，居中
                g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                g2.fillRoundRect(sx, sy, shadowSize, shadowSize, CARD_ARC, CARD_ARC);
            }

            // 2) 背景轨道（加深）
            g2.setColor(getTrackColor());
            g2.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Ellipse2D.Float(cx - outerR, cy - outerR, trackDiam, trackDiam));

            // 3) 进度弧（纯白）
            if (percentage > 0) {
                g2.setColor(getProgressColor());
                g2.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Arc2D.Float prog = new Arc2D.Float(
                    cx - outerR, cy - outerR, trackDiam, trackDiam,
                    90f, -percentage * 360f,
                    Arc2D.OPEN
                );
                g2.draw(prog);
            }
        } finally {
            g2.dispose();
        }
    }

    private Color getTrackColor() {
        // 轨道色：暗黑主题下需足够亮以区分背景；hover 略亮一档
        if (!com.intellij.ui.JBColor.isBright()) {
            return hover ? new JBColor(0x5A6275, 0x5A6275) : new JBColor(0x4A5268, 0x4A5268);
        } else {
            return hover ? new JBColor(0x8089a0, 0x8089a0) : new JBColor(0x6b7280, 0x6b7280);
        }
    }

    private Color getProgressColor() {
        // 有进度的部分用纯白
        return new JBColor(Color.WHITE, Color.WHITE);
    }
}

