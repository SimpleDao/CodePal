package com.loongc.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.loongc.toolwindow.ChatPanel;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * @author 水龙吟
 * @date 2026-05-24
 *
 * LoongC 工具窗口工厂
 * titleActions 使用 AllIcons 原生图标，不覆盖 IDEA 原生头部按钮
 */
public class LoongCToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ChatPanel chatPanel = new ChatPanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(chatPanel, "", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);

        // 注册原生 titleActions：使用 AllIcons 图标，显示在工具窗口标题栏
        toolWindow.setTitleActions(Arrays.asList(
                new AnAction("清空对话", "清空当前对话历史", AllIcons.Actions.GC) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        chatPanel.clearChat();
                    }
                },
                new AnAction("LoongC 设置", "打开 LoongC 设置", AllIcons.General.Settings) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, "LoongC");
                    }
                }
        ));
    }
}
