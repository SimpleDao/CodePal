package com.codepal.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import javax.swing.JComponent;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.codepal.mcp.McpService;
import org.jetbrains.annotations.NotNull;

/**
 * @author 水龙吟
 * @date 2026-05-24
 *
 * CodePal 工具窗口工厂。
 *
 * 多标签架构：
 * - 单 Content，IDEA 原生标签条不显示
 * - 自定义 tabBarPanel（由 {@link TabManager} 管理）作为唯一可见标签条
 * - CardLayout 在标签间切换，每个标签 = 独立 ChatPanel 实例，天然隔离
 * - 标题栏 action 路由到「当前激活标签」的 ChatPanel
 *
 * <p>★ 根上修复「标题栏按钮偶发失效」：
 * {@code createToolWindowContent} 会被 IDEA 在「插件热重载 / 工具窗口 dispose 后重建」时重复调用。
 * 旧做法在已有 Content 时直接 return，但 IDEA 重建 ToolWindow 时可能已清空旧 titleActions，
 * 导致 return 后按钮再也不会被重新注册 → 点击毫无反应。
 * <p>根上做法：
 * <ol>
 *   <li>把 TabManager 缓存在 Content 的 userData 上（Content extends UserDataHolder），
 *       重复调用时取出复用，不 new 第二套面板</li>
 *   <li>无论首次还是重复调用，都执行 {@code setTitleActions} 重新注册标题栏按钮，
 *       确保按钮始终绑定到「屏幕可见的」TabManager</li>
 * </ol>
 */
public class CPToolWindowFactory implements ToolWindowFactory {

    /** Content userData key：把 TabManager 实例与 Content 绑定（同生命周期）。 */
    private static final Key<TabManager> TAB_MANAGER_KEY = Key.create("CP.tabManager");

    /** MCP 服务全局只启动一次（多标签 + 多窗口共用）。 */
    private static boolean mcpStarted = false;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager cm = toolWindow.getContentManager();

        // ★ 尝试从已有 Content 中取回 TabManager（重复调用场景：热重载 / dispose 重建）
        TabManager tabManager = null;
        if (cm.getContentCount() > 0) {
            Content existing = cm.getContent(0);
            if (existing != null) {
                tabManager = existing.getUserData(TAB_MANAGER_KEY);
            }
        }

        // 首次调用：创建 TabManager + 第一个 ChatPanel + Content
        if (tabManager == null) {
            ChatPanel firstPanel = new ChatPanel(project, true);
            tabManager = new TabManager(project);
            JComponent container = tabManager.init(firstPanel);

            ContentFactory contentFactory = ContentFactory.getInstance();
            Content content = contentFactory.createContent(container, "", false);
            content.setCloseable(false);
            // ★ 把 TabManager 绑定到 Content 上，重复调用时复用同一实例
            content.putUserData(TAB_MANAGER_KEY, tabManager);
            cm.addContent(content);
        }

        // ★ 无论首次还是重复调用，都重新注册标题栏按钮。
        //   IDEA 重建 ToolWindow 时可能已清空旧 titleActions，不重新注册 → 按钮失效。
        final TabManager tm = tabManager;
        toolWindow.setTitleActions(java.util.Arrays.asList(
                new AnAction("新建聊天", "新建一个会话标签", AllIcons.General.Add) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        tm.createTab(null);
                    }
                },
                new AnAction("历史会话", "查看历史会话列表", AllIcons.Vcs.History) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        try {
                            ChatPanel panel = tm.getActiveChatPanel();
                            if (panel != null) panel.showHistoryPanel();
                        } catch (Exception ex) {
                            Logger.getInstance(CPToolWindowFactory.class).warn("打开历史面板失败", ex);
                        }
                    }
                },
                new AnAction("清空对话", "清空当前对话历史", AllIcons.Actions.GC) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        try {
                            ChatPanel panel = tm.getActiveChatPanel();
                            if (panel != null) panel.clearChat();
                        } catch (Exception ex) {
                            Logger.getInstance(CPToolWindowFactory.class).warn("清空对话失败", ex);
                        }
                    }
                },
                new AnAction("CodePal 设置", "打开 CodePal 设置", AllIcons.General.Settings) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, "CodePal");
                    }
                }
        ));

        // MCP 服务只需启动一次（多标签共享）
        if (!mcpStarted) {
            mcpStarted = true;
            ApplicationManager.getApplication().executeOnPooledThread(McpService::startAllEnabled);
        }
    }
}
