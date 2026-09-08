package com.codepal.actions;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 反馈 / 报告问题 Action
 * 仓库为私有，故通过邮件渠道收集反馈：预填 IDE/插件版本与日志路径，用户可附上日志附件。
 * @author 水龙吟
 * @date 2026-07-18
 */
public class ReportIssueAction extends AnAction {

    /** 反馈接收邮箱，发布前请替换为真实地址 */
    private static final String FEEDBACK_EMAIL = "1838327804@qq.com";

    private static final String PLUGIN_ID = "com.loongc.plugin";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        String ideVersion = ApplicationInfo.getInstance().getFullVersion();
        String pluginVersion = getPluginVersion();
        String logPath = PathManager.getLogPath();

        String message = String.format(
                "感谢你的反馈！\n\n" +
                "提交问题时请附上以下信息以便复现：\n" +
                "  IDE 版本：%s\n" +
                "  插件版本：%s\n" +
                "  IDE 日志目录：%s\n\n" +
                "点击「确定」将打开邮件客户端，已为你预填版本信息；\n" +
                "如需排查问题，请把日志目录中的相关日志作为附件发送。",
                ideVersion, pluginVersion, logPath);

        int result = Messages.showOkCancelDialog(
                message,
                "CP 反馈 / 报告问题",
                "发送邮件反馈",
                "取消",
                Messages.getInformationIcon());

        if (result == Messages.OK) {
            String subject = encode("CP 反馈 [" + pluginVersion + "]");
            String body = encode(String.format(
                    "IDE 版本：%s\n插件版本：%s\nIDE 日志目录：%s\n\n问题描述：\n",
                    ideVersion, pluginVersion, logPath));
            BrowserUtil.browse("mailto:" + FEEDBACK_EMAIL + "?subject=" + subject + "&body=" + body);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String getPluginVersion() {
        try {
            var plugin = PluginManager.getPlugin(PluginId.getId(PLUGIN_ID));
            if (plugin != null) {
                return plugin.getVersion();
            }
        } catch (Exception ignored) {
            // 忽略，返回未知
        }
        return "未知";
    }
}
