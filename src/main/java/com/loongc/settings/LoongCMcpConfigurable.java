package com.loongc.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * LoongC → MCP 配置面板
 * Model Context Protocol 服务器管理
 */
public class LoongCMcpConfigurable implements Configurable {

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "MCP";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JBPanel<?> panel = new JBPanel<>(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(20, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 0, 8, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0;

        JBLabel title = new JBLabel("MCP (Model Context Protocol)");
        title.setFont(JBUI.Fonts.label(16).asBold());
        panel.add(title, gbc);

        gbc.gridy++;
        JBLabel desc = new JBLabel("<html><body style='width:400px'>" +
                "MCP 允许 LoongC 连接外部工具和数据源，扩展 AI 的能力边界。<br><br>" +
                "<font color='gray'>此功能即将上线，敬请期待。</font>" +
                "</body></html>");
        desc.setFont(JBUI.Fonts.label(13));
        panel.add(desc, gbc);

        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
    }

    @Override
    public void reset() {
    }
}
