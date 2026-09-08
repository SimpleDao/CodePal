package com.codepal.toolwindow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 多标签管理器：自定义 tabBarPanel 作为唯一可见标签条，CardLayout 切换独立 ChatPanel 实例。
 * <p>
 * 架构：
 * <ul>
 *   <li>单 Content（IDEA 原生标签条不显示）</li>
 *   <li>每个标签 = 一个独立的 {@link ChatPanel} 实例（独立 WebView/ConversationManager/StreamRenderController）</li>
 *   <li>CardLayout 在标签间切换，每个 ChatPanel 完全隔离，支持并发流式聊天</li>
 *   <li>自定义 tabBarPanel 作为唯一可见标签条</li>
 * </ul>
 *
 * @author CodePal Tab Isolation
 */
public class TabManager {

    private final Project project;

    /** tabId → ChatPanel 实例 */
    private final Map<String, ChatPanel> panelsById = new LinkedHashMap<>();
    /** tabId → 标题 */
    private final Map<String, String> titlesById = new LinkedHashMap<>();
    /** 当前激活的 tabId */
    private String activeTabId = null;

    /** 主容器 */
    private JPanel container;
    /** 标签栏 */
    private JPanel tabBarPanel;
    private JPanel tabStrip;
    /** 卡片面板（CardLayout 切换 ChatPanel） */
    private JPanel cardsPanel;
    private CardLayout cardLayout;

    public TabManager(Project project) {
        this.project = project;
    }

    /**
     * 初始化：构建容器 + 创建第一个标签。
     * @param firstPanel 首个 ChatPanel 实例（由 CPToolWindowFactory 创建并传入，恢复上次会话）
     * @return 主容器组件（放入 Content）
     */
    public JComponent init(ChatPanel firstPanel) {
        // 构建容器
        container = new JPanel(new BorderLayout());
        container.setBackground(getBgColor());

        // 标签栏
        buildTabBar();
        container.add(tabBarPanel, BorderLayout.NORTH);

        // 卡片面板
        cardLayout = new CardLayout();
        cardsPanel = new JPanel(cardLayout);
        cardsPanel.setOpaque(false);
        container.add(cardsPanel, BorderLayout.CENTER);

        // 创建第一个标签（复用传入的 ChatPanel，恢复上次会话）
        createTab(firstPanel);

        return container;
    }

    /**
     * 创建新标签。
     * @param existingChatPanel 若不为 null，则复用该实例；为 null 则新建一个 ChatPanel（新会话）。
     * @return 新标签的 tabId
     */
    public String createTab(ChatPanel existingChatPanel) {
        final String tabId = "tab_" + UUID.randomUUID().toString().substring(0, 8);

        ChatPanel chatPanel = existingChatPanel != null
                ? existingChatPanel
                : new ChatPanel(project, false); // false = 不恢复上次会话，创建新空会话

        // 注册 TabCallbacks：ChatPanel 内部会话变化时通知 TabManager 更新标题
        chatPanel.setTabCallbacks(new ChatPanel.TabCallbacks() {
            @Override
            public void onSessionTitleChanged(String title) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    // ★ 防止已关闭的标签通过异步回调重新加回标签栏
                    if (title != null && !title.isEmpty() && panelsById.containsKey(tabId)) {
                        titlesById.put(tabId, title);
                        refreshTabBar();
                    }
                });
            }

