package com.loongc.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.loongc.model.ModelConfig;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;


/**
 * LoongC → Model 配置面板
 * 聊天模型配置 + 内联补全（Ghost Text）配置
 */
public class LoongCModelConfigurable implements Configurable {

    // ── 内存编辑副本 ───────────────────────────────
    private List<ModelConfig> editingChatModels = new ArrayList<>();
    private List<ModelConfig> editingCompletionModels = new ArrayList<>();
    private int editingChatIdx = 0;
    private int editingCompletionIdx = 0;

    // ── 聊天模型 UI ───────────────────────────────
    private JComboBox<ModelConfig> chatModelCombo;
    private JButton                chatAddBtn;
    private JButton                chatDelBtn;
    private JBTextField            chatNameField;
    private JBTextField            chatApiKeyField;
    private JBTextField            chatApiBaseField;
    private JSpinner               chatMaxTokensSpinner;
    private JSpinner               chatTemperatureSpinner;

    // ── 内联补全 UI ───────────────────────────────
    private JComboBox<ModelConfig> compModelCombo;
    private JButton                compAddBtn;
    private JButton                compDelBtn;
    private JBTextField            compNameField;
    private JBTextField            compApiKeyField;
    private JBTextField            compApiBaseField;
    private JBTextField            compModelIdField;
    private JSpinner               compMaxTokensSpinner;
    private JSpinner               compTemperatureSpinner;
    private JSpinner               compDelaySpinner;

    private boolean suppressComboEvent = false;

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "Model";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JBPanel<?> root = new JBPanel<>(null);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(JBUI.Borders.empty(12, 16));

        root.add(buildChatPanel());
        root.add(Box.createVerticalStrut(16));
        root.add(buildCompletionPanel());
        root.add(Box.createVerticalGlue());

