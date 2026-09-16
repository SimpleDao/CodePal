package com.codepal.db;

import com.codepal.model.ChatResponse;
import com.codepal.model.ModelTokenUsage;
import com.intellij.openapi.diagnostic.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;

/**
 * 会话 × 模型 维度的 token 用量落库（{@code session_model_usage} 表）。
 *
 * <p>每次请求的 usage 到达时按「当前选中模型」原子增量累加；会话激活时整表回填，
 * 用于圆环面板「会话累计（按模型）」的跨重启/切会话展示。
 */
public final class DBSessionUsageRepository {

    private static final Logger LOG = Logger.getInstance(DBSessionUsageRepository.class);

    private DBSessionUsageRepository() {}

    /** 原子增量 upsert：某会话某模型的用量累加（并发安全，单条 SQL 完成） */
    public static void incrementUsage(String sessionId, String modelName, ChatResponse.Usage u) {
        if (sessionId == null || sessionId.isEmpty()
                || modelName == null || modelName.isEmpty() || u == null) return;
        String sql = """
            INSERT INTO session_model_usage
              (session_id, model_name, prompt_tokens, completion_tokens,
               cache_hit_tokens, cache_miss_tokens, updated_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(session_id, model_name) DO UPDATE SET
              prompt_tokens     = prompt_tokens     + excluded.prompt_tokens,
              completion_tokens = completion_tokens + excluded.completion_tokens,
              cache_hit_tokens  = cache_hit_tokens  + excluded.cache_hit_tokens,
              cache_miss_tokens = cache_miss_tokens + excluded.cache_miss_tokens,
              updated_at        = excluded.updated_at
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, modelName);
            ps.setInt(3, u.getPromptTokens());
            ps.setInt(4, u.getCompletionTokens());
            ps.setInt(5, u.getPromptCacheHitTokens());
            ps.setInt(6, u.getPromptCacheMissTokens());
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("incrementUsage failed, session=" + sessionId + ", model=" + modelName, e);
        }
    }

    /** 读取某会话按模型分组的累计用量（rowid 序 = 模型首次使用顺序）；无记录返回空 Map */
    public static LinkedHashMap<String, ModelTokenUsage> loadUsage(String sessionId) {
        LinkedHashMap<String, ModelTokenUsage> map = new LinkedHashMap<>();
        if (sessionId == null || sessionId.isEmpty()) return map;
        String sql = "SELECT model_name, prompt_tokens, completion_tokens, "
                + "cache_hit_tokens, cache_miss_tokens FROM session_model_usage "
                + "WHERE session_id = ? ORDER BY rowid ASC";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ModelTokenUsage b = new ModelTokenUsage();
                    b.setPrompt(rs.getLong("prompt_tokens"));
                    b.setCompletion(rs.getLong("completion_tokens"));
                    b.setCacheHit(rs.getLong("cache_hit_tokens"));
                    b.setCacheMiss(rs.getLong("cache_miss_tokens"));
                    map.put(rs.getString("model_name"), b);
                }
            }
        } catch (Exception e) {
            LOG.error("loadUsage failed, session=" + sessionId, e);
        }
        return map;
    }

    /** 删除某会话的全部用量行（会话删除时调用） */
    public static void deleteUsage(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM session_model_usage WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("deleteUsage failed, session=" + sessionId, e);
        }
    }

    // ═══ 会话级「当前上下文」真实快照（session_context_snapshot）═══
    // 语义 = 最近一次主链路请求的 usage（覆盖式，非增量）。
    // 用途：会话激活/重启回显时回填 last* 快照，圆环跨重启/切会话保持精确值。

    /** 覆盖式 upsert：记录某会话最近一次请求的真实 usage 快照 */
    public static void saveContextSnapshot(String sessionId, ChatResponse.Usage u) {
        if (sessionId == null || sessionId.isEmpty() || u == null) return;
        String sql = """
            INSERT INTO session_context_snapshot
              (session_id, prompt_tokens, completion_tokens,
               cache_hit_tokens, cache_miss_tokens, updated_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(session_id) DO UPDATE SET
              prompt_tokens     = excluded.prompt_tokens,
              completion_tokens = excluded.completion_tokens,
              cache_hit_tokens  = excluded.cache_hit_tokens,
              cache_miss_tokens = excluded.cache_miss_tokens,
              updated_at        = excluded.updated_at
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, u.getPromptTokens());
            ps.setInt(3, u.getCompletionTokens());
            ps.setInt(4, u.getPromptCacheHitTokens());
            ps.setInt(5, u.getPromptCacheMissTokens());
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("saveContextSnapshot failed, session=" + sessionId, e);
        }
    }

    /** 读取某会话的上下文快照；无记录返回 null（调用方回退估算） */
    public static ChatResponse.Usage loadContextSnapshot(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        String sql = "SELECT prompt_tokens, completion_tokens, "
                + "cache_hit_tokens, cache_miss_tokens FROM session_context_snapshot "
                + "WHERE session_id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ChatResponse.Usage u = new ChatResponse.Usage();
                    u.setPromptTokens(rs.getInt("prompt_tokens"));
                    u.setCompletionTokens(rs.getInt("completion_tokens"));
                    u.setPromptCacheHitTokens(rs.getInt("cache_hit_tokens"));
                    u.setPromptCacheMissTokens(rs.getInt("cache_miss_tokens"));
                    return u;
                }
            }
        } catch (Exception e) {
            LOG.error("loadContextSnapshot failed, session=" + sessionId, e);
        }
        return null;
    }

    /** 删除某会话的上下文快照（压缩/清空会话后上下文已裁剪，快照失效） */
    public static void deleteContextSnapshot(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM session_context_snapshot WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("deleteContextSnapshot failed, session=" + sessionId, e);
        }
    }
}
