package com.loongc.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * LoongC 插件持久化设置
 *
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 聊天模型与内联补全（Ghost Text）模型分开独立配置
 */
@State(
        name = "com.loongc.settings.LoongCSettings",
        storages = @Storage("LoongCSettings.xml")
)
public class LoongCSettings implements PersistentStateComponent<LoongCSettings> {

    // ── 聊天模型配置 ──────────────────────────────────────────
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private int maxTokens = 4096;
    private double temperature = 0.7;

    // ── 内联补全（Ghost Text）专用配置 ────────────────────────
    /** 留空时自动继承聊天 API Key */
    private String completionApiKey = "";
    /** 留空时自动继承聊天 API Base */
    private String completionApiBase = "";
    /** 补全专用模型，建议用较快的模型 */
    private String completionModel = "deepseek-chat";
    /** 补全最大 token（补全片段无需太长） */
    private int completionMaxTokens = 256;
    /** 补全 temperature，0.0 最确定性 */
    private double completionTemperature = 0.0;
    /** 是否启用内联补全 */
    private boolean enableAutoComplete = true;
    /** 用户停止输入后多少毫秒触发补全（默认 600ms，回车立即触发，普通字符需要停顿） */
    private int completionDelayMs = 600;

    public static LoongCSettings getInstance() {
        return ApplicationManager.getApplication().getService(LoongCSettings.class);
    }

    @Nullable
    @Override
    public LoongCSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull LoongCSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    // ── 聊天模型 getter/setter ────────────────────────────────
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    // ── 内联补全 getter/setter ────────────────────────────────
    public String getCompletionApiKey() { return completionApiKey; }
    public void setCompletionApiKey(String completionApiKey) { this.completionApiKey = completionApiKey; }

    public String getCompletionApiBase() { return completionApiBase; }
    public void setCompletionApiBase(String completionApiBase) { this.completionApiBase = completionApiBase; }

    public String getCompletionModel() { return completionModel; }
    public void setCompletionModel(String completionModel) { this.completionModel = completionModel; }

    public int getCompletionMaxTokens() { return completionMaxTokens; }
    public void setCompletionMaxTokens(int completionMaxTokens) { this.completionMaxTokens = completionMaxTokens; }

    public double getCompletionTemperature() { return completionTemperature; }
    public void setCompletionTemperature(double completionTemperature) { this.completionTemperature = completionTemperature; }

    public boolean isEnableAutoComplete() { return enableAutoComplete; }
    public void setEnableAutoComplete(boolean enableAutoComplete) { this.enableAutoComplete = enableAutoComplete; }

    public int getCompletionDelayMs() { return completionDelayMs; }
    public void setCompletionDelayMs(int completionDelayMs) { this.completionDelayMs = completionDelayMs; }

    // ── 便捷方法 ──────────────────────────────────────────────
    /** 聊天模型是否已配置 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /** 获取实际生效的补全 API Key（优先用专用，否则继承聊天） */
    public String getEffectiveCompletionApiKey() {
        return (completionApiKey != null && !completionApiKey.trim().isEmpty())
                ? completionApiKey : apiKey;
    }

    /** 获取实际生效的补全 API Base */
    public String getEffectiveCompletionApiBase() {
        return (completionApiBase != null && !completionApiBase.trim().isEmpty())
                ? completionApiBase : apiBase;
    }
}
