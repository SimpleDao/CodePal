package com.codepal.model;

import java.util.UUID;

public class ModelConfig {
    /**
     * 接口数据格式。决定该模型走哪条请求/响应解析链路：
     *   "openai"     —— OpenAI 兼容（/v1/chat/completions，默认）
     *   "anthropic"  —— Anthropic 原生 Messages API
     */
    public static final String FORMAT_OPENAI = "openai";
    public static final String FORMAT_ANTHROPIC = "anthropic";

    private String id;
    private String name = "";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private int maxTokens = 8152;
    private int maxOutput = 8192;
    private double temperature = 0.7;
    private String apiFormat = FORMAT_OPENAI;
    /** 该模型自身是否具备视觉（看图）能力；勾选后主聊天流程可直接看图，无需依赖独立的视觉子智能体 */
    private boolean supportsVision = false;

    public ModelConfig() {
        this.id = UUID.randomUUID().toString();
    }

    public ModelConfig(String name, String apiKey, String apiBase,
                       int maxTokens, double temperature) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.maxTokens = maxTokens;
        this.maxOutput = 8192;
        this.temperature = temperature;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ModelConfig copy() {
        ModelConfig m = new ModelConfig(name, apiKey, apiBase, maxTokens, temperature);
        m.id = this.id;
        m.setMaxOutput(maxOutput);
        m.setApiFormat(apiFormat);
        m.setSupportsVision(supportsVision);
        return m;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public int getMaxOutput() { return maxOutput; }
    public void setMaxOutput(int maxOutput) { this.maxOutput = maxOutput; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public String getApiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) {
        this.apiFormat = (apiFormat == null) ? FORMAT_OPENAI : apiFormat;
    }

    public boolean isSupportsVision() { return supportsVision; }
    public void setSupportsVision(boolean supportsVision) { this.supportsVision = supportsVision; }

    @Override
    public String toString() {
        return "ModelConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", apiBase='" + apiBase + '\'' +
                ", maxTokens=" + maxTokens +
                ", maxOutput=" + maxOutput +
                ", temperature=" + temperature +
                ", apiFormat='" + apiFormat + '\'' +
                ", supportsVision=" + supportsVision +
                '}';
    }
}