            @Override
            public void onOpenHistorySession(String sessionId, String title) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    openHistorySessionInNewTab(sessionId, title);
                });
            }
        });

        panelsById.put(tabId, chatPanel);
        titlesById.put(tabId, "新会话");
        cardsPanel.add(chatPanel, tabId);

        activateTab(tabId);
        return tabId;
    }

    /**
     * 激活指定标签。
     */
    public void activateTab(String tabId) {
        if (tabId == null || !panelsById.containsKey(tabId)) return;
        if (tabId.equals(activeTabId)) return;

        activeTabId = tabId;
        cardLayout.show(cardsPanel, tabId);
        refreshTabBar();
    }

    /**
     * 关闭指定标签。
     */
    public void closeTab(String tabId) {
        if (tabId == null || !panelsById.containsKey(tabId)) return;

        // 找邻居
        java.util.List<String> ids = new java.util.ArrayList<>(panelsById.keySet());
        int idx = ids.indexOf(tabId);
        String neighbor = null;
        if (idx + 1 < ids.size()) neighbor = ids.get(idx + 1);
        else if (idx - 1 >= 0) neighbor = ids.get(idx - 1);

        // 先从 Map 移除（防止异步回调重新加回）
        ChatPanel panel = panelsById.remove(tabId);
        titlesById.remove(tabId);

        // ★ 先切换到邻居标签（让邻居的 ChatPanel 显示出来），再移除旧面板
        //   避免先 remove+dispose 导致聊天窗空白
        if (tabId.equals(activeTabId)) {
            activeTabId = null;
            if (neighbor != null) {
                activateTab(neighbor);
            } else if (!panelsById.isEmpty()) {
                activateTab(panelsById.keySet().iterator().next());
            } else {
                createTab(null);
            }
        } else {
            refreshTabBar();
        }

        // 最后安全地移除和销毁旧面板
        if (panel != null) {
            cardsPanel.remove(panel);
            cardsPanel.revalidate();
            cardsPanel.repaint();
            panel.dispose();
        }
    }

    /**
     * 获取当前激活的 ChatPanel。
     */
    public ChatPanel getActiveChatPanel() {
        return activeTabId != null ? panelsById.get(activeTabId) : null;
    }

    /**
     * 在新标签中打开历史会话（不覆盖当前标签）。
     */
    private void openHistorySessionInNewTab(String sessionId, String title) {
        // ★ 同一会话已打开过 → 直接激活已有标签，不再重复开新标签
        String existingTabId = findTabIdBySessionId(sessionId);
        if (existingTabId != null) {
            activateTab(existingTabId);
            return;
        }

        // 创建新标签（新 ChatPanel 实例）
        String newTabId = createTab(null);
        // 让新 ChatPanel 加载指定会话
        ChatPanel panel = panelsById.get(newTabId);
        if (panel != null) {
            panel.loadHistorySession(sessionId);
            titlesById.put(newTabId, title != null ? title : "未命名会话");
            refreshTabBar();
        }
    }

    /**
     * 按 sessionId 查找已打开的标签 tabId。
     * 遍历 panelsById，取每个 ChatPanel 当前绑定的会话 ID 进行比对。
     */
    private String findTabIdBySessionId(String sessionId) {
        if (sessionId == null) return null;
        for (Map.Entry<String, ChatPanel> entry : panelsById.entrySet()) {
            ChatPanel p = entry.getValue();
            if (p != null && sessionId.equals(p.getChatSessionManager().getCurrentSessionId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 标签栏 UI
    // ─────────────────────────────────────────────────────────────────────

    private void buildTabBar() {
        tabBarPanel = new JPanel(new BorderLayout());
        tabBarPanel.setOpaque(true);
        tabBarPanel.setBackground(getBgColor());
        tabBarPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        tabStrip.setOpaque(false);
        tabBarPanel.add(tabStrip, BorderLayout.CENTER);
    }

    private void refreshTabBar() {
        if (tabStrip == null) return;
        tabStrip.removeAll();
        for (Map.Entry<String, String> entry : titlesById.entrySet()) {
            String id = entry.getKey();
            String title = entry.getValue();
            boolean active = id.equals(activeTabId);
            tabStrip.add(buildSingleTab(id, title, active));
        }
        tabStrip.revalidate();
        tabStrip.repaint();
        if (tabBarPanel != null) {
            tabBarPanel.revalidate();
            tabBarPanel.repaint();
        }
    }

    private JComponent buildSingleTab(String tabId, String title, boolean active) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        tab.setOpaque(true);
        tab.setBackground(getBgColor());

        // 激活态底部下划线
        if (active) {
            tab.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, getUnderlineColor()));
        } else {
            tab.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
        }

        // 标题
        JLabel label = new JLabel(truncateForTab(title));
        label.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
        label.setFont(UIUtil.getLabelFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                activateTab(tabId);
            }
        });
        tab.add(label);

        // 关闭按钮
        JLabel close = new JLabel("×");
        close.setFont(UIUtil.getLabelFont().deriveFont(14f));
        close.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                closeTab(tabId);
                e.consume();
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                close.setForeground(UIUtil.getLabelForeground());
            }
            @Override
            public void mouseExited(MouseEvent e) {
                close.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
            }
        });
        tab.add(close);

        return tab;
    }

    private static Color getBgColor() {
        return UIUtil.isUnderDarcula() ? new Color(0x2B2B2B) : new Color(0xF5F5F5);
    }

    private static Color getUnderlineColor() {
        return UIUtil.isUnderDarcula() ? Color.WHITE : new Color(0x1E88E5);
    }

    private static String truncateForTab(String t) {
        if (t == null) return "";
        final int max = 18;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    /**
     * 释放所有资源。
     */
    public void dispose() {
        for (ChatPanel panel : panelsById.values()) {
            panel.dispose();
        }
        panelsById.clear();
        titlesById.clear();
        activeTabId = null;
    }
}
