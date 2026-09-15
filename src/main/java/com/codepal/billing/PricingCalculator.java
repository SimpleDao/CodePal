package com.codepal.billing;

import com.codepal.model.ModelPricing;
import com.codepal.model.PeakWindow;

/**
 * 计费计算服务（纯逻辑，不依赖 Swing / 数据库 / 网络）。
 *
 * <p>职责单一：给定一组 token 用量与某模型的 {@link ModelPricing}，算出预估费用字符串。
 * 所有价格口径都来自数据（{@link ModelPricing}），本类不持有任何硬编码单价。
 *
 * <p>仅保留一个 {@link #DEFAULT_FALLBACK} 兜底单价，用于「用户尚未给该模型配置计费」时
 * 避免费用显示整体消失；它是单一通用档，不再按模型名硬编码多档价格。
 */
public final class PricingCalculator {

    /** 兜底单价（通用档，每百万 token）：命中 0.02 / 未命中 1.0 / 输出 2.0 元 */
    public static final ModelPricing DEFAULT_FALLBACK = defaultFallback();

    private PricingCalculator() {}

    /** 计算结果 */
    public static final class CostResult {
        public final double cost;
        public final String currency;
        public final boolean peakApplied;
        public CostResult(double cost, String currency, boolean peakApplied) {
            this.cost = cost;
            this.currency = currency;
            this.peakApplied = peakApplied;
        }
    }

    /** 以当前时刻计算（显示时刻估算，最小版够用） */
    public static CostResult compute(ModelPricing p, long cacheHit, long cacheMiss, long output) {
        return compute(p, cacheHit, cacheMiss, output, java.time.LocalDateTime.now());
    }

    /**
     * 核心计算。
     * @param at 计费时刻，用于匹配高峰时段窗口（星期 + 当天分钟数）
     */
    public static CostResult compute(ModelPricing p, long cacheHit, long cacheMiss, long output,
                                     java.time.LocalDateTime at) {
        if (p == null) return new CostResult(0, "¥", false);
        double mult = peakMultiplier(p, at);
        long unit = p.getUnit() > 0 ? p.getUnit() : ModelPricing.UNIT_PER_MILLION;
        double cost = (cacheHit * p.getPriceCacheHit()
                + cacheMiss * p.getPriceCacheMiss()
                + output * p.getPriceOutput()) / (double) unit * mult;
        return new CostResult(cost, p.getCurrency(), mult != 1.0);
    }

    /** 格式化为展示字符串：极小值用科学计数法，否则四位小数，前缀币种符号 */
    public static String format(CostResult r) {
        if (r == null) return "";
        double c = r.cost;
        String num = (c < 0.0001) ? String.format("%.2e", c) : String.format("%.4f", c);
        return (r.currency == null ? "" : r.currency) + num;
    }

    /** 判断是否处于任一高峰窗口并返回系数（按星期 + 分钟匹配，支持跨午夜窗口） */
    private static double peakMultiplier(ModelPricing p, java.time.LocalDateTime at) {
        if (!p.isPeakEnabled() || p.getPeakWindows().isEmpty()) return 1.0;
        int minutesOfDay = at.getHour() * 60 + at.getMinute();
        int isoDay = at.getDayOfWeek().getValue(); // 1=周一 … 7=周日
        for (PeakWindow w : p.getPeakWindows()) {
            if (w.matches(isoDay, minutesOfDay)) return p.getPeakMultiplier();
        }
        return 1.0;
    }

    private static ModelPricing defaultFallback() {
        ModelPricing p = new ModelPricing("__default__");
        p.setEnabled(true);
        p.setCurrency("¥");
        p.setUnit(ModelPricing.UNIT_PER_MILLION);
        p.setPriceCacheHit(0.02);
        p.setPriceCacheMiss(1.0);
        p.setPriceOutput(2.0);
        return p;
    }
}
