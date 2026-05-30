package com.loongc.model;

public class ModelConfig {
    private String name = "";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private int maxTokens = 4096;
    private double temperature = 0.7;

    public ModelConfig() {}

    public ModelConfig(String name, String apiKey, String apiBase,
                       int maxTokens, double temperature) {
        this.name = name;
        this.apiKey = apiKey;
        this.apiBase = apiBase;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    // 浅拷贝
    public ModelConfig copy() {
        return new ModelConfig(name, apiKey, apiBase, maxTokens, temperature);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    @Override
    public String toString() {
        return "ModelConfig{" +
                "name='" + name + '\'' +
                ", apiKey='" + apiKey + '\'' +
                ", apiBase='" + apiBase + '\'' +
                ", maxTokens=" + maxTokens +
                ", temperature=" + temperature +
                '}';
    }
}
