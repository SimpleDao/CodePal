package com.codepal.settings;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * CP → General 通用设置面板
 * 包含会话持久化、向量库优化、智能内联补全等全局开关
 */
public class CPGeneralConfigurable implements Configurable {

//    private JBCheckBox persistenceBox;
//    private JBCheckBox vectorOptBox;
//    private JBCheckBox smartCompleteBox;
    private JBCheckBox openFileBox;

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "General";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JBPanel<?> root = new JBPanel<>(new GridBagLayout());
        root.setBorder(JBUI.Borders.empty(24, 24));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.weightx = 1.0;

        // 标题
        int row = 0;
        JBLabel title = new JBLabel("通用设置");
        title.setFont(JBUI.Fonts.label(16).asBold());
        gbc.gridy = row++;
        root.add(title, gbc);

        // 副标题
        JBLabel subtitle = new JBLabel("配置 CodePal 的全局行为与功能开关");
        subtitle.setFont(JBUI.Fonts.label(13));
        subtitle.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        gbc.gridy = row++;
        gbc.insets = new Insets(4, 0, 18, 0);
        root.add(subtitle, gbc);

//        // 分隔线
//        gbc.gridy = row++;
//        gbc.insets = new Insets(6, 0, 12, 0);
//        root.add(new JSeparator(), gbc);
//
//        // ── 选项 1：会话消息持久化 ──
//        gbc.gridy = row++;
//        gbc.insets = new Insets(12, 0, 4, 0);
//        persistenceBox = new JBCheckBox("启用会话消息持久化");
//        persistenceBox.setFont(JBUI.Fonts.label(13));
//        root.add(persistenceBox, gbc);

//        JBLabel persistenceDesc = new JBLabel("<html><body style='width:480px'>" +
//                "<font color='gray' size='2'>" +
//                "启用持久化后，您的聊天历史会自动保存到本地数据库文件中，" +
//                "方便下次打开 IDE 时恢复对话。数据库文件存储在：<br>" +
//                new File(PathManager.getConfigPath(), "codepal/chatdb.mv.db").getAbsolutePath() +
//                "</font></body></html>");
//        persistenceDesc.setFont(JBUI.Fonts.label(12));
//        gbc.gridy = row++;
//        gbc.insets = new Insets(0, 22, 16, 0);
//        root.add(persistenceDesc, gbc);

        // ── 选项 2：向量库优化 token ──
        gbc.gridy = row++;
        gbc.insets = new Insets(8, 0, 4, 0);
//        vectorOptBox = new JBCheckBox("启用向量库优化 token");
//        vectorOptBox.setFont(JBUI.Fonts.label(13));
//        root.add(vectorOptBox, gbc);

//        JBLabel vectorDesc = new JBLabel("<html><body style='width:480px'>" +
//                "<font color='gray' size='2'>" +
//                "开启后，CP 将使用本地向量库对项目代码进行语义索引，" +
//                "在向 AI 发送请求时自动筛选最相关的代码片段作为上下文，" +
//                "从而减少冗余 token 消耗并提升回答质量。" +
//                "</font></body></html>");
//        vectorDesc.setFont(JBUI.Fonts.label(12));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 22, 16, 0);
//        root.add(vectorDesc, gbc);

        // ── 选项 3：智能内联代码补全 ──
        gbc.gridy = row++;
        gbc.insets = new Insets(8, 0, 4, 0);
//        smartCompleteBox = new JBCheckBox("启用智能内联代码补全");
//        smartCompleteBox.setFont(JBUI.Fonts.label(13));
//        root.add(smartCompleteBox, gbc);

//        JBLabel completeDesc = new JBLabel("<html><body style='width:480px'>" +
//                "<font color='gray' size='2'>" +
//                "开启后，在您输入代码时会以 Ghost Text 的形式实时展示 AI 预测的下一段代码，" +
//                "按 Tab 键即可快速采纳补全建议，大幅提升编码效率。" +
//                "</font></body></html>");
//        completeDesc.setFont(JBUI.Fonts.label(12));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 22, 16, 0);
//      root.add(completeDesc, gbc);

        // ── 选项 4：编辑/新增文件时自动打开 ──
        gbc.gridy = row++;
        gbc.insets = new Insets(8, 0, 4, 0);
        openFileBox = new JBCheckBox("编辑或新增文件时自动在编辑器中打开");
        openFileBox.setFont(JBUI.Fonts.label(13));
        root.add(openFileBox, gbc);

        JBLabel openFileDesc = new JBLabel("<html><body style='width:480px'>" +
                "<font color='gray' size='2'>" +
                "开启后，AI 编辑或新建文件时会自动在编辑器区域打开该文件（已打开的不会重复打开）。" +
                "若您怀疑自动打开文件导致界面闪烁，可取消勾选以关闭此行为。" +
                "</font></body></html>");
        openFileDesc.setFont(JBUI.Fonts.label(12));
        gbc.gridy = row++;
        gbc.insets = new Insets(0, 22, 16, 0);
        root.add(openFileDesc, gbc);

        // 填充剩余空间
        gbc.gridy = row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        root.add(Box.createVerticalGlue(), gbc);

        return root;
    }

    @Override
    public boolean isModified() {
        CPSettings s = CPSettings.getInstance();
//        persistenceBox.isSelected() != s.isEnableMessagePersistence()
        return
               openFileBox.isSelected() != s.isOpenFileOnEdit();
    }

    @Override
    public void apply() {
        CPSettings s = CPSettings.getInstance();
//        s.setEnableMessagePersistence(persistenceBox.isSelected());
//        s.setEnableVectorOptimization(vectorOptBox.isSelected());
//        s.setEnableSmartAutoComplete(smartCompleteBox.isSelected());
        s.setOpenFileOnEdit(openFileBox.isSelected());
    }

    @Override
    public void reset() {
        CPSettings s = CPSettings.getInstance();
//        persistenceBox.setSelected(s.isEnableMessagePersistence());
//        vectorOptBox.setSelected(s.isEnableVectorOptimization());
//        smartCompleteBox.setSelected(s.isEnableSmartAutoComplete());
        openFileBox.setSelected(s.isOpenFileOnEdit());
    }
}
