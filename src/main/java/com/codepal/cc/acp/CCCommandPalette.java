package com.codepal.cc.acp;

import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * CC 命令面板（/ 触发），参考 auto-dev WorkspaceFileSearchPopup 模式。
 * JBPopup + SearchTextField + JBList，焦点在面板内，↑↓ 原生可用。
 * 每次调用 show() 重建 popup，解决 cancel 后无法再次 show 的问题。
 */
public class CCCommandPalette {

    private final Consumer<String> onSelect;
    private final Runnable onDismiss;
    private final List<CCCommand> allCommands;

    private JBPopup popup;
    private JBList<CCCommand> list;
    private DefaultListModel<CCCommand> listModel;
    private SearchTextField searchField;
    private JBLabel hintLabel;
    private List<CCCommand> filteredCommands;

    private static final int PANEL_WIDTH = 380;
    private static final int VISIBLE_ROWS = 8;
    private static final int ROW_HEIGHT = 28;

    public CCCommandPalette(Consumer<String> onSelect, Runnable onDismiss) {
        this.onSelect = onSelect;
        this.onDismiss = onDismiss;
        this.allCommands = new ArrayList<>(CCCommand.BUILT_IN);
        this.filteredCommands = new ArrayList<>(allCommands);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 构建（每次 show 都重建，因为 JBPopup.cancel() 后无法复用）
    // ═══════════════════════════════════════════════════════════════════════

    private void buildPopup() {
        // ── 搜索框 ──
        searchField = new SearchTextField();
        searchField.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { rebuildList(); }
            @Override public void removeUpdate(DocumentEvent e)  { rebuildList(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuildList(); }
        });
        // ↓：从搜索框跳到列表首项（参考 WorkspaceFileSearchPopup）
        searchField.getTextEditor().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && listModel != null && listModel.size() > 0) {
                    list.requestFocusInWindow();
                    list.setSelectedIndex(0);
                    e.consume();
                }
            }
        });

        // ── 列表 ──
        listModel = new DefaultListModel<>();
        list = new JBList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new CommandCellRenderer());
        list.setVisibleRowCount(VISIBLE_ROWS);
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER:
                        selectCurrent();
                        e.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        dismiss();
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        // 列表顶部再按 ↑：焦点回到搜索框
                        if (list.getSelectedIndex() == 0) {
                            searchField.getTextEditor().requestFocusInWindow();
                            e.consume();
                        }
                        break;
                }
            }
        });

        // ── 底部提示 ──
        hintLabel = new JBLabel("↑↓ 选择  Enter 确认  Esc 关闭");
        hintLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f));
        hintLabel.setForeground(UIUtil.getContextHelpForeground());
        hintLabel.setBorder(JBUI.Borders.empty(4, 10, 4, 10));
        hintLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        // ── 布局 ──
        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBackground(list.getBackground());
        content.setBorder(JBUI.Borders.empty(4, 4, 4, 4));
        content.add(searchField, BorderLayout.NORTH);
        content.add(new JScrollPane(list) {{
            setBorder(null);
            setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        }}, BorderLayout.CENTER);
        content.add(hintLabel, BorderLayout.SOUTH);

        // ── 弹窗 ──
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, searchField.getTextEditor())
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelOnWindowDeactivation(true)
                .setMovable(false)
                .setResizable(false)
                .createPopup();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 列表过滤
    // ═══════════════════════════════════════════════════════════════════════

    private void rebuildList() {
        String text = searchField.getText().trim();
        filteredCommands = allCommands.stream()
                .filter(c -> text.isEmpty()
                        || c.getCommand().toLowerCase().contains(text.toLowerCase())
                        || c.getDescription().toLowerCase().contains(text.toLowerCase()))
                .collect(Collectors.toList());
        listModel.clear();
        filteredCommands.forEach(listModel::addElement);
        if (!filteredCommands.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════════════

    /** 显示面板，紧贴输入框上方。每次调用重建 popup（cancel 后不可复用）。 */
    public void show(JTextArea inputField, String initialFilter) {
        if (allCommands.isEmpty()) return;
        if (popup != null) {
            popup.cancel();
        }

        buildPopup();
        searchField.setText(initialFilter);
        rebuildList();

        // 计算内容高度用于定位到输入框上方
        int rows = Math.max(Math.min(filteredCommands.size(), VISIBLE_ROWS), 1);
        int contentH = searchField.getPreferredSize().height + rows * ROW_HEIGHT + 50;
        Point offset = new Point(0, -(contentH + 4));
        popup.show(new RelativePoint(inputField, offset));

        SwingUtilities.invokeLater(() -> {
            searchField.getTextEditor().requestFocusInWindow();
            searchField.getTextEditor().selectAll();
        });
    }

    public boolean isVisible() {
        return popup != null && popup.isVisible();
    }

    public void hide() {
        if (popup != null) popup.cancel();
    }

    private void selectCurrent() {
        CCCommand cmd = list.getSelectedValue();
        if (cmd == null) return;
        popup.cancel();
        SwingUtilities.invokeLater(() -> onSelect.accept(cmd.getFullCommand() + " "));
    }

    private void dismiss() {
        popup.cancel();
        SwingUtilities.invokeLater(onDismiss);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 列表渲染
    // ═══════════════════════════════════════════════════════════════════════

    private static class CommandCellRenderer extends JPanel
            implements ListCellRenderer<CCCommand> {

        private final JLabel cmdLabel = new JLabel();
        private final JLabel descLabel = new JLabel();
        private final JLabel shortcutLabel = new JLabel();

        CommandCellRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(JBUI.Borders.empty(4, 10, 4, 10));
            add(cmdLabel, BorderLayout.WEST);
            add(descLabel, BorderLayout.CENTER);
            add(shortcutLabel, BorderLayout.EAST);

            shortcutLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f));
            shortcutLabel.setForeground(JBColor.GRAY);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends CCCommand> list, CCCommand cmd,
                int index, boolean isSelected, boolean cellHasFocus) {
            cmdLabel.setText(cmd.getFullCommand());
            cmdLabel.setFont(cmdLabel.getFont().deriveFont(Font.BOLD, 13f));
            cmdLabel.setForeground(isSelected
                    ? list.getSelectionForeground() : UIUtil.getListForeground());

            descLabel.setText(cmd.getDescription());
            descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 12f));
            descLabel.setForeground(isSelected
                    ? list.getSelectionForeground() : JBColor.GRAY);

            shortcutLabel.setText("↩");
            shortcutLabel.setVisible(isSelected);

            setBackground(isSelected
                    ? list.getSelectionBackground() : list.getBackground());
            setOpaque(true);
            return this;
        }
    }
}
