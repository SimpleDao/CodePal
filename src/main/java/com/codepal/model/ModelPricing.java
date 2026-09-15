package com.codepal.model;

/**
 * 单模型计费配置（按模型名维度，落库 {@code model_pricing}）。
 *
 * <p>所有价格均以「每 {@link #unit} 个 token」计价，前端填写时统一以「每百万 token」为单位，
 * 避免把价格常量散落在业务代码里（之前 ChatPanel 中硬编码的 deepseek 多档价格已迁移到此处数据）。
 */
public class ModelPricing {

    /** 计价单位：默认每百万 token 一个价格单位 */
    public static final long UNIT_PER_MILLION = 1_000_000L;

    private String modelName;
    private boolean enabled = false;
    private String currency = "¥";
    private long unit = UNIT_PER_MILLION;
    private double priceCacheHit = 0.0;   // 输入·缓存命中：每 unit 个 token 的价格
    private double priceCacheMiss = 0.0;  // 输入·缓存未命中：每 unit 个 token 的价格
    private double priceOutput = 0.0;     // 输出：每 unit 个 token 的价格
    private boolean peakEnabled = false;
    /** 高峰时段窗口（支持多窗口/限定星期/跨午夜，如 DeepSeek：工作日 09:00-12:00、14:00-18:00） */
    private final java.util.List<PeakWindow> peakWindows = new java.util.ArrayList<>();
    private double peakMultiplier = 1.0;   // 高峰时段价格系数

    public ModelPricing() {}

    public ModelPricing(String modelName) {
        this.modelName = modelName;
    }

    public ModelPricing copy() {
        ModelPricing p = new ModelPricing(modelName);
        p.enabled = enabled;
        p.currency = currency;
        p.unit = unit;
        p.priceCacheHit = priceCacheHit;
        p.priceCacheMiss = priceCacheMiss;
        p.priceOutput = priceOutput;
        p.peakEnabled = peakEnabled;
        p.peakWindows.addAll(peakWindows);
        p.peakMultiplier = peakMultiplier;
        return p;
    }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = (currency == null ? "¥" : currency); }

    public long getUnit() { return unit; }
    public void setUnit(long unit) { this.unit = unit > 0 ? unit : UNIT_PER_MILLION; }

    public double getPriceCacheHit() { return priceCacheHit; }
    public void setPriceCacheHit(double v) { this.priceCacheHit = v; }

    public double getPriceCacheMiss() { return priceCacheMiss; }
    public void setPriceCacheMiss(double v) { this.priceCacheMiss = v; }

    public double getPriceOutput() { return priceOutput; }
    public void setPriceOutput(double v) { this.priceOutput = v; }

    public boolean isPeakEnabled() { return peakEnabled; }
    public void setPeakEnabled(boolean peakEnabled) { this.peakEnabled = peakEnabled; }

    public java.util.List<PeakWindow> getPeakWindows() { return peakWindows; }

    public void setPeakWindows(java.util.List<PeakWindow> windows) {
        peakWindows.clear();
        if (windows != null) peakWindows.addAll(windows);
    }

    public void addPeakWindow(PeakWindow w) { if (w != null) peakWindows.add(w); }

    public double getPeakMultiplier() { return peakMultiplier; }
    public void setPeakMultiplier(double m) { this.peakMultiplier = m; }
}
