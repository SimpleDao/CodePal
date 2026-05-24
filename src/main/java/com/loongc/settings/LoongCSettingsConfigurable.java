package com.loongc.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * LoongC 设置面板
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 分为两组：聊天模型配置 / 内联补全（Ghost Text）配置
 */
public class LoongCSettingsConfigurable implements Configurable {

    // ── 聊天模型 UI ───────────────────────────────
    private JTextField chatApiKeyField;
    private JTextField chatApiBaseField;
    private JTextField chatModelField;
    private JSpinner   chatMaxTokensSpinner;
    private JSpinner   chatTemperatureSpinner;

    // ── 内联补全 UI ───────────────────────────────
    private JCheckBox  enableAutoCompleteBox;
    private JTextField completionApiKeyField;
    private JTextField completionApiBaseField;
    private JTextField completionModelField;
    private JSpinner   completionMaxTokensSpinner;
    private JSpinner   completionTemperatureSpinner;
    private JSpinner   completionDelaySpinner;

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "LoongC";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        root.add(buildChatPanel());
        root.add(Box.createVerticalStrut(12));
        root.add(buildCompletionPanel());
        root.add(Box.createVerticalGlue());

        reset();

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(root, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildChatPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "聊天模型配置",
                TitledBorder.LEFT, TitledBorder.TOP));

        GridBagConstraints gbc = defaultGbc();
        int row = 0;
        chatApiKeyField        = addRow(panel, gbc, row++, "API Key:", new JTextField(35));
        chatApiBaseField       = addRow(panel, gbc, row++, "API Base URL:", new JTextField(35));
        chatModelField         = addRow(panel, gbc, row++, "模型:", new JTextField(35));
        chatMaxTokensSpinner   = addRow(panel, gbc, row++, "最大 Token:",
                new JSpinner(new SpinnerNumberModel(4096, 256, 32768, 256)));
        chatTemperatureSpinner = addRow(panel, gbc, row, "Temperature:",
                new JSpinner(new SpinnerNumberModel(0.7, 0.0, 2.0, 0.1)));
        return panel;
    }

    private JPanel buildCompletionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "内联补全配置（Ghost Text）",
                TitledBorder.LEFT, TitledBorder.TOP));

        GridBagConstraints gbc = defaultGbc();
        int row = 0;

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("启用内联补全:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        enableAutoCompleteBox = new JCheckBox();
        panel.add(enableAutoCompleteBox, gbc);
        row++;

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JLabel hint = new JLabel("<html><font color='gray' size='2'>" +
                "API Key / Base 留空则自动继承聊天模型配置</font></html>");
        panel.add(hint, gbc);
        gbc.gridwidth = 1;
        row++;

        completionApiKeyField        = addRow(panel, gbc, row++, "专用 API Key:", new JTextField(35));
        completionApiBaseField       = addRow(panel, gbc, row++, "专用 API Base:", new JTextField(35));
        completionModelField         = addRow(panel, gbc, row++, "补全模型:", new JTextField(35));
        completionMaxTokensSpinner   = addRow(panel, gbc, row++, "最大 Token:",
                new JSpinner(new SpinnerNumberModel(256, 64, 4096, 64)));
        completionTemperatureSpinner = addRow(panel, gbc, row++, "Temperature:",
                new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1)));
        completionDelaySpinner       = addRow(panel, gbc, row, "触发延迟 (ms):",
                new JSpinner(new SpinnerNumberModel(300, 100, 3000, 100)));
        return panel;
    }

    @Override
    public boolean isModified() {
        LoongCSettings s = LoongCSettings.getInstance();
        return !chatApiKeyField.getText().equals(s.getApiKey())
                || !chatApiBaseField.getText().equals(s.getApiBase())
                || !chatModelField.getText().equals(s.getModel())
                || !intVal(chatMaxTokensSpinner).equals(s.getMaxTokens())
                || !dblVal(chatTemperatureSpinner).equals(s.getTemperature())
                || enableAutoCompleteBox.isSelected() != s.isEnableAutoComplete()
                || !completionApiKeyField.getText().equals(s.getCompletionApiKey())
                || !completionApiBaseField.getText().equals(s.getCompletionApiBase())
                || !completionModelField.getText().equals(s.getCompletionModel())
                || !intVal(completionMaxTokensSpinner).equals(s.getCompletionMaxTokens())
                || !dblVal(completionTemperatureSpinner).equals(s.getCompletionTemperature())
                || !intVal(completionDelaySpinner).equals(s.getCompletionDelayMs());
    }

    @Override
    public void apply() {
        LoongCSettings s = LoongCSettings.getInstance();
        s.setApiKey(chatApiKeyField.getText().trim());
        s.setApiBase(chatApiBaseField.getText().trim());
        s.setModel(chatModelField.getText().trim());
        s.setMaxTokens(intVal(chatMaxTokensSpinner));
        s.setTemperature(dblVal(chatTemperatureSpinner));
        s.setEnableAutoComplete(enableAutoCompleteBox.isSelected());
        s.setCompletionApiKey(completionApiKeyField.getText().trim());
        s.setCompletionApiBase(completionApiBaseField.getText().trim());
        s.setCompletionModel(completionModelField.getText().trim());
        s.setCompletionMaxTokens(intVal(completionMaxTokensSpinner));
        s.setCompletionTemperature(dblVal(completionTemperatureSpinner));
        s.setCompletionDelayMs(intVal(completionDelaySpinner));
    }

    @Override
    public void reset() {
        LoongCSettings s = LoongCSettings.getInstance();
        chatApiKeyField.setText(s.getApiKey());
        chatApiBaseField.setText(s.getApiBase());
        chatModelField.setText(s.getModel());
        chatMaxTokensSpinner.setValue(s.getMaxTokens());
        chatTemperatureSpinner.setValue(s.getTemperature());
        enableAutoCompleteBox.setSelected(s.isEnableAutoComplete());
        completionApiKeyField.setText(s.getCompletionApiKey());
        completionApiBaseField.setText(s.getCompletionApiBase());
        completionModelField.setText(s.getCompletionModel());
        completionMaxTokensSpinner.setValue(s.getCompletionMaxTokens());
        completionTemperatureSpinner.setValue(s.getCompletionTemperature());
        completionDelaySpinner.setValue(s.getCompletionDelayMs());
    }

    // ── 辅助方法 ──────────────────────────────────
    private GridBagConstraints defaultGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    @SuppressWarnings("unchecked")
    private <T extends JComponent> T addRow(JPanel panel, GridBagConstraints gbc,
                                            int row, String label, T field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(field, gbc);
        return field;
    }

    private Integer intVal(JSpinner spinner) {
        Object v = spinner.getValue();
        return v instanceof Integer ? (Integer) v : ((Number) v).intValue();
    }

    private Double dblVal(JSpinner spinner) {
        Object v = spinner.getValue();
        return v instanceof Double ? (Double) v : ((Number) v).doubleValue();
    }
}
