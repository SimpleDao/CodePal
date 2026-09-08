package com.codepal.db;

import com.codepal.model.DatabaseComboItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据源配置持久化（data_sources 表）。
 * 密码明文保存（与 model_configs.api_key 同款处理，不加密）。
 */
public final class DataSourceDao {

    private DataSourceDao() {
    }

    /** 保存或更新一个数据源；id 为空时视为新增并自动生成 id + sort_order。 */
    public static void save(String id, String name, String type, String host,
                            int port, String dbName, String user, String password, int sortOrder) {
        String realId = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
        boolean exists = realId != null && exists(realId);
        String sql;
        if (exists) {
            sql = "UPDATE data_sources SET name=?, type=?, host=?, port=?, db_name=?, user=?, password=?, sort_order=? WHERE id=?";
        } else {
            sql = "INSERT INTO data_sources(id, name, type, host, port, db_name, user, password, sort_order) VALUES (?,?,?,?,?,?,?,?,?)";
        }
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (exists) {
                ps.setString(i++, name);
                ps.setString(i++, type == null ? "mysql" : type);
                ps.setString(i++, host);
                ps.setObject(i++, port <= 0 ? null : port);
                ps.setString(i++, dbName);
                ps.setString(i++, user);
                ps.setString(i++, password);
                ps.setInt(i++, sortOrder);
                ps.setString(i, realId);
            } else {
                ps.setString(i++, realId);
                ps.setString(i++, name);
                ps.setString(i++, type == null ? "mysql" : type);
                ps.setString(i++, host);
                ps.setObject(i++, port <= 0 ? null : port);
                ps.setString(i++, dbName);
                ps.setString(i++, user);
                ps.setString(i++, password);
                ps.setInt(i++, sortOrder <= 0 ? nextSortOrder() : sortOrder);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存数据源失败: " + e.getMessage(), e);
        }
    }

    public static void delete(String id) {
        String sql = "DELETE FROM data_sources WHERE id=?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除数据源失败: " + e.getMessage(), e);
        }
    }

    /** 返回全部数据源（按 sort_order, name 排序）。 */
    public static List<DatabaseComboItem> getAll() {
        List<DatabaseComboItem> list = new ArrayList<>();
        String sql = "SELECT id, name, type FROM data_sources ORDER BY sort_order ASC, name ASC";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(DatabaseComboItem.source(rs.getString("id"), rs.getString("name"), rs.getString("type")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取数据源失败: " + e.getMessage(), e);
        }
        return list;
    }

    /** 按名称精确查询完整连接信息（含密码），供工具执行连接使用。 */
    public static DatabaseConnectionInfo getByName(String name) {
        String sql = "SELECT id, name, type, host, port, db_name, user, password FROM data_sources WHERE name=?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DatabaseConnectionInfo info = new DatabaseConnectionInfo();
                    info.id = rs.getString("id");
                    info.name = rs.getString("name");
                    info.type = rs.getString("type");
                    info.host = rs.getString("host");
                    info.port = rs.getInt("port");
                    if (rs.wasNull()) info.port = 0;
                    info.dbName = rs.getString("db_name");
                    info.user = rs.getString("user");
                    info.password = rs.getString("password");
                    return info;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询数据源失败: " + e.getMessage(), e);
        }
        return null;
    }

    private static boolean exists(String id) {
        String sql = "SELECT 1 FROM data_sources WHERE id=?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static int nextSortOrder() {
        String sql = "SELECT COALESCE(MAX(sort_order), 0) + 1 AS next FROM data_sources";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt("next");
        } catch (SQLException ignored) {
        }
        return 1;
    }

    /** 数据源完整连接信息（含敏感字段）。 */
    public static class DatabaseConnectionInfo {
        public String id;
        public String name;
        public String type;   // "mysql" | "sqlite"
        public String host;
        public int port;
        public String dbName;
        public String user;
        public String password;

        /** 构建 JDBC URL（不抛异常，连接失败由调用方处理）。 */
        public String buildJdbcUrl() {
            if ("sqlite".equalsIgnoreCase(type)) {
                // SQLite 时 db_name 作为连接路径或内存库名
                String path = (dbName == null || dbName.isEmpty()) ? "file::memory:?cache=shared" : dbName;
                if (!path.startsWith("jdbc:sqlite:")) {
                    path = "jdbc:sqlite:" + path;
                }
                return path;
            }
            // MySQL
            int p = port > 0 ? port : 3306;
            String h = (host == null || host.isEmpty()) ? "localhost" : host;
            String db = (dbName == null || dbName.isEmpty()) ? "" : "/" + dbName;
            return "jdbc:mysql://" + h + ":" + p + db
                    + "?useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=15000";
        }
    }
}
