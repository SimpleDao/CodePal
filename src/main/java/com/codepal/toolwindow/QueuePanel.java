package com.codepal.toolwindow;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.codepal.model.ChatMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * 队列面板：渲染在 ChatPanel 输入框上方，显示「正在排队等待 AI 回复结束后自动发送」的消息列表。
 *
 * <p>仅在 {@link MessageQueue} 非空时外部可见。无项时整个面板隐藏，不占任何布局空间。
 *
 * <p>组成：
 * <ul>
 *   <li>头部条（始终显示）：折叠箭头 + 「队列 (N)」+ 「还有 N 个任务待执行」胶囊徽章</li>
 *   <li>列表区（展开时）：每条队列项一行，含序号、文本预览、附件标记、↑置顶、✎编辑、✕删除</li>
 * </ul>
 *
 * <p>所有操作通过 {@link Callbacks} 抛回 ChatPanel；本类只负责 UI 与渲染，不持有任何决策权。
 *
 * @author 水龙吟
 */
public class QueuePanel extends JPanel {

    /** 队列操作回调（来自外部 ChatPanel） */
    public interface Callbacks {
        void onMoveToTop(String itemId);
        void onEdit(String itemId);
        void onRemove(String itemId);
    }

    private final MessageQueue queue;
    private final Callbacks callbacks;

    private final JPanel headerRow;
    private final JLabel chevron;
    private final JLabel titleLabel;
    private final JLabel summaryChip;       //「还有 N 个待执行」胶囊内容
    private final JPanel listContainer;

    /** 当前是否展开 */
    private boolean expanded = true;

    /** 面板最大高度（px）：队列项过多时不无限增高，超出部分由 centerLayer doLayout 裁切。 */
    private static final int MAX_PANEL_HEIGHT = 220;

    public QueuePanel(MessageQueue queue, Callbacks callbacks) {
        super(new BorderLayout(0, 0));
        this.queue = queue;
        this.callbacks = callbacks;

        setOpaque(false);
        setBorder(JBUI.Borders.empty(8, 14, 10, 14));  // 卡片内边距（上下给圆角留白）

        // ── 头部条 ──
        headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        headerRow.setOpaque(false);

        chevron = new JLabel("▾");
        chevron.setFont(JBUI.Fonts.label(11));
        chevron.setForeground(secondaryFg());
        chevron.setHorizontalAlignment(SwingConstants.CENTER);
        chevron.setPreferredSize(new Dimension(14, 18));

        titleLabel = new JLabel("队列 (0)");
        titleLabel.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        titleLabel.setForeground(UIUtil.getLabelForeground());

        summaryChip = new JLabel("暂无待发") {
            // 圆角胶囊底色
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = getWidth(), h = getHeight();
                    int arc = Math.min(h, 12);
                    g2.setColor(chipBg());
                    g2.fillRoundRect(0, 0, w, h, arc, arc);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        summaryChip.setFont(JBUI.Fonts.label(11));
        summaryChip.setForeground(chipFg());
        summaryChip.setOpaque(false);
        summaryChip.setBorder(JBUI.Borders.empty(2, 8));

        headerRow.add(chevron);
        headerRow.add(titleLabel);
        headerRow.add(Box.createHorizontalStrut(8));
        headerRow.add(summaryChip);

        // 头部条整体响应点击：切换展开/收起。
        // 这里只关心 mouseClicked；光标由各子组件持有 HAND_CURSOR，
        // headerRow 容器由于 FlowLayout 子组件遮挡，mouseEntered 不可靠，所以不依赖 hover 处理。
        MouseAdapter toggleRecursive = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (queue.isEmpty()) return;
                expanded = !expanded;
                applyExpandedState();
                refresh();
            }
        };
        attachClickRecursive(headerRow, toggleRecursive);

        // ── 列表容器 ──
        listContainer = new JPanel();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setOpaque(false);
        listContainer.setBorder(JBUI.Borders.empty(4, 0, 4, 0));

