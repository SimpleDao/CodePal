package com.loongc.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.loongc.model.ModelConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    // ── 通用字段配置 ──────────────────────────────────────────
    private final String fontStyle = "Microsoft YaHei";

    private final String systemPrompt =  "你是 LoongC，一个会主动使用工具的编程助手。当用户询问代码相关问题时，你必须使用工具来探索项目，而不是凭空猜测。\n\n" +
            "你可以使用的四大金刚工具：\n" +
            "1. locate_code_by_symbol —— 定位：输入类名或方法名，返回它所在的文件路径\n" +
            "2. view_file_outline —— 看大纲：输入文件路径，返回这个类的属性、方法签名及注释\n" +
            "3. read_file_range —— 精准读：输入文件路径、起始行和结束行，返回具体代码\n" +
            "4. search_grep —— 全局搜：在整个项目里搜索关键词\n\n" +
            "【工作流】当用户问代码问题时，按以下链路执行：\n" +
            "  第1步：search_grep 全局搜索相关关键词 → 发现涉及哪些文件\n" +
            "  第2步：view_file_outline 查看相关文件的大纲 → 了解类结构\n" +
            "  第3步：locate_code_by_symbol 定位关键方法 → 确定具体位置\n" +
            "  第4步：read_file_range 只读关键代码行 → 精确分析逻辑\n" +
            "  第5步：给出答案\n\n" +
            "回复时请使用 Markdown 格式，代码请放在代码块中。";

    private final String sayHello = "你好！我是 **LoongC**，你的智能编程助手。";

    // ── 聊天模型列表 ────────────────────────────────────────
    private List<ModelConfig> chatModels = new ArrayList<>();
    private int currentChatModelIndex = 0;

    // ── 补全模型列表 ────────────────────────────────────────
    private List<ModelConfig> completionModels = new ArrayList<>();
    private int currentCompletionModelIndex = 0;

    // ── 聊天模型配置 ──────────────────────────────────────────
//    private String apiKey = "";
//    private String apiBase = "https://api.deepseek.com";
//    private String model = "deepseek-chat";
//    private int maxTokens = 4096;
//    private double temperature = 0.7;

    // ── 内联补全（Ghost Text）专用配置 ────────────────────────
