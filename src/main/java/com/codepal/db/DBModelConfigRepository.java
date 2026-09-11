package com.codepal.db;

import com.intellij.openapi.diagnostic.Logger;
import com.codepal.model.ModelConfig;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模型配置数据访问层 —— 聊天/补全模型列表落库，替代 IDE 缓存（CPSettings.xml）。
 *
 * <p>表 {@code model_configs} 按 {@code type} 区分聊天(chat)/补全(completion)，
 * {@code sort_order} 维护展示顺序，{@code is_current} 标记当前选中项。
 */
public final class DBModelConfigRepository {

    private static final Logger LOG = Logger.getInstance(DBModelConfigRepository.class);

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_COMPLETION = "completion";
    public static final String TYPE_COMPRESSION = "compression";

    private DBModelConfigRepository() {}

    /** 读取某类模型列表（按 sort_order 升序） */
    public static List<ModelConfig> loadModels(String type) {
        List<ModelConfig> list = new ArrayList<>();
        String sql = "SELECT id, name, api_key, api_base, max_tokens, max_output, temperature, "
                + "api_format, supports_vision, sort_order, is_current FROM model_configs WHERE type = ? "
                + "ORDER BY sort_order ASC";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ModelConfig m = new ModelConfig();
                    m.setId(rs.getString("id"));
                    m.setName(rs.getString("name"));
                    m.setApiKey(rs.getString("api_key"));
                    m.setApiBase(rs.getString("api_base"));
                    m.setMaxTokens(rs.getInt("max_tokens"));
                    m.setMaxOutput(rs.getInt("max_output"));
                    m.setTemperature(rs.getDouble("temperature"));
                    m.setApiFormat(rs.getString("api_format"));
                    m.setSupportsVision(rs.getInt("supports_vision") == 1);
                    list.add(m);
                }
            }
        } catch (Exception e) {
            LOG.error("loadModels failed, type=" + type, e);
        }
        return list;
    }

    /** 读取单个模型整行配置（type + id 精确定位），供"当前模型直接查库"使用 */
    @Nullable
    public static ModelConfig loadModelById(String type, String id) {
        if (type == null || id == null || id.isEmpty()) return null;
        String sql = "SELECT id, name, api_key, api_base, max_tokens, max_output, temperature, "
                + "api_format, supports_vision, sort_order, is_current FROM model_configs WHERE type = ? AND id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ModelConfig m = new ModelConfig();
                    m.setId(rs.getString("id"));
                    m.setName(rs.getString("name"));
                    m.setApiKey(rs.getString("api_key"));
                    m.setApiBase(rs.getString("api_base"));
                    m.setMaxTokens(rs.getInt("max_tokens"));
                    m.setMaxOutput(rs.getInt("max_output"));
                    m.setTemperature(rs.getDouble("temperature"));
                    m.setApiFormat(rs.getString("api_format"));
                    m.setSupportsVision(rs.getInt("supports_vision") == 1);
                    return m;
                }
            }
        } catch (Exception e) {
            LOG.error("loadModelById failed, type=" + type + ", id=" + id, e);
        }
        return null;
    }

    /** 读取某类当前选中模型的 id（is_current=1 的行；无则 null） */
    @Nullable
    public static String loadCurrentId(String type) {
        String sql = "SELECT id FROM model_configs WHERE type = ? AND is_current = 1 LIMIT 1";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (Exception e) {
            LOG.error("loadCurrentId failed, type=" + type, e);
        }
        return null;
    }

    /** 单条插入：sort_order 取该 type 当前最大值 +1，is_current 置 0（选中由 setCurrent 单独设置） */
    public static boolean insertModel(String type, ModelConfig m) {
        if (m == null) return false;
        if (m.getId() == null || m.getId().isEmpty()) {
            m.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO model_configs "
                + "(id, type, name, api_key, api_base, max_tokens, max_output, temperature, "
                + "api_format, supports_vision, sort_order, is_current) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, m.getId());
            ps.setString(2, type);
            ps.setString(3, m.getName());
            ps.setString(4, m.getApiKey());
            ps.setString(5, m.getApiBase());
            ps.setInt(6, m.getMaxTokens());
            ps.setInt(7, m.getMaxOutput());
            ps.setDouble(8, m.getTemperature());
            ps.setString(9, m.getApiFormat());
            ps.setInt(10, m.isSupportsVision() ? 1 : 0);
            ps.setInt(11, nextSortOrder(conn, type));
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            LOG.error("insertModel failed, type=" + type, e);
            return false;
        }
    }

    private static int nextSortOrder(Connection conn, String type) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(sort_order), -1) FROM model_configs WHERE type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 0;
            }
        }
    }

    /** 单条更新（按 id）：只改业务字段，不动 type / sort_order / is_current */
    public static boolean updateModel(ModelConfig m) {
        if (m == null || m.getId() == null || m.getId().isEmpty()) return false;
        String sql = "UPDATE model_configs SET name = ?, api_key = ?, api_base = ?, max_tokens = ?, "
                + "max_output = ?, temperature = ?, api_format = ?, supports_vision = ? WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, m.getName());
            ps.setString(2, m.getApiKey());
            ps.setString(3, m.getApiBase());
            ps.setInt(4, m.getMaxTokens());
            ps.setInt(5, m.getMaxOutput());
            ps.setDouble(6, m.getTemperature());
            ps.setString(7, m.getApiFormat());
            ps.setInt(8, m.isSupportsVision() ? 1 : 0);
            ps.setString(9, m.getId());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.error("updateModel failed, id=" + m.getId(), e);
            return false;
        }
    }

    /** 单条删除（按 id） */
    public static boolean deleteModel(String id) {
        if (id == null || id.isEmpty()) return false;
        String sql = "DELETE FROM model_configs WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.error("deleteModel failed, id=" + id, e);
            return false;
        }
    }

    /** 设置某类当前选中项（按 id）：先清零再置位，同一事务内完成 */
    public static boolean setCurrent(String type, String id) {
        if (type == null || id == null || id.isEmpty()) return false;
        Connection conn = null;
        try {
            conn = SqliteDatabaseManager.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement clear = conn.prepareStatement(
                    "UPDATE model_configs SET is_current = 0 WHERE type = ?")) {
                clear.setString(1, type);
                clear.executeUpdate();
            }
            try (PreparedStatement set = conn.prepareStatement(
                    "UPDATE model_configs SET is_current = 1 WHERE id = ? AND type = ?")) {
                set.setString(1, id);
                set.setString(2, type);
                set.executeUpdate();
            }
            conn.commit();
            return true;
        } catch (Exception e) {
            LOG.error("setCurrent failed, type=" + type + ", id=" + id, e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
            return false;
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
                SqliteDatabaseManager.closeConnection(conn);
            }
        }
    }

    /** 整体替换某类模型配置（删旧→插新，事务保证原子；currentIndex 标记 is_current） */
    public static void saveModels(String type, List<ModelConfig> models, int currentIndex) {
        if (models == null) models = new ArrayList<>();
        Connection conn = null;
        try {
            conn = SqliteDatabaseManager.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM model_configs WHERE type = ?")) {
                del.setString(1, type);
                del.executeUpdate();
            }
            String ins = "INSERT INTO model_configs "
                    + "(id, type, name, api_key, api_base, max_tokens, max_output, temperature, "
                    + "api_format, supports_vision, sort_order, is_current) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                for (int i = 0; i < models.size(); i++) {
                    ModelConfig m = models.get(i);
                    if (m.getId() == null || m.getId().isEmpty()) {
                        m.setId(UUID.randomUUID().toString());
                    }
                    ps.setString(1, m.getId());
                    ps.setString(2, type);
                    ps.setString(3, m.getName());
                    ps.setString(4, m.getApiKey());
                    ps.setString(5, m.getApiBase());
                    ps.setInt(6, m.getMaxTokens());
                    ps.setInt(7, m.getMaxOutput());
                    ps.setDouble(8, m.getTemperature());
                    ps.setString(9, m.getApiFormat());
                    ps.setInt(10, m.isSupportsVision() ? 1 : 0);
                    ps.setInt(11, i);
                    ps.setInt(12, (i == currentIndex) ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            LOG.error("saveModels failed, type=" + type, e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) { }
            }
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
                SqliteDatabaseManager.closeConnection(conn);
            }
        }
    }
}
