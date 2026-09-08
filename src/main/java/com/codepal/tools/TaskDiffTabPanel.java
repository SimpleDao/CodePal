package com.codepal.tools;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 任务 + 文件变更面板 —— 对标图2的现代化设计
 * 顶部 Tab 栏：圆角胶囊式，深色背景，文字居中
 * 内容区：分组标题行（N个文件 + 保留/撤销/查看变更）+ 文件列表
 */
public class TaskDiffTabPanel {

    private enum ActiveTab { TASKS, CHANGES }

    private JPanel mainPanel;
    private JPanel tabBar;
    private JPanel tasksTabBtn;
    private JPanel changesTabBtn;

    private JPanel contentPanel;
    private JPanel tasksCard;
    private JPanel changesCard;

    // 文件列表分组标题行
    private JPanel changesHeader;
    private JBLabel changesCountLabel;
    private JLabel expandArrow;
    private RoundButton keepAllBtn;
    private RoundButton revertAllBtn;

    private final TodoListPanel todoListPanel;
    private FileChangeListPanel fileChangeListPanel;

    private ActiveTab activeTab = ActiveTab.TASKS;
    private boolean collapsed = false;
    /** 文件列表内部折叠状态（点击"▼ N个文件"箭头切换） */
    private boolean filesListCollapsed = false;

    private final com.intellij.openapi.project.Project project;
    private final BiConsumer<String, String> onFileAccepted;
    private final Consumer<String> onFileRejected;
    private final Consumer<String> onFileRemoved;
    private final Runnable onAllResolved;
    private final Runnable onClose;
    private final Runnable onLayoutChanged;

    // ── 颜色（对标图2深色风格，同时支持亮/暗主题） ──
    private static final Color PANEL_BG = new JBColor(
            new Color(0xFFFFFF),
            new Color(0x2B2D30)
    );
    private static final Color BORDER = new JBColor(
            new Color(0xE5E7EB),
            new Color(0x3E4145)
    );
    // Tab 胶囊背景（激活态与未激活态同底，激活时再蒙一层灰白高亮以区分）
    // 暗主题底：#3B4354（中性灰，不再用近黑 #1E1F22）
    private static final Color TAB_BG_INACTIVE = new JBColor(
            new Color(0xF3F4F6),
            new Color(0x3B4354)
    );
    // 激活态蒙层：淡灰白半透明
    private static final Color TAB_ACTIVE_OVERLAY = new Color(255, 255, 255, 40);
    private static final Color TAB_TEXT_ACTIVE = new JBColor(
            new Color(0xFFFFFF),
            new Color(0xE5E7EB)
    );
    private static final Color TAB_TEXT_INACTIVE = new JBColor(
            new Color(0x6B7280),
            new Color(0x9CA3AF)
    );
    // 按钮颜色
    private static final Color BTN_KEEP_BG = new JBColor(new Color(0x2563EB), new Color(0x3B82F6));
    private static final Color BTN_KEEP_HOVER = new JBColor(new Color(0x1D4ED8), new Color(0x2563EB));
    private static final Color BTN_KEEP_FG = Color.WHITE;
    private static final Color BTN_REVERT_BG = new JBColor(new Color(0x374151), new Color(0x4B5563));
    private static final Color BTN_REVERT_HOVER = new JBColor(new Color(0x1F2937), new Color(0x374151));
    private static final Color BTN_REVERT_FG = new JBColor(new Color(0xF3F4F6), new Color(0xE5E7EB));
    private static final Color BTN_VIEW_BORDER = new JBColor(new Color(0xD1D5DB), new Color(0x6B7280));
    private static final Color BTN_VIEW_FG = new JBColor(new Color(0x374151), new Color(0xD1D5DB));
    private static final Color BTN_VIEW_HOVER_BG = new JBColor(new Color(0xF3F4F6), new Color(0x374151));

    private static final Color HEADER_TEXT = new JBColor(new Color(0x374151), new Color(0xD1D5DB));
    private static final Color HEADER_BG = new JBColor(new Color(0xF9FAFB), new Color(0x2B2D30));
    private static final Color HEADER_SEPARATOR = new JBColor(new Color(0xE5E7EB), new Color(0x3E4145));

