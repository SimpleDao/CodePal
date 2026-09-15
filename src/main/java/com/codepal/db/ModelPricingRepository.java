package com.codepal.db;

import com.codepal.model.ModelPricing;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 单模型计费配置数据访问层 —— 按 {@code model_name} 维度读写 {@code model_pricing} 表。
 *
 * <p>与 {@link DBModelConfigRepository} 解耦：计费是模型的一个可选项，不污染模型主表，
 * 模型改名时由调用方负责迁移旧行。
 */
public final class ModelPricingRepository {

    private static final Logger LOG = Logger.getInstance(ModelPricingRepository.class);

    private ModelPricingRepository() {}

    /** 读取某模型的计费配置；无则返回 null */
    @Nullable
    public static ModelPricing load(String modelName) {
        if (modelName == null || modelName.isEmpty()) return null;
        String sql = "SELECT model_name, enabled, currency, unit, price_cache_hit, price_cache_miss, "
                + "price_output, peak_enabled, peak_windows, "
                + "peak_start_hour, peak_start_minute, peak_end_hour, peak_end_minute, peak_multiplier "
                + "FROM model_pricing WHERE model_name = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        } catch (Exception e) {
            LOG.error("load pricing failed, model=" + modelName, e);
        }
        return null;
    }

    /** 插入或更新（按 model_name 冲突合并） */
    public static void upsert(ModelPricing p) {
        if (p == null || p.getModelName() == null || p.getModelName().isEmpty()) return;
        String sql = "INSERT INTO model_pricing "
                + "(model_name, enabled, currency, unit, price_cache_hit, price_cache_miss, price_output, "
                + "peak_enabled, peak_multiplier, peak_windows) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(model_name) DO UPDATE SET "
                + "enabled=excluded.enabled, currency=excluded.currency, unit=excluded.unit, "
                + "price_cache_hit=excluded.price_cache_hit, price_cache_miss=excluded.price_cache_miss, "
                + "price_output=excluded.price_output, peak_enabled=excluded.peak_enabled, "
                + "peak_multiplier=excluded.peak_multiplier, peak_windows=excluded.peak_windows";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, p);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("upsert pricing failed, model=" + p.getModelName(), e);
        }
    }

    /** 删除某模型的计费配置（模型改名或彻底弃用计费时调用） */
    public static void delete(String modelName) {
        if (modelName == null || modelName.isEmpty()) return;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM model_pricing WHERE model_name = ?")) {
            ps.setString(1, modelName);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("delete pricing failed, model=" + modelName, e);
        }
    }

    private static ModelPricing map(ResultSet rs) throws SQLException {
        ModelPricing p = new ModelPricing();
        p.setModelName(rs.getString("model_name"));
        p.setEnabled(rs.getInt("enabled") == 1);
        p.setCurrency(rs.getString("currency"));
        p.setUnit(rs.getLong("unit"));
        p.setPriceCacheHit(rs.getDouble("price_cache_hit"));
        p.setPriceCacheMiss(rs.getDouble("price_cache_miss"));
        p.setPriceOutput(rs.getDouble("price_output"));
        p.setPeakEnabled(rs.getInt("peak_enabled") == 1);
        p.setPeakWindows(fromJson(rs.getString("peak_windows")));
        // 旧数据兼容：多窗口列为空但启用过高峰 → 用旧的单窗口列回退（每天生效）
        if (p.getPeakWindows().isEmpty() && p.isEnabled()) {
            int sh = rs.getInt("peak_start_hour");
            int sm = rs.getInt("peak_start_minute");
            int eh = rs.getInt("peak_end_hour");
            int em = rs.getInt("peak_end_minute");
            p.addPeakWindow(new com.codepal.model.PeakWindow(null, sh * 60 + sm, eh * 60 + em));
        }
        p.setPeakMultiplier(rs.getDouble("peak_multiplier"));
        return p;
    }

    private static void bind(PreparedStatement ps, ModelPricing p) throws SQLException {
        ps.setString(1, p.getModelName());
        ps.setInt(2, p.isEnabled() ? 1 : 0);
        ps.setString(3, p.getCurrency());
        ps.setLong(4, p.getUnit());
        ps.setDouble(5, p.getPriceCacheHit());
        ps.setDouble(6, p.getPriceCacheMiss());
        ps.setDouble(7, p.getPriceOutput());
        ps.setInt(8, p.isPeakEnabled() ? 1 : 0);
        ps.setDouble(9, p.getPeakMultiplier());
        ps.setString(10, toJson(p.getPeakWindows()));
    }

    // ── peak_windows JSON 序列化 ──
    // 形如：[{"days":[1,2,3,4,5],"start":"09:00","end":"12:00"},
    //        {"days":[1,2,3,4,5],"start":"14:00","end":"18:00"}]
    // days 为空数组/缺省 = 每天生效

    private static String toJson(java.util.List<com.codepal.model.PeakWindow> windows) {
        if (windows == null || windows.isEmpty()) return null;
        try {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (com.codepal.model.PeakWindow w : windows) {
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                com.google.gson.JsonArray days = new com.google.gson.JsonArray();
                for (Integer d : w.getDays()) days.add(d);
                o.add("days", days);
                o.addProperty("start", com.codepal.model.PeakWindow.formatTime(w.getStartMinutes()));
                o.addProperty("end", com.codepal.model.PeakWindow.formatTime(w.getEndMinutes()));
                arr.add(o);
            }
            return arr.toString();
        } catch (Exception e) {
            LOG.error("peak_windows toJson failed", e);
            return null;
        }
    }

    private static java.util.List<com.codepal.model.PeakWindow> fromJson(String json) {
        java.util.List<com.codepal.model.PeakWindow> list = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) return list;
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (var el : arr) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                java.util.Set<Integer> days = new java.util.LinkedHashSet<>();
                if (o.has("days") && o.get("days").isJsonArray()) {
                    for (var d : o.getAsJsonArray("days")) days.add(d.getAsInt());
                }
                int s = com.codepal.model.PeakWindow.parseTimeToMinutes(
                        o.has("start") ? o.get("start").getAsString() : "", -1);
                int e = com.codepal.model.PeakWindow.parseTimeToMinutes(
                        o.has("end") ? o.get("end").getAsString() : "", -1);
                if (s >= 0 && e >= 0) list.add(new com.codepal.model.PeakWindow(days, s, e));
            }
        } catch (Exception e) {
            LOG.error("peak_windows fromJson failed: " + json, e);
        }
        return list;
    }
}
