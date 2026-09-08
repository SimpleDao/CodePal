package com.codepal.tools;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Todo任务列表面板 —— 对标 codepal-desktop TodoPanel.vue 设计
 * 上区：任务列表（标题栏 + 列表 + 底部状态栏）
 */
public class TodoListPanel {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";

    public static class TodoItem {
        public String id;
        public String content;
        public String status;
        public String priority;

        public TodoItem(String id, String content, String status, String priority) {
            this.id = id;
            this.content = content;
            this.status = status;
            this.priority = priority;
        }
    }

    private static class RowRef {
        final TodoItem item;
        final JPanel row;
        final StatusIcon iconLabel;
        final JBLabel textLabel;

        RowRef(TodoItem item, JPanel row, StatusIcon iconLabel, JBLabel textLabel) {
            this.item = item;
            this.row = row;
            this.iconLabel = iconLabel;
            this.textLabel = textLabel;
        }
    }

    /**
     * 状态图标组件 —— 对标 codepal-desktop 的SVG图标
     * - 已完成：绿色实心圆 + 白色对勾
     * - 进行中：橙色虚线圆环（旋转动画）
     * - 待办：灰色空心圆圈
     */
    private static class StatusIcon extends JComponent {
        private String status = STATUS_PENDING;
        private static final int SIZE = 15;
        private float spinAngle = 0f;
        private Timer spinTimer;

        // 颜色 —— 对标 Vue 版本
        private static final Color COLOR_COMPLETED = new JBColor(new Color(0x10B981), new Color(0x34D399));
        private static final Color COLOR_IN_PROGRESS = new JBColor(new Color(0xF59E0B), new Color(0xFBBF24));
        private static final Color COLOR_PENDING = new JBColor(new Color(0xD1D5DB), new Color(0x4B5563));