//    /** 留空时自动继承聊天 API Key */
//    private String completionApiKey = "";
//    /** 留空时自动继承聊天 API Base */
//    private String completionApiBase = "";
//    /** 补全专用模型，建议用较快的模型 */
//    private String completionModel = "deepseek-chat";
//    /** 补全最大 token（补全片段无需太长） */
//    private int completionMaxTokens = 256;
//    /** 补全 temperature，0.0 最确定性 */
//    private double completionTemperature = 0.0;
    /** 是否启用内联补全 */
    //private boolean enableAutoComplete = true;
    /** 用户停止输入后多少毫秒触发补全（默认 600ms，回车立即触发，普通字符需要停顿） */
    private int completionDelayMs = 600;

    // ── 通用设置 ──────────────────────────────────────────────
    /** 启用会话消息持久化 */
    private boolean enableMessagePersistence = true;
    /** 启用向量库优化 token */
    private boolean enableVectorOptimization = false;
    /** 启用智能内联代码补全 */
    private boolean enableSmartAutoComplete = true;

    public LoongCSettings() {
        // 初始化默认模型（若列表为空）
        ensureDefaultModels();
    }



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
        ensureDefaultModels();
    }

    public List<ModelConfig> getChatModels() { return chatModels; }
    public void setChatModels(List<ModelConfig> chatModels) { this.chatModels = chatModels; }

    public int getCurrentChatModelIndex() { return currentChatModelIndex; }
    public void setCurrentChatModelIndex(int index) { this.currentChatModelIndex = index; }

    public ModelConfig getCurrentChatModel() {
        if (chatModels == null || chatModels.isEmpty()) return null;
        if (currentChatModelIndex < 0 || currentChatModelIndex >= chatModels.size()) {
            currentChatModelIndex = 0;
        }
        return chatModels.get(currentChatModelIndex);
    }

    public String getChatApiKey() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getApiKey() : "";
    }

    public String getChatApiBase() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getApiBase() : "https://api.deepseek.com";
    }

    public String getChatModelName() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getName() : "deepseek-v4-flash";
    }

    public String[] getChatModelNames(){
        List<String> modelIds = new ArrayList<>();
        for (ModelConfig modelConfig : chatModels) {
            modelIds.add(modelConfig.getName());
        }
        return modelIds.toArray(new String[0]);
    }

    public int getChatMaxTokens() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getMaxTokens() : 4096;
    }

    public double getChatTemperature() {
        ModelConfig m = getCurrentChatModel();
        return m != null ? m.getTemperature() : 0.7;
    }

    public List<ModelConfig> getCompletionModels() { return completionModels; }
    public void setCompletionModels(List<ModelConfig> completionModels) { this.completionModels = completionModels; }
    public int getCurrentCompletionModelIndex() { return currentCompletionModelIndex; }
    public void setCurrentCompletionModelIndex(int index) { this.currentCompletionModelIndex = index; }


    public ModelConfig getCurrentCompletionModel() {
        if (completionModels == null || completionModels.isEmpty()) return null;
        if (currentCompletionModelIndex < 0 || currentCompletionModelIndex >= completionModels.size()) {
            currentCompletionModelIndex = 0;
        }
        return completionModels.get(currentCompletionModelIndex);
    }

    public String getCompletionApiKey() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getApiKey() : "";
    }

    public String getCompletionApiBase() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getApiBase() : "";
    }

    public String getCompletionModelName() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getName() : "deepseek-v4-flash";
    }

    public int getCompletionMaxTokens() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getMaxTokens() : 256;
    }

    public double getCompletionTemperature() {
        ModelConfig m = getCurrentCompletionModel();
        return m != null ? m.getTemperature() : 0.0;
    }

    public int getCompletionDelayMs() { return completionDelayMs; }
    public void setCompletionDelayMs(int completionDelayMs) { this.completionDelayMs = completionDelayMs; }

    // ── 通用设置 getter/setter ────────────────────────────────
    public boolean isEnableMessagePersistence() { return enableMessagePersistence; }
    public void setEnableMessagePersistence(boolean enableMessagePersistence) { this.enableMessagePersistence = enableMessagePersistence; }

    public boolean isEnableVectorOptimization() { return enableVectorOptimization; }
    public void setEnableVectorOptimization(boolean enableVectorOptimization) { this.enableVectorOptimization = enableVectorOptimization; }

    public boolean isEnableSmartAutoComplete() { return enableSmartAutoComplete; }
    public void setEnableSmartAutoComplete(boolean enableSmartAutoComplete) { this.enableSmartAutoComplete = enableSmartAutoComplete; }

    public String getFontStyle() {
        return fontStyle;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getSayHello() {
        return sayHello;
    }

    // ── 便捷方法 ──────────────────────────────────────────────
    /** 聊天模型是否已配置 */
    public boolean isConfigured() {
        return !getChatApiKey().trim().isEmpty();
    }

    /** 获取实际生效的补全 API Key（优先用专用，否则继承聊天） */
    public String getEffectiveCompletionApiKey() {
        String key = getCompletionApiKey();
        return !key.trim().isEmpty() ? key : getChatApiKey();
    }

    /** 获取实际生效的补全 API Base */
    public String getEffectiveCompletionApiBase() {
        String base = getCompletionApiBase();
        return !base.trim().isEmpty() ? base : getChatApiBase();
    }

    private void ensureDefaultModels() {
        if (chatModels == null) chatModels = new ArrayList<>();
        if (completionModels == null) completionModels = new ArrayList<>();
        if (chatModels.isEmpty()) {
            chatModels.add(new ModelConfig("deepseek-v4-flash", "",
                    "https://api.deepseek.com", 4096, 0.7));
        }
        if (completionModels.isEmpty()) {
            completionModels.add(new ModelConfig("deepseek-v4-flash", "",
                    "https://api.deepseek.com/beta", 256, 0.0));
        }
        // 索引越界保护
        if (currentChatModelIndex < 0 || currentChatModelIndex >= chatModels.size()) {
            currentChatModelIndex = 0;
        }
        if (currentCompletionModelIndex < 0 || currentCompletionModelIndex >= completionModels.size()) {
            currentCompletionModelIndex = 0;
        }
    }
}
