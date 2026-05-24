package com.loongc.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * CodeBuddy 插件持久化设置
 */
@State(
    name = "com.codebuddy.settings.CodeBuddySettings",
    storages = @Storage("CodeBuddySettings.xml")
)
public class CodeBuddySettings implements PersistentStateComponent<CodeBuddySettings> {

    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private boolean enableAutoComplete = true;
    private int maxTokens = 4096;
    private double temperature = 0.7;

    public static CodeBuddySettings getInstance() {
        return ApplicationManager.getApplication().getService(CodeBuddySettings.class);
    }

    @Nullable
    @Override
    public CodeBuddySettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull CodeBuddySettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isEnableAutoComplete() {
        return enableAutoComplete;
    }

    public void setEnableAutoComplete(boolean enableAutoComplete) {
        this.enableAutoComplete = enableAutoComplete;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