        add(headerRow, BorderLayout.NORTH);
        add(listContainer, BorderLayout.CENTER);

        applyExpandedState();
        refresh();
    }

    /** 限制面板高度：多行队列项时不会无限增高（centerLayer 会给本面板固定高度，超出的被裁切）。 */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (d.height > MAX_PANEL_HEIGHT) d.height = MAX_PANEL_HEIGHT;
        if (d.width < 200) d.width = 200;   // 给个合理最小宽，避免宽度异常导致 validate 抖动
        return d;
    }

    /** 自绘圆角卡片背景（与任务/Diff 面板同风格）：半透明阴影 + 实心卡片 + 描边。 */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = 12;
            // 浅阴影（比任务面板略淡，避免与输入框区视觉打架）
            g2.setColor(new Color(0, 0, 0, 10));
            g2.fill(new RoundRectangle2D.Float(1, 2, w - 2, h - 1, r, r));
            // 卡片底色
            g2.setColor(cardBg());
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 2, r, r));
            // 描边
            g2.setColor(cardBorder());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 3, r, r));
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /** 组件及其所有子组件统一挂点击监听；每个组件设置手形光标。 */
    private static void attachClickRecursive(java.awt.Container c, MouseAdapter adapter) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(adapter);
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                child.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                child.addMouseListener(adapter);
            }
            // JLabel 嵌套不深，不再递归容器（避免误命中其他交互元素）
        }
    }

    /** 重新渲染：标题、徽章、列表全部按当前状态重画。 */
    public void refresh() {
        List<MessageQueue.QueueItem> items = queue.snapshot();
        titleLabel.setText("队列 (" + items.size() + ")");
        if (items.isEmpty()) {
            summaryChip.setText("暂无待发");
        } else {
            summaryChip.setText("还有 " + items.size() + " 个任务待执行");
        }
        summaryChip.repaint();

        // 重新渲染列表
        listContainer.removeAll();
        if (!items.isEmpty()) {
            int idx = 0;
            for (MessageQueue.QueueItem item : items) {
                listContainer.add(new QueueRow(idx + 1, item));
                listContainer.add(Box.createVerticalStrut(2));
                idx++;
            }
        }
        listContainer.revalidate();
        listContainer.repaint();
    }

    /** 控制 expanded 并同步箭头/列表区可见性 */
    private void applyExpandedState() {
        chevron.setText(expanded ? "▾" : "▸");
        listContainer.setVisible(expanded);
    }

    /** 单条队列项 UI 行 */
    private class QueueRow extends JPanel {
        QueueRow(int displayIndex, MessageQueue.QueueItem item) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, dividerColor()),
                    JBUI.Borders.emptyTop(6)));

            // 限制最大显示长度
            int maxTextLen = 80;
            String text = item.text == null ? "" : item.text;
            if (text.length() > maxTextLen) text = text.substring(0, maxTextLen) + "…";
            final String displayText = text;
            boolean hasImages = item.attachments != null && !item.attachments.isEmpty();

            // 左侧：序号 + 内容
            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            left.setOpaque(false);
            left.setAlignmentX(LEFT_ALIGNMENT);

            JLabel idxLabel = new JLabel(dragHandle() + " " + displayIndex + ".");
            idxLabel.setFont(JBUI.Fonts.label(12));
            idxLabel.setForeground(secondaryFg());
            idxLabel.setBorder(JBUI.Borders.emptyRight(4));
            left.add(idxLabel);

            // 纯图片消息（无文本）显示占位文案；有文本则显示文本
            if (displayText.isEmpty() && hasImages) {
                JLabel imgOnly = new JLabel("[图片消息]");
                imgOnly.setFont(JBUI.Fonts.label(12));
                imgOnly.setForeground(secondaryFg());
                left.add(imgOnly);
            } else if (!displayText.isEmpty()) {
                JLabel textLabel = new JLabel(displayText);
                textLabel.setFont(JBUI.Fonts.label(12));
                textLabel.setForeground(UIUtil.getLabelForeground());
                left.add(textLabel);
            }

            if (hasImages) {
                JLabel attachLabel = new JLabel(item.attachments.size() + " 张图片");
                attachLabel.setFont(JBUI.Fonts.label(11));
                attachLabel.setForeground(secondaryFg());
                attachLabel.setBorder(JBUI.Borders.emptyLeft(8));
                left.add(attachLabel);
            }
            add(left, BorderLayout.CENTER);

            // 右侧：操作按钮组（↑置顶 / ✎编辑 / ✕删除）
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            right.setOpaque(false);
            right.add(buildIconBtn(moveUpIcon(), "上移到队首", () -> callbacks.onMoveToTop(item.id)));
            right.add(buildIconBtn(editIcon(),   "把内容写回输入框", () -> callbacks.onEdit(item.id)));
            right.add(buildIconBtn(deleteIcon(), "从队列中移除", () -> callbacks.onRemove(item.id)));

            add(right, BorderLayout.EAST);
        }
    }

    /** "图标按钮"：透明背景 + hover 圆角半透明黑卡片 + 点击回调。统一 24x22 尺寸，点击区足够大。 */
    private static JLabel buildIconBtn(String glyph, String tooltip, Runnable onClick) {
        JLabel btn = new JLabel(glyph) {
            private boolean hover = false;
            {
                setOpaque(false);
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) {
                        hover = true; repaint();
                    }
                    @Override public void mouseExited(MouseEvent e) {
                        // Swing 在鼠标进入子组件时会向容器发 mouseExited，但这里是叶子组件，
                        // 所以 mouseExited 总是真实离开，直接关掉 hover 即可。
                        hover = false; repaint();
                    }
                    @Override public void mouseClicked(MouseEvent e) {
                        onClick.run();
                    }
                });
            }
            @Override
            protected void paintComponent(Graphics g) {
                // 先画 hover 卡片底色（如有），再调 super 画文字
                if (hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        int w = getWidth(), h = getHeight();
                        int pad = 2;
                        int cardW = w - pad * 2, cardH = h - pad * 2;
                        int arc = 6;
                        g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                        g2.fillRoundRect(pad, pad, cardW, cardH, arc, arc);
                    } finally {
                        g2.dispose();
                    }
                }
                super.paintComponent(g);
            }
        };
        btn.setFont(JBUI.Fonts.label(13));
        btn.setForeground(secondaryFg());
        btn.setHorizontalAlignment(SwingConstants.CENTER);
        btn.setVerticalAlignment(SwingConstants.CENTER);
        btn.setPreferredSize(new Dimension(24, 22));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(tooltip);
        return btn;
    }

    // ── 颜色 / 文字辅助 ──

    private static Color secondaryFg() {
        return JBColor.namedColor("Label.infoForeground", JBColor.GRAY);
    }

    private static Color chipBg() {
        return new JBColor(new Color(0xE8E9EB), new Color(0x262A30));
    }

    private static Color chipFg() {
        return new JBColor(new Color(0x555A66), new Color(0xB8BDC6));
    }

    /** 卡片背景色（与任务/Diff 面板 PANEL_BG 一致） */
    private static Color cardBg() {
        return new JBColor(new Color(0xFFFFFF), new Color(0x2B2D30));
    }

    /** 卡片描边色（与任务/Diff 面板 BORDER 一致） */
    private static Color cardBorder() {
        return new JBColor(new Color(0xE5E7EB), new Color(0x3E4145));
    }

    private static Color dividerColor() {
        return JBColor.namedColor("Separator.separatorColor",
                new JBColor(new Color(0xCFD1D6), new Color(0x4E5157)));
    }

    private static String dragHandle() { return "≡"; }
    private static String moveUpIcon() { return "↑"; }
    private static String editIcon()   { return "✎"; }
    private static String deleteIcon() { return "✕"; }
}
