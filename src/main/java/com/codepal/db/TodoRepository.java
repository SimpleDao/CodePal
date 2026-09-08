package com.codepal.db;

import com.codepal.tools.TodoManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 待办列表持久化 DAO —— 按会话维度把 TodoItem[] 存进 SQLite（session_todos 表）。
 *
 * <p>待办列表原先只在 TodoManager 内存里，重启 IDEA / 切换会话后丢失（历史消息重放恢复
 * 不可靠：依赖 todo 工具 part 是否落库、是否被压缩、异步时序等）。本类提供独立、可靠的
 * 按会话存储，变更即写、激活即读。
 */
public final class TodoRepository {

    private TodoRepository() {}

    /** 保存某个会话的待办列表（整表替换）。todos 为 null/空则视为清空。 */
    public static void save(String sessionId, String todosJson) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Connection conn = SqliteDatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM session_todos WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            if (todosJson == null || todosJson.isBlank()) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO session_todos(session_id, todos_json, updated_at) VALUES(?,?,?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, todosJson);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            com.intellij.openapi.diagnostic.Logger.getInstance(TodoRepository.class)
                    .warn("保存待办失败 sessionId=" + sessionId, e);
        }
    }

    /** 删除某个会话的待办记录（会话被删除时清理孤儿数据）。 */
    public static void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM session_todos WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            com.intellij.openapi.diagnostic.Logger.getInstance(TodoRepository.class)
                    .warn("删除待办失败 sessionId=" + sessionId, e);
        }
    }

    /** 读取某个会话的待办列表 JSON（无记录返回 null）。 */
    public static String load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT todos_json FROM session_todos WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("todos_json");
            }
        } catch (Exception e) {
            com.intellij.openapi.diagnostic.Logger.getInstance(TodoRepository.class)
                    .warn("读取待办失败 sessionId=" + sessionId, e);
        }
        return null;
    }
}