        reset();

        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout());
        wrapper.add(root, BorderLayout.NORTH);
        return wrapper;
    }

    // ═══════════════════════════════════════════════════════════
    //  聊天模型区
    // ═══════════════════════════════════════════════════════════

    private JBPanel<?> buildChatPanel() {
        JBPanel<?> panel = new JBPanel<>(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "聊天模型配置",
                TitledBorder.LEFT, TitledBorder.TOP));

        GridBagConstraints gbc = defaultGbc();
        int row = 0;

        // ── 模型选择行 ──
        JBLabel modelLabel = new JBLabel("当前模型:");
        chatModelCombo = new JComboBox<>();
        chatModelCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ModelConfig) {
                    setText(((ModelConfig) value).getName());
                }
                return this;
            }
        });
        chatModelCombo.addActionListener(e -> {
            if (suppressComboEvent) return;
            int idx = chatModelCombo.getSelectedIndex();
            if (idx >= 0) {
                syncCurrentChatModel();     // 保存旧模型编辑内容
                editingChatIdx = idx;
                loadChatModelToForm(idx);   // 加载新模型
            }
        });

        chatAddBtn = new JButton("＋ 新增");
        chatAddBtn.setFocusable(false);
        chatAddBtn.addActionListener(e -> addChatModel());

        chatDelBtn = new JButton("－ 删除");
        chatDelBtn.setFocusable(false);
        chatDelBtn.addActionListener(e -> deleteChatModel());

        JBPanel<?> selectRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectRow.setOpaque(false);
        selectRow.add(modelLabel);
        selectRow.add(chatModelCombo);
        selectRow.add(chatAddBtn);
        selectRow.add(chatDelBtn);

        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 1.0;
        panel.add(selectRow, gbc);
        gbc.gridwidth = 1;

        // ── 参数表单 ──
        chatNameField         = addRow(panel, gbc, row++, "模型名称:", new JBTextField(35));
        chatApiKeyField       = addRow(panel, gbc, row++, "API Key:", new JBTextField(35));
        chatApiBaseField      = addRow(panel, gbc, row++, "API Base URL:", new JBTextField(35));
        chatMaxTokensSpinner  = addRow(panel, gbc, row++, "最大 Token:",
                new JSpinner(new SpinnerNumberModel(4096, 256, 32768, 256)));
        chatTemperatureSpinner = addRow(panel, gbc, row, "Temperature:",
                new JSpinner(new SpinnerNumberModel(0.7, 0.0, 2.0, 0.1)));
        return panel;
    }

    // ═══════════════════════════════════════════════════════════
    //  补全模型区
    // ═══════════════════════════════════════════════════════════

    private JBPanel<?> buildCompletionPanel() {
        JBPanel<?> panel = new JBPanel<>(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "内联补全配置（Ghost Text）",
                TitledBorder.LEFT, TitledBorder.TOP));

        GridBagConstraints gbc = defaultGbc();
        int row = 0;

        // ── 继承提示 ──
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JBLabel hint = new JBLabel("<html><font color='gray' size='2'>" +
                "专用 API Key / Base 留空则自动继承当前选中的聊天模型配置</font></html>");
        panel.add(hint, gbc);
        gbc.gridwidth = 1;
        row++;

        // ── 模型选择行 ──
        JBLabel modelLabel = new JBLabel("当前模型:");
        compModelCombo = new JComboBox<>();
        compModelCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ModelConfig) {
                    setText(((ModelConfig) value).getName());
                }
                return this;
            }
        });
        compModelCombo.addActionListener(e -> {
            if (suppressComboEvent) return;
            int idx = compModelCombo.getSelectedIndex();
            if (idx >= 0) {
                syncCurrentCompletionModel();
                editingCompletionIdx = idx;
                loadCompletionModelToForm(idx);
            }
        });

        compAddBtn = new JButton("＋ 新增");
        compAddBtn.setFocusable(false);
        compAddBtn.addActionListener(e -> addCompletionModel());

        compDelBtn = new JButton("－ 删除");
        compDelBtn.setFocusable(false);
        compDelBtn.addActionListener(e -> deleteCompletionModel());

        JBPanel<?> selectRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectRow.setOpaque(false);
        selectRow.add(modelLabel);
        selectRow.add(compModelCombo);
        selectRow.add(compAddBtn);
        selectRow.add(compDelBtn);

        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 1.0;
        panel.add(selectRow, gbc);
        gbc.gridwidth = 1;

        // ── 参数表单 ──
        compNameField         = addRow(panel, gbc, row++, "模型名称:", new JBTextField(35));
        compApiKeyField       = addRow(panel, gbc, row++, "专用 API Key:", new JBTextField(35));
        compApiBaseField      = addRow(panel, gbc, row++, "专用 API Base:", new JBTextField(35));
        compMaxTokensSpinner  = addRow(panel, gbc, row++, "最大 Token:",
                new JSpinner(new SpinnerNumberModel(256, 64, 4096, 64)));
        compTemperatureSpinner = addRow(panel, gbc, row++, "Temperature:",
                new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1)));
        compDelaySpinner      = addRow(panel, gbc, row, "触发延迟 (ms):",
                new JSpinner(new SpinnerNumberModel(600, 100, 3000, 100)));
        return panel;
    }

    // ═══════════════════════════════════════════════════════════
    //  数据同步：表单 ↔ 内存模型
    // ═══════════════════════════════════════════════════════════

    private void syncCurrentChatModel() {
        if (editingChatIdx < 0 || editingChatIdx >= editingChatModels.size()) return;
        ModelConfig m = editingChatModels.get(editingChatIdx);
        m.setName(chatNameField.getText().trim());
        m.setApiKey(chatApiKeyField.getText().trim());
        m.setApiBase(chatApiBaseField.getText().trim());
        m.setMaxTokens(intVal(chatMaxTokensSpinner));
        m.setTemperature(dblVal(chatTemperatureSpinner));
        // 刷新 combo 显示（名称可能已改）
        chatModelCombo.repaint();
    }

    private void syncCurrentCompletionModel() {
        if (editingCompletionIdx < 0 || editingCompletionIdx >= editingCompletionModels.size()) return;
        ModelConfig m = editingCompletionModels.get(editingCompletionIdx);
        m.setName(compNameField.getText().trim());
        m.setApiKey(compApiKeyField.getText().trim());
        m.setApiBase(compApiBaseField.getText().trim());
        m.setMaxTokens(intVal(compMaxTokensSpinner));
        m.setTemperature(dblVal(compTemperatureSpinner));
        compModelCombo.repaint();
    }

    private void loadChatModelToForm(int idx) {
        if (idx < 0 || idx >= editingChatModels.size()) return;
        ModelConfig m = editingChatModels.get(idx);
        chatNameField.setText(m.getName());
        chatApiKeyField.setText(m.getApiKey());
        chatApiBaseField.setText(m.getApiBase());
        chatMaxTokensSpinner.setValue(m.getMaxTokens());
        chatTemperatureSpinner.setValue(m.getTemperature());
    }

    private void loadCompletionModelToForm(int idx) {
        if (idx < 0 || idx >= editingCompletionModels.size()) return;
        ModelConfig m = editingCompletionModels.get(idx);
        compNameField.setText(m.getName());
        compApiKeyField.setText(m.getApiKey());
        compApiBaseField.setText(m.getApiBase());
        compMaxTokensSpinner.setValue(m.getMaxTokens());
        compTemperatureSpinner.setValue(m.getTemperature());
    }

    // ═══════════════════════════════════════════════════════════
    //  新增 / 删除模型
    // ═══════════════════════════════════════════════════════════

    private void addChatModel() {
        syncCurrentChatModel();
        editingChatModels.add(new ModelConfig("", "",
                "https://api.deepseek.com",  4096, 0.7));
        editingChatIdx = editingChatModels.size() - 1;
        refreshChatCombo();
    }

    private void deleteChatModel() {
        if (editingChatModels.size() <= 1) {
            // 至少保留一个默认模型
            ModelConfig m = editingChatModels.get(0);
            m.setName("deepseek-v4-flash");
            m.setApiKey("");
            m.setApiBase("https://api.deepseek.com");
            m.setMaxTokens(4096);
            m.setTemperature(0.7);
            editingChatIdx = 0;
            refreshChatCombo();
            return;
        }
        editingChatModels.remove(editingChatIdx);
        editingChatIdx = Math.min(editingChatIdx, editingChatModels.size() - 1);
        refreshChatCombo();
    }

    private void addCompletionModel() {
        syncCurrentCompletionModel();
        editingCompletionModels.add(new ModelConfig("", "",
                "", 256, 0.0));
        editingCompletionIdx = editingCompletionModels.size() - 1;
        refreshCompCombo();
    }

    private void deleteCompletionModel() {
        if (editingCompletionModels.size() <= 1) {
            ModelConfig m = editingCompletionModels.get(0);
            m.setName("");
            m.setApiKey("");
            m.setApiBase("");
            m.setMaxTokens(256);
            m.setTemperature(0.0);
            editingCompletionIdx = 0;
            refreshCompCombo();
            return;
        }
        editingCompletionModels.remove(editingCompletionIdx);
        editingCompletionIdx = Math.min(editingCompletionIdx, editingCompletionModels.size() - 1);
        refreshCompCombo();
    }

    private void refreshChatCombo() {
        suppressComboEvent = true;
        chatModelCombo.removeAllItems();
        for (ModelConfig m : editingChatModels) {
            chatModelCombo.addItem(m);
        }
        if (editingChatIdx >= 0 && editingChatIdx < editingChatModels.size()) {
            chatModelCombo.setSelectedIndex(editingChatIdx);
        }
        suppressComboEvent = false;
        loadChatModelToForm(editingChatIdx);
    }

    private void refreshCompCombo() {
        suppressComboEvent = true;
        compModelCombo.removeAllItems();
        for (ModelConfig m : editingCompletionModels) {
            compModelCombo.addItem(m);
        }
        if (editingCompletionIdx >= 0 && editingCompletionIdx < editingCompletionModels.size()) {
            compModelCombo.setSelectedIndex(editingCompletionIdx);
        }
        suppressComboEvent = false;
        loadCompletionModelToForm(editingCompletionIdx);
    }

    // ═══════════════════════════════════════════════════════════
    //  Configurable 标准方法
    // ═══════════════════════════════════════════════════════════

    @Override
    public boolean isModified() {
        syncCurrentChatModel();
        syncCurrentCompletionModel();

        LoongCSettings s = LoongCSettings.getInstance();
        if (!listEquals(editingChatModels, s.getChatModels())) return true;
        if (!listEquals(editingCompletionModels, s.getCompletionModels())) return true;
        if (editingChatIdx != s.getCurrentChatModelIndex()) return true;
        if (editingCompletionIdx != s.getCurrentCompletionModelIndex()) return true;
        if (!intVal(compDelaySpinner).equals(s.getCompletionDelayMs())) return true;
        return false;
    }

    @Override
    public void apply() {
        syncCurrentChatModel();
        syncCurrentCompletionModel();

        System.out.println("=== apply() 前 editingChatModels ===");
        for (ModelConfig m : editingChatModels) {
            System.out.println("name: " + m.getName() + ", apiKey: " + m.getApiKey());
        }


        LoongCSettings s = LoongCSettings.getInstance();
        s.setChatModels(deepCopy(editingChatModels));
        s.setCompletionModels(deepCopy(editingCompletionModels));
        s.setCurrentChatModelIndex(editingChatIdx);
        s.setCurrentCompletionModelIndex(editingCompletionIdx);
        s.setCompletionDelayMs(intVal(compDelaySpinner));

        System.out.println("=== apply() 后 LoongCSettings.getChatModels() ===");
        for (ModelConfig m : s.getChatModels()) {
            System.out.println("name: " + m.getName() + ", apiKey: " + m.getApiKey());
        }
    }

    @Override
    public void reset() {
        LoongCSettings s = LoongCSettings.getInstance();
        editingChatModels = deepCopy(s.getChatModels());
        editingCompletionModels = deepCopy(s.getCompletionModels());
        editingChatIdx = s.getCurrentChatModelIndex();
        editingCompletionIdx = s.getCurrentCompletionModelIndex();

        refreshChatCombo();
        refreshCompCombo();
        compDelaySpinner.setValue(s.getCompletionDelayMs());
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    private GridBagConstraints defaultGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    @SuppressWarnings("unchecked")
    private <T extends JComponent> T addRow(JBPanel<?> panel, GridBagConstraints gbc,
                                            int row, String label, T field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JBLabel(label), gbc);
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

    private List<ModelConfig> deepCopy(List<ModelConfig> list) {
        List<ModelConfig> copy = new ArrayList<>();
        for (ModelConfig m : list) {
            copy.add(m.copy());
        }
        return copy;
    }

    private boolean listEquals(List<ModelConfig> a, List<ModelConfig> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!modelEquals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private boolean modelEquals(ModelConfig a, ModelConfig b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return strEq(a.getName(), b.getName())
                && strEq(a.getApiKey(), b.getApiKey())
                && strEq(a.getApiBase(), b.getApiBase())
                && a.getMaxTokens() == b.getMaxTokens()
                && Double.compare(a.getTemperature(), b.getTemperature()) == 0;
    }

    private boolean strEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
