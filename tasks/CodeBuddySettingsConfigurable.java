package com.loongc.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * CodeBuddy 设置配置页面
 */
public class CodeBuddySettingsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JTextField apiKeyField;
    private JTextField apiBaseField;
    private JTextField modelField;
    private JCheckBox enableAutoCompleteBox;
    private JSpinner maxTokensSpinner;
    private JSpinner temperatureSpinner;

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "CodeBuddy";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // API Key
        gbc.gridx = 0;
        gbc.gridy = 0;
        mainPanel.add(new JLabel("DeepSeek API Key:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        apiKeyField = new JTextField(30);
        mainPanel.add(apiKeyField, gbc);

        // API Base
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        mainPanel.add(new JLabel("API Base URL:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        apiBaseField = new JTextField(30);
        mainPanel.add(apiBaseField, gbc);

        // Model
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        mainPanel.add(new JLabel("模型:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        modelField = new JTextField(30);
        mainPanel.add(modelField, gbc);

        // Auto Complete
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        mainPanel.add(new JLabel("启用自动补全:"), gbc);
        gbc.gridx = 1;
        enableAutoCompleteBox = new JCheckBox();
        mainPanel.add(enableAutoCompleteBox, gbc);

        // Max Tokens
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        mainPanel.add(new JLabel("最大 Token 数:"), gbc);
        gbc.gridx = 1;
        SpinnerNumberModel tokensModel = new SpinnerNumberModel(4096, 256, 8192, 256);
        maxTokensSpinner = new JSpinner(tokensModel);
        mainPanel.add(maxTokensSpinner, gbc);

        // Temperature
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.weightx = 0;
        mainPanel.add(new JLabel("Temperature:"), gbc);
        gbc.gridx = 1;
        SpinnerNumberModel tempModel = new SpinnerNumberModel(0.7, 0.0, 2.0, 0.1);
        temperatureSpinner = new JSpinner(tempModel);
        mainPanel.add(temperatureSpinner, gbc);

        // 填充剩余空间
        gbc.gridy = 6;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        mainPanel.add(new JPanel(), gbc);

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        CodeBuddySettings settings = CodeBuddySettings.getInstance();
        return !apiKeyField.getText().equals(settings.getApiKey())
            || !apiBaseField.getText().equals(settings.getApiBase())
            || !modelField.getText().equals(settings.getModel())
            || enableAutoCompleteBox.isSelected() != settings.isEnableAutoComplete()
            || (Integer) maxTokensSpinner.getValue() != settings.getMaxTokens()
            || (Double) temperatureSpinner.getValue() != settings.getTemperature();
    }

    @Override
    public void apply() {
        CodeBuddySettings settings = CodeBuddySettings.getInstance();
        settings.setApiKey(apiKeyField.getText().trim());
        settings.setApiBase(apiBaseField.getText().trim());
        settings.setModel(modelField.getText().trim());
        settings.setEnableAutoComplete(enableAutoCompleteBox.isSelected());
        settings.setMaxTokens((Integer) maxTokensSpinner.getValue());
        settings.setTemperature((Double) temperatureSpinner.getValue());
    }

    @Override
    public void reset() {
        CodeBuddySettings settings = CodeBuddySettings.getInstance();
        apiKeyField.setText(settings.getApiKey());
        apiBaseField.setText(settings.getApiBase());
        modelField.setText(settings.getModel());
        enableAutoCompleteBox.setSelected(settings.isEnableAutoComplete());
        maxTokensSpinner.setValue(settings.getMaxTokens());
        temperatureSpinner.setValue(settings.getTemperature());
    }
}