    public TaskDiffTabPanel(@NotNull com.intellij.openapi.project.Project project,
                            @Nullable BiConsumer<String, String> onFileAccepted,
                            @Nullable Consumer<String> onFileRejected,
                            @Nullable Consumer<String> onFileRemoved,
                            @Nullable Runnable onAllResolved,
                            @Nullable Runnable onClose,
                            @Nullable Runnable onLayoutChanged) {
        this.project = project;
        this.onFileAccepted = onFileAccepted;
        this.onFileRejected = onFileRejected;
        this.onFileRemoved = onFileRemoved;
        this.onAllResolved = onAllResolved;
        this.onClose = onClose;
        this.onLayoutChanged = onLayoutChanged;

        todoListPanel = new TodoListPanel(false);
        initPanel();
    }

    private void initPanel() {
        mainPanel = new JPanel(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(new Color(0, 0, 0, 12));
                g2.fill(new RoundRectangle2D.Float(1, 2, w - 2, h - 1, JBUI.scale(14), JBUI.scale(14)));
                g2.setColor(PANEL_BG);
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 2, JBUI.scale(14), JBUI.scale(14)));
                g2.setColor(BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 3, JBUI.scale(14), JBUI.scale(14)));
                g2.dispose();
            }
        };
        mainPanel.setOpaque(false);
        mainPanel.setBorder(JBUI.Borders.empty(1, 1, 2, 1));
        mainPanel.setVisible(false);

        // ── Tab 栏（圆角胶囊式） ──
        tabBar = new JPanel(new GridLayout(1, 2, JBUI.scale(4), 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));

        tasksTabBtn = createCapsuleTab("任务列表", true);
        changesTabBtn = createCapsuleTab("文件列表", false);

        tasksTabBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { switchTab(ActiveTab.TASKS); }
        });
        changesTabBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { switchTab(ActiveTab.CHANGES); }
        });

        tabBar.add(tasksTabBtn);
        tabBar.add(changesTabBtn);
        mainPanel.add(tabBar, BorderLayout.NORTH);

        // ── 内容区 ──
        contentPanel = new JPanel(new CardLayout());
        contentPanel.setOpaque(false);

        tasksCard = createTasksCard();
        changesCard = createChangesCard();

        contentPanel.add(tasksCard, ActiveTab.TASKS.name());
        contentPanel.add(changesCard, ActiveTab.CHANGES.name());

        mainPanel.add(contentPanel, BorderLayout.CENTER);

        // ── 预创建 FileChangeListPanel ──
        fileChangeListPanel = new FileChangeListPanel(
                project, onFileAccepted, onFileRejected, onFileRemoved, onAllResolved,
                () -> {
                    updateChangesBadge();
                    updateActionButtonsVisibility();
                    updateVisibility();
                    fireLayoutChanged();
                }
        );
        changesCard.add(fileChangeListPanel.getComponent(), BorderLayout.CENTER);

        updateTabStyles();
    }

    /** 创建圆角胶囊Tab按钮（图2风格） */
    private JPanel createCapsuleTab(String title, boolean isTasks) {
        final boolean[] hovered = {false};
        final int tabH = JBUI.scale(26);

        JPanel btn = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                boolean isActive = (isTasks && activeTab == ActiveTab.TASKS && !collapsed)
                        || (!isTasks && activeTab == ActiveTab.CHANGES && !collapsed);
                Color bg = TAB_BG_INACTIVE;
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, JBUI.scale(12), JBUI.scale(12)));
                // 激活态：在同底之上蒙一层淡淡的灰白高亮，以区别于未激活
                if (isActive) {
                    g2.setColor(TAB_ACTIVE_OVERLAY);
                    g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, JBUI.scale(12), JBUI.scale(12)));
                }
                g2.dispose();
                super.paintComponent(g);
            }
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(d.width, tabH);
            }
            @Override
            public Dimension getMinimumSize() {
                Dimension d = super.getMinimumSize();
                return new Dimension(d.width, tabH);
            }
        };
        btn.setOpaque(false);
        btn.setBorder(JBUI.Borders.empty(0, JBUI.scale(10), 0, JBUI.scale(8)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered[0] = true; btn.repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered[0] = false; btn.repaint(); }
        });

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.gridy = 0;

        // 左侧留白
        gbc.gridx = 0;
        gbc.weightx = 1;
        gbc.insets = JBUI.emptyInsets();
        JPanel leftSpacer = new JPanel();
        leftSpacer.setOpaque(false);
        btn.add(leftSpacer, gbc);

        // 图标
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.insets = JBUI.insetsRight(3);
        JLabel iconLabel = new JLabel(isTasks ? AllIcons.Actions.ListFiles : AllIcons.Actions.Diff);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        btn.add(iconLabel, gbc);

        // 标题
        gbc.gridx = 2;
        gbc.insets = JBUI.insetsRight(3);
        JBLabel titleLabel = new JBLabel(title);
        Font f = titleLabel.getFont();
        if (f != null) titleLabel.setFont(f.deriveFont(Font.PLAIN, 11f));
        titleLabel.setVerticalAlignment(SwingConstants.CENTER);
        btn.add(titleLabel, gbc);

        // 计数徽章
        gbc.gridx = 3;
        gbc.insets = JBUI.insetsRight(5);
        JBLabel countBadge = new JBLabel("") {
            @Override
            public Dimension getPreferredSize() {
                if (!isVisible() || getText().isEmpty()) return new Dimension(0, 0);
                Dimension d = super.getPreferredSize();
                return new Dimension(d.width + JBUI.scale(6), Math.max(d.height, JBUI.scale(14)));
            }
        };
        if (f != null) countBadge.setFont(f.deriveFont(Font.PLAIN, 10f));
        countBadge.setBorder(new EmptyBorder(0, JBUI.scale(3), 0, JBUI.scale(3)));
        countBadge.setOpaque(false);
        countBadge.setVisible(false);
        countBadge.setVerticalAlignment(SwingConstants.CENTER);
        btn.add(countBadge, gbc);

        // 箭头
        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.insets = JBUI.emptyInsets();
        JLabel arrowLabel = new JLabel(AllIcons.General.ArrowUp);
        arrowLabel.setVerticalAlignment(SwingConstants.CENTER);
        btn.add(arrowLabel, gbc);

        // 右侧留白
        gbc.gridx = 5;
        gbc.weightx = 1;
        JPanel rightSpacer = new JPanel();
        rightSpacer.setOpaque(false);
        btn.add(rightSpacer, gbc);

        // 保存引用
        if (isTasks) {
            btn.putClientProperty("titleLabel", titleLabel);
            btn.putClientProperty("countBadge", countBadge);
            btn.putClientProperty("arrowLabel", arrowLabel);
            btn.putClientProperty("isTasks", true);
        } else {
            btn.putClientProperty("titleLabel", titleLabel);
            btn.putClientProperty("countBadge", countBadge);
            btn.putClientProperty("arrowLabel", arrowLabel);
            btn.putClientProperty("isTasks", false);
        }

        return btn;
    }

    /** 圆角按钮（对标图2的保留/撤销/查看变更） */
    private static class RoundButton extends JComponent {
        private String text;
        private ButtonStyle style;
        private boolean hovered = false;
        private boolean pressed = false;
        private boolean visible = true;

        enum ButtonStyle { PRIMARY, SECONDARY, OUTLINE }

        RoundButton(String text, ButtonStyle style) {
            this.text = text;
            this.style = style;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Font f = UIManager.getFont("Button.font");
            if (f != null) setFont(f.deriveFont(Font.PLAIN, 12f));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            if (!visible) return new Dimension(0, 0);
            Font f = getFont();
            if (f == null) f = new Font(Font.DIALOG, Font.PLAIN, 12);
            FontMetrics fm = getFontMetrics(f);
            int w = fm.stringWidth(text) + JBUI.scale(24);
            int h = JBUI.scale(28);
            return new Dimension(w, h);
        }

        @Override
        public void setVisible(boolean v) {
            super.setVisible(v);
            this.visible = v;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!visible) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int r = JBUI.scale(6);

            Color bg, fg, border;
            switch (style) {
                case PRIMARY:
                    bg = hovered || pressed ? BTN_KEEP_HOVER : BTN_KEEP_BG;
                    fg = BTN_KEEP_FG;
                    border = null;
                    break;
                case SECONDARY:
                    bg = hovered || pressed ? BTN_REVERT_HOVER : BTN_REVERT_BG;
                    fg = BTN_REVERT_FG;
                    border = null;
                    break;
                default: // OUTLINE
                    bg = hovered || pressed ? BTN_VIEW_HOVER_BG : null;
                    fg = BTN_VIEW_FG;
                    border = BTN_VIEW_BORDER;
                    break;
            }

            if (bg != null) {
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            }
            if (border != null) {
                g2.setColor(border);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            }

            Font f = getFont();
            if (f == null) f = new Font(Font.DIALOG, Font.PLAIN, 12);
            g2.setFont(f);
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics(f);
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, tx, ty);
            g2.dispose();
        }
    }

    private JPanel createTasksCard() {
        JPanel card = new JPanel(new BorderLayout(0, 0));
        card.setOpaque(false);
        card.add(todoListPanel.getComponent(), BorderLayout.CENTER);
        return card;
    }

    private JPanel createChangesCard() {
        JPanel card = new JPanel(new BorderLayout(0, 0));
        card.setOpaque(false);

        // 分组标题行（N个文件 + 保留/撤销/查看变更）
        changesHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(HEADER_BG);
                g2.fillRect(0, 0, w, h);
                // 底部分隔线
                g2.setColor(HEADER_SEPARATOR);
                g2.drawLine(0, h - 1, w, h - 1);
                g2.dispose();
            }
        };
        changesHeader.setOpaque(false);
        changesHeader.setBorder(JBUI.Borders.empty(JBUI.scale(6), JBUI.scale(12), JBUI.scale(6), JBUI.scale(12)));
        changesHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // 左侧：折叠箭头 + N个文件（可点击折叠/展开）
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        leftPanel.setOpaque(false);

        // 折叠箭头（使用字段引用，方便后续切换图标）
        expandArrow = new JLabel(AllIcons.General.ArrowDown);
        leftPanel.add(expandArrow);

        changesCountLabel = new JBLabel("0 个文件");
        changesCountLabel.setFont(changesCountLabel.getFont().deriveFont(Font.PLAIN, 12f));
        changesCountLabel.setForeground(HEADER_TEXT);
        leftPanel.add(changesCountLabel);

        changesHeader.add(leftPanel, BorderLayout.WEST);

        // 点击标题行折叠/展开文件列表（点击右侧按钮时不触发）
        MouseAdapter toggleFilesList = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                Component deepest = SwingUtilities.getDeepestComponentAt(changesHeader, e.getX(), e.getY());
                if (deepest instanceof RoundButton) return;
                toggleFilesListCollapsed();
            }
        };
        changesHeader.addMouseListener(toggleFilesList);
        leftPanel.addMouseListener(toggleFilesList);
        expandArrow.addMouseListener(toggleFilesList);
        changesCountLabel.addMouseListener(toggleFilesList);

        // 右侧：操作按钮
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        rightPanel.setOpaque(false);

        keepAllBtn = new RoundButton("保留全部", RoundButton.ButtonStyle.PRIMARY);
        keepAllBtn.setToolTipText("保留所有待处理的文件（已保留/撤销的文件不受影响）");
        keepAllBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (fileChangeListPanel != null) fileChangeListPanel.keepAll();
            }
        });
        keepAllBtn.setVisible(false);
        rightPanel.add(keepAllBtn);

        revertAllBtn = new RoundButton("撤销全部", RoundButton.ButtonStyle.SECONDARY);
        revertAllBtn.setToolTipText("撤销所有待处理的文件（已保留/撤销的文件不受影响）");
        revertAllBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (fileChangeListPanel != null) fileChangeListPanel.revertAll();
            }
        });
        revertAllBtn.setVisible(false);
        rightPanel.add(revertAllBtn);

        changesHeader.add(rightPanel, BorderLayout.EAST);
        changesHeader.setVisible(false); // 初始隐藏，有变更时显示

        card.add(changesHeader, BorderLayout.NORTH);

        return card;
    }

    private void switchTab(ActiveTab tab) {
        if (activeTab == tab && !collapsed) {
            collapsed = true;
            contentPanel.setVisible(false);
        } else if (activeTab == tab && collapsed) {
            collapsed = false;
            contentPanel.setVisible(true);
        } else {
            activeTab = tab;
            collapsed = false;
            contentPanel.setVisible(true);
            CardLayout cl = (CardLayout) contentPanel.getLayout();
            cl.show(contentPanel, tab.name());
        }
        updateTabStyles();
        updateActionButtonsVisibility();
        changesCard.revalidate();
        contentPanel.revalidate();
        mainPanel.revalidate();
        fireLayoutChanged();
        mainPanel.repaint();
    }

    /** 切换文件列表内部折叠/展开 */
    private void toggleFilesListCollapsed() {
        filesListCollapsed = !filesListCollapsed;
        if (fileChangeListPanel != null) {
            fileChangeListPanel.getComponent().setVisible(!filesListCollapsed);
        }
        if (expandArrow != null) {
            expandArrow.setIcon(filesListCollapsed ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown);
        }
        fireLayoutChanged();
        changesCard.revalidate();
        contentPanel.revalidate();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    private void updateTabStyles() {
        styleTab(tasksTabBtn, activeTab == ActiveTab.TASKS && !collapsed);
        styleTab(changesTabBtn, activeTab == ActiveTab.CHANGES && !collapsed);
        tabBar.repaint();
    }

    @SuppressWarnings("unchecked")
    private void styleTab(JPanel btn, boolean isActive) {
        JBLabel titleLabel = (JBLabel) btn.getClientProperty("titleLabel");
        JBLabel countBadge = (JBLabel) btn.getClientProperty("countBadge");
        JLabel arrowLabel = (JLabel) btn.getClientProperty("arrowLabel");

        if (titleLabel != null) {
            titleLabel.setForeground(isActive ? TAB_TEXT_ACTIVE : TAB_TEXT_INACTIVE);
        }
        if (countBadge != null) {
            countBadge.setForeground(isActive ? TAB_TEXT_ACTIVE : TAB_TEXT_INACTIVE);
            Font f = titleLabel != null ? titleLabel.getFont() : UIManager.getFont("Label.font");
            if (f != null) countBadge.setFont(f.deriveFont(Font.PLAIN, 10f));
        }
        if (arrowLabel != null) {
            arrowLabel.setIcon(collapsed && ((isActive && activeTab == ActiveTab.TASKS) || (!isActive && activeTab == ActiveTab.CHANGES))
                    ? AllIcons.General.ArrowDown : AllIcons.General.ArrowUp);
        }
        btn.repaint();
    }

    // ==================== Todo 相关 ====================

    public void setTodos(List<TodoListPanel.TodoItem> items) {
        todoListPanel.setTodos(items);
        updateTasksBadge();
        updateVisibility();
        fireLayoutChanged();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void updateTodos(List<TodoListPanel.TodoItem> updates) {
        todoListPanel.updateTodos(updates);
        updateTasksBadge();
        updateVisibility();
        fireLayoutChanged();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void clearTodos() {
        todoListPanel.clear();
        setTabCount(tasksTabBtn, "");
        updateVisibility();
        fireLayoutChanged();
    }

    public void clearAll() {
        todoListPanel.clear();
        setTabCount(tasksTabBtn, "");
        if (fileChangeListPanel != null) {
            fileChangeListPanel.reset();
        }
        setTabCount(changesTabBtn, "");
        if (changesHeader != null) changesHeader.setVisible(false);
        if (keepAllBtn != null) keepAllBtn.setVisible(false);
        if (revertAllBtn != null) revertAllBtn.setVisible(false);
        activeTab = ActiveTab.TASKS;
        collapsed = false;
        filesListCollapsed = false;
        if (expandArrow != null) expandArrow.setIcon(AllIcons.General.ArrowDown);
        if (fileChangeListPanel != null) fileChangeListPanel.getComponent().setVisible(true);
        contentPanel.setVisible(true);
        CardLayout cl = (CardLayout) contentPanel.getLayout();
        cl.show(contentPanel, ActiveTab.TASKS.name());
        updateTabStyles();
        mainPanel.setVisible(false);
        changesCard.revalidate();
        contentPanel.revalidate();
        mainPanel.revalidate();
        fireLayoutChanged();
    }

    public String getTodoListText() { return todoListPanel.formatTodoList(); }
    public boolean hasTodos() { return todoListPanel.size() > 0; }
    public boolean isAllTodosCompleted() { return todoListPanel.isAllCompleted(); }

    @SuppressWarnings("unchecked")
    private void setTabCount(JPanel tabBtn, String text) {
        JBLabel countBadge = (JBLabel) tabBtn.getClientProperty("countBadge");
        if (countBadge != null) {
            countBadge.setText(text);
            countBadge.setVisible(text != null && !text.isEmpty());
            tabBtn.revalidate();
            tabBtn.repaint();
        }
    }

    private void updateTasksBadge() {
        int total = todoListPanel.size();
        if (total > 0) {
            int done = todoListPanel.getCompletedCount();
            setTabCount(tasksTabBtn, done + "/" + total);
        } else {
            setTabCount(tasksTabBtn, "");
        }
    }

    // ==================== Diff 相关 ====================

    public FileChangeListPanel getOrCreateChangePanel() {
        System.out.println("[DiffDebug] TaskDiffTabPanel.getOrCreateChangePanel called, fileChangeListPanel exists=" + (fileChangeListPanel != null));
        if (activeTab != ActiveTab.CHANGES) {
            switchTab(ActiveTab.CHANGES);
        } else if (collapsed) {
            switchTab(ActiveTab.CHANGES);
        }
        return fileChangeListPanel;
    }

    public void addChange(String filePath, String originalContent, String newContent) {
        getOrCreateChangePanel().addChange(filePath, originalContent, newContent);
        afterChange();
    }

    public void upsertChange(String filePath, String originalContent, String newContent) {
        getOrCreateChangePanel().upsertChange(filePath, originalContent, newContent);
        afterChange();
    }

    public void upsertChange(String filePath, String originalContent, String newContent, boolean isNewFile) {
        getOrCreateChangePanel().upsertChange(filePath, originalContent, newContent, isNewFile);
        afterChange();
    }

    private void afterChange() {
        updateChangesBadge();
        updateActionButtonsVisibility();
        updateVisibility();
        contentPanel.revalidate();
        changesCard.revalidate();
        mainPanel.revalidate();
        mainPanel.repaint();
        System.out.println("[DiffDebug] TaskDiffTabPanel.afterChange: mainPanel.visible=" + mainPanel.isVisible() +
            " size=" + mainPanel.getSize() + " preferredSize=" + mainPanel.getPreferredSize() +
            " activeTab=" + activeTab + " collapsed=" + collapsed);
        fireLayoutChanged();
    }

    public void resetChanges() {
        if (fileChangeListPanel != null) {
            fileChangeListPanel.reset();
        }
        setTabCount(changesTabBtn, "");
        if (changesHeader != null) changesHeader.setVisible(false);
        if (keepAllBtn != null) keepAllBtn.setVisible(false);
        if (revertAllBtn != null) revertAllBtn.setVisible(false);
        // 重置文件列表折叠状态为展开
        filesListCollapsed = false;
        if (expandArrow != null) expandArrow.setIcon(AllIcons.General.ArrowDown);
        if (fileChangeListPanel != null) fileChangeListPanel.getComponent().setVisible(true);
        changesCard.revalidate();
        updateVisibility();
        fireLayoutChanged();
    }

    public boolean isChangesResolved() {
        return fileChangeListPanel == null || fileChangeListPanel.isResolved();
    }

    public void expandAndFocusFirstPending() {
        if (fileChangeListPanel != null) {
            if (activeTab != ActiveTab.CHANGES || collapsed) {
                switchTab(ActiveTab.CHANGES);
            }
            fileChangeListPanel.expandAndFocusFirstPending();
        }
    }

    public void closeChangePanel() {
        if (activeTab == ActiveTab.CHANGES) {
            switchTab(ActiveTab.TASKS);
        }
        updateVisibility();
        fireLayoutChanged();
    }

    private void updateChangesBadge() {
        if (fileChangeListPanel != null && changesCountLabel != null) {
            int total = fileChangeListPanel.getChangeCount();
            if (total > 0) {
                setTabCount(changesTabBtn, "(" + total + ")");
                changesCountLabel.setText(total + " 个文件");
            } else {
                setTabCount(changesTabBtn, "");
            }
        }
    }

    private void updateActionButtonsVisibility() {
        boolean hasChanges = fileChangeListPanel != null && fileChangeListPanel.getChangeCount() > 0;
        boolean hasPending = fileChangeListPanel != null && fileChangeListPanel.hasPending();
        boolean show = hasChanges && activeTab == ActiveTab.CHANGES && !collapsed;
        if (changesHeader != null) changesHeader.setVisible(show);
        if (keepAllBtn != null) keepAllBtn.setVisible(show && hasPending);
        if (revertAllBtn != null) revertAllBtn.setVisible(show && hasPending);
    }

    private void fireLayoutChanged() {
        if (onLayoutChanged != null) onLayoutChanged.run();
    }

    // ==================== 可见性 ====================

    private void updateVisibility() {
        boolean hasTodos = todoListPanel.size() > 0;
        boolean hasChanges = fileChangeListPanel != null && fileChangeListPanel.getChangeCount() > 0;
        boolean shouldBeVisible = hasTodos || hasChanges;
        boolean wasVisible = mainPanel.isVisible();
        mainPanel.setVisible(shouldBeVisible);
        if (shouldBeVisible && !wasVisible) {
            System.out.println("[DiffDebug] TaskDiffTabPanel.updateVisibility: panel became visible, hasTodos=" + hasTodos + " hasChanges=" + hasChanges);
            fireLayoutChanged();
        }
    }

    public void show() { mainPanel.setVisible(true); }
    public void hide() { mainPanel.setVisible(false); }
    public JComponent getComponent() { return mainPanel; }
}