        StatusIcon() {
            setPreferredSize(new Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE)));
            setOpaque(false);
        }

        void setStatus(String status) {
            this.status = status;
            if (STATUS_IN_PROGRESS.equals(status)) {
                startAnimation();
            } else {
                stopAnimation();
            }
            repaint();
        }

        private void startAnimation() {
            if (spinTimer != null && spinTimer.isRunning()) return;
            spinTimer = new Timer(40, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    spinAngle += 0.15f;
                    repaint();
                }
            });
            spinTimer.start();
        }

        private void stopAnimation() {
            if (spinTimer != null) {
                spinTimer.stop();
                spinTimer = null;
            }
            spinAngle = 0f;
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            stopAnimation();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = JBUI.scale(SIZE);
            float scale = s / 15f;
            float cx = s / 2f;
            float cy = s / 2f;
            float r = 5.5f * scale;

            switch (status) {
                case STATUS_COMPLETED:
                    paintCheck(g2, cx, cy, r, scale);
                    break;
                case STATUS_IN_PROGRESS:
                    paintProgressRing(g2, cx, cy, r, scale);
                    break;
                default:
                    paintCircle(g2, cx, cy, r, scale);
                    break;
            }
            g2.dispose();
        }

        /** 已完成：绿色实心圆 + 白色对勾 */
        private void paintCheck(Graphics2D g2, float cx, float cy, float r, float scale) {
            g2.setColor(COLOR_COMPLETED);
            g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
            // 白色对勾 (polyline points="20 6 9 17 4 12" in 24x24 viewBox)
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float path = new Path2D.Float();
            float ox = cx - 7.5f * scale;
            float oy = cy - 5.5f * scale;
            path.moveTo(ox + 16f * scale, oy + 6f * scale);
            path.lineTo(ox + 9f * scale, oy + 13f * scale);
            path.lineTo(ox + 4f * scale, oy + 8f * scale);
            g2.draw(path);
        }

        /** 进行中：橙色虚线圆环（旋转动画） */
        private void paintProgressRing(Graphics2D g2, float cx, float cy, float r, float scale) {
            g2.setColor(COLOR_IN_PROGRESS);
            g2.setStroke(new BasicStroke(1.8f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // 虚线效果：dash array
            float[] dash = {3f * scale, 2f * scale};
            g2.setStroke(new BasicStroke(1.8f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, dash, spinAngle * scale * 3));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }

        /** 待办：灰色空心圆圈 */
        private void paintCircle(Graphics2D g2, float cx, float cy, float r, float scale) {
            g2.setColor(COLOR_PENDING);
            g2.setStroke(new BasicStroke(1.6f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }
    }

    /** 脉冲圆点（底部状态栏用） */
    private static class PulseDot extends JComponent {
        private float alpha = 1f;
        private boolean dir = false;
        private Timer timer;
        private static final int SIZE = 14;

        PulseDot() {
            setPreferredSize(new Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE)));
            setOpaque(false);
            timer = new Timer(50, e -> {
                alpha += dir ? -0.06f : 0.06f;
                if (alpha >= 1f) { alpha = 1f; dir = true; }
                if (alpha <= 0.3f) { alpha = 0.3f; dir = false; }
                repaint();
            });
            timer.start();
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            if (timer != null) timer.stop();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = JBUI.scale(SIZE);
            g2.setColor(new Color(0x11, 0x18, 0x27, (int)(alpha * 255)));
            boolean dark = !com.intellij.ui.JBColor.isBright();
            if (dark) {
                g2.setColor(new Color(0xE5, 0xE7, 0xEB, (int)(alpha * 255)));
            }
            float r = 4f * (s / 14f);
            g2.fill(new Ellipse2D.Float(s/2f - r, s/2f - r, r * 2, r * 2));
            g2.dispose();
        }
    }

    private final List<RowRef> rows = new ArrayList<>();
    private JPanel mainPanel;
    private JScrollPane scrollPane;
    private JPanel listPanel;
    private JPanel footerPanel;
    private JBLabel footerNameLabel;
    private JBLabel footerStateLabel;
    private PulseDot footerDot;

    // ── 颜色 ── 对标 Vue 版本
    private static final Color BORDER = new JBColor(
            new Color(0xE5E7EB),
            new Color(0x3E4145)
    );
    private static final Color ROW_HOVER = new JBColor(
            new Color(0xF3F4F6),
            new Color(0x2E3033)
    );
    private static final Color ROW_ACTIVE_BG = new JBColor(
            new Color(0xF3F4F6),
            new Color(0x2E3033)
    );
    private static final Color ROW_ACTIVE_BORDER = new JBColor(
            new Color(0x111827),
            new Color(0xE5E7EB)
    );
    private static final Color COMPLETED_TEXT = new JBColor(
            new Color(0x9CA3AF),
            new Color(0x6B7280)
    );
    private static final Color NORMAL_TEXT = new JBColor(
            new Color(0x111827),
            new Color(0xE5E7EB)
    );
    private static final Color TEXT_2 = new JBColor(
            new Color(0x6B7280),
            new Color(0x9CA3AF)
    );

    private int currentIndex = -1;

    public TodoListPanel() {
        this(false);
    }

    public TodoListPanel(boolean showHeader) {
        initPanel();
    }

    private void initPanel() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setOpaque(false);

        // 列表区
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        listPanel.setBorder(JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8)));

        scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(JBUI.scale(28));

        // 初始最小高度，宽度设为Short.MAX_VALUE确保BorderLayout能正确拉伸
        scrollPane.setPreferredSize(new Dimension(Short.MAX_VALUE, JBUI.scale(52)));

        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // 顶部分割线（独立面板）
        JPanel footerDivider = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(BORDER);
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        footerDivider.setPreferredSize(new Dimension(0, 1));
        footerDivider.setOpaque(false);

        // 底部状态栏
        footerPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        footerPanel.setOpaque(false);
        footerPanel.setBorder(JBUI.Borders.empty(JBUI.scale(10), JBUI.scale(14), JBUI.scale(10), JBUI.scale(14)));
        footerPanel.setVisible(false);

        footerDot = new PulseDot();
        footerPanel.add(footerDot, BorderLayout.WEST);

        footerNameLabel = new JBLabel();
        footerNameLabel.setFont(footerNameLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        footerNameLabel.setForeground(NORMAL_TEXT);
        footerPanel.add(footerNameLabel, BorderLayout.CENTER);

        footerStateLabel = new JBLabel("进行中");
        footerStateLabel.setFont(footerStateLabel.getFont().deriveFont(Font.PLAIN, 11f));
        footerStateLabel.setForeground(TEXT_2);
        footerPanel.add(footerStateLabel, BorderLayout.EAST);

        // 用一个面板包裹分割线+状态栏
        JPanel footerWrap = new JPanel(new BorderLayout(0, 0));
        footerWrap.setOpaque(false);
        footerWrap.add(footerDivider, BorderLayout.NORTH);
        footerWrap.add(footerPanel, BorderLayout.CENTER);

        mainPanel.add(footerWrap, BorderLayout.SOUTH);
        mainPanel.setVisible(false);
    }

    public void setTodos(List<TodoItem> items) {
        listPanel.removeAll();
        rows.clear();
        for (TodoItem item : items) {
            addTodoRow(item);
        }
        updateCurrentIndex();
        adjustSize();
        updateFooter();
        mainPanel.setVisible(!items.isEmpty());
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void updateTodos(List<TodoItem> updates) {
        for (TodoItem update : updates) {
            RowRef ref = findRowById(update.id);
            if (ref != null) {
                if (update.content != null) ref.item.content = update.content;
                if (update.status != null) ref.item.status = update.status;
                if (update.priority != null) ref.item.priority = update.priority;
                refreshRow(ref);
            } else {
                addTodoRow(update);
            }
        }
        updateCurrentIndex();
        adjustSize();
        updateFooter();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void clear() {
        listPanel.removeAll();
        rows.clear();
        currentIndex = -1;
        footerPanel.setVisible(false);
        mainPanel.setVisible(false);
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    private void updateCurrentIndex() {
        // 找第一个 in_progress
        int inProgressIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (STATUS_IN_PROGRESS.equals(rows.get(i).item.status)) {
                inProgressIdx = i;
                break;
            }
        }
        if (inProgressIdx >= 0) {
            currentIndex = inProgressIdx;
        } else {
            // 找最后一个非 completed
            currentIndex = -1;
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (!STATUS_COMPLETED.equals(rows.get(i).item.status)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        // 刷新所有行的 active 状态
        for (int i = 0; i < rows.size(); i++) {
            refreshRow(rows.get(i));
        }
    }

    private void updateFooter() {
        if (currentIndex >= 0 && currentIndex < rows.size()) {
            TodoItem current = rows.get(currentIndex).item;
            footerNameLabel.setText(current.content);
            boolean inProgress = STATUS_IN_PROGRESS.equals(current.status);
            footerStateLabel.setText(inProgress ? "进行中" : "待办");
            footerPanel.setVisible(true);
        } else {
            footerPanel.setVisible(false);
        }
    }

    private RowRef findRowById(String id) {
        for (RowRef ref : rows) {
            if (ref.item.id != null && ref.item.id.equals(id)) return ref;
        }
        return null;
    }

    private void addTodoRow(TodoItem item) {
        final boolean[] hovered = {false};
        final int[] myIndex = {rows.size()};

        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                int r = JBUI.scale(6);
                boolean isActive = myIndex[0] == currentIndex && !STATUS_COMPLETED.equals(item.status);

                if (isActive) {
                    // 当前活跃项：背景色 + 左侧边框
                    g2.setColor(ROW_ACTIVE_BG);
                    g2.fill(new RoundRectangle2D.Float(JBUI.scale(2), JBUI.scale(2), w - JBUI.scale(4), h - JBUI.scale(4), r, r));
                    // 左侧2.5px边框
                    g2.setColor(ROW_ACTIVE_BORDER);
                    g2.fillRect(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), h - JBUI.scale(8));
                } else if (hovered[0]) {
                    g2.setColor(ROW_HOVER);
                    g2.fill(new RoundRectangle2D.Float(JBUI.scale(2), JBUI.scale(2), w - JBUI.scale(4), h - JBUI.scale(4), r, r));
                }
                g2.dispose();
            }
        };
        row.setBorder(JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8)));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(28)));

        StatusIcon iconLabel = new StatusIcon();
        JBLabel textLabel = new JBLabel(item.content);
        textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
        textLabel.setForeground(NORMAL_TEXT);

        // 左侧图标容器
        JPanel iconWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        iconWrap.setOpaque(false);
        iconWrap.add(iconLabel);
        iconWrap.setPreferredSize(new Dimension(JBUI.scale(20), JBUI.scale(20)));

        row.add(iconWrap, BorderLayout.WEST);
        row.add(textLabel, BorderLayout.CENTER);

        RowRef ref = new RowRef(item, row, iconLabel, textLabel);
        rows.add(ref);
        myIndex[0] = rows.size() - 1;
        refreshRow(ref);
        listPanel.add(row);

        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                hovered[0] = true;
                row.repaint();
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hovered[0] = false;
                row.repaint();
            }
        });
    }

    private void refreshRow(RowRef ref) {
        TodoItem item = ref.item;
        int myIndex = rows.indexOf(ref);
        boolean isActive = myIndex == currentIndex && !STATUS_COMPLETED.equals(item.status);

        ref.iconLabel.setStatus(item.status);

        switch (item.status) {
            case STATUS_COMPLETED:
                ref.textLabel.setForeground(COMPLETED_TEXT);
                ref.textLabel.setText("<html><strike>" + escapeHtml(item.content) + "</strike></html>");
                ref.textLabel.setFont(ref.textLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
                break;
            case STATUS_IN_PROGRESS:
                ref.textLabel.setForeground(NORMAL_TEXT);
                ref.textLabel.setText(item.content);
                ref.textLabel.setFont(ref.textLabel.getFont().deriveFont(isActive ? Font.BOLD : Font.PLAIN, 12.5f));
                break;
            default:
                ref.textLabel.setForeground(NORMAL_TEXT);
                ref.textLabel.setText(item.content);
                ref.textLabel.setFont(ref.textLabel.getFont().deriveFont(isActive ? Font.BOLD : Font.PLAIN, 12.5f));
                break;
        }
        ref.row.repaint();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void adjustSize() {
        int rowH = JBUI.scale(26);
        int pad = JBUI.scale(12);
        int footerH = footerPanel.isVisible() ? JBUI.scale(36) : 0;
        int maxH = JBUI.scale(200);
        int height = Math.min(rows.size() * rowH + pad + footerH, maxH);
        scrollPane.setPreferredSize(new Dimension(Short.MAX_VALUE, Math.max(height, rowH * 2)));
    }

    public String formatTodoList() {
        if (rows.isEmpty()) return "(无任务)";
        StringBuilder sb = new StringBuilder();
        for (RowRef ref : rows) {
            TodoItem item = ref.item;
            String mark;
            switch (item.status) {
                case STATUS_COMPLETED: mark = "[x]"; break;
                case STATUS_IN_PROGRESS: mark = "[>]"; break;
                default: mark = "[ ]"; break;
            }
            sb.append(mark).append(" ").append(item.content).append("\n");
        }
        return sb.toString();
    }

    public JComponent getComponent() {
        return mainPanel;
    }

    public int size() {
        return rows.size();
    }

    public int getCompletedCount() {
        return (int) rows.stream().filter(r -> STATUS_COMPLETED.equals(r.item.status)).count();
    }

    public boolean isAllCompleted() {
        if (rows.isEmpty()) return false;
        return rows.stream().allMatch(r -> STATUS_COMPLETED.equals(r.item.status));
    }
}
