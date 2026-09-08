package com.codepal.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * CP 设置面板
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 分为两组：聊天模型配置 / 内联补全（Ghost Text）配置
 */
public class CPSettingsConfigurable implements Configurable {

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "CodePal";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JBPanel<?> panel = new JBPanel<>(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(24, 24));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.weightx = 1.0;

        // 标题
        int row = 0;
        JBLabel title = new JBLabel("CodePal");
        title.setFont(JBUI.Fonts.label(18).asBold());
        gbc.gridy = row++;
        panel.add(title, gbc);

        // 功能列表标题
//        JBLabel featuresTitle = new JBLabel("What do we have?");
//        featuresTitle.setFont(JBUI.Fonts.label(14).asBold());
//        gbc.gridy = row++;
//        gbc.insets = new Insets(6, 0, 8, 0);
//        panel.add(featuresTitle, gbc);

        // 功能列表
        String[] features = {
               "CPode - Free code assistant"
        };
        for (String f : features) {
            JBLabel fl = new JBLabel("  •  " + f);
            fl.setFont(JBUI.Fonts.label(13));
            gbc.gridy = row++;
            gbc.insets = new Insets(2, 0, 2, 0);
            panel.add(fl, gbc);
        }

        // 分隔线
        gbc.gridy = row++;
        gbc.insets = new Insets(12, 0, 12, 0);
        panel.add(new JSeparator(), gbc);

        // 填充剩余空间
        gbc.gridy = row;
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
