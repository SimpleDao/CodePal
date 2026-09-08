package com.codepal.db;

import com.codepal.model.ChatMessageEntity;
import com.codepal.model.ChatSessionEntity;
import com.codepal.model.MessagePartEntity;
import com.intellij.openapi.diagnostic.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据库访问层 — 会话/消息/消息分片 三表 CRUD
 *
 * <p>表结构：
 * <pre>
 *   sessions ──&lt; messages ──&lt; message_parts
 * </pre>
 */
public class DBChatHistoryRepository {

    private static final Logger LOG = Logger.getInstance(DBChatHistoryRepository.class);

    // ═══════════════════════════════════════════════════════════════
    // Sessions
    // ═══════════════════════════════════════════════════════════════

    public static ChatSessionEntity createSession(String title) {
        return createSession(title, null);
    }

    /** 创建属于指定项目的会话（projectPath 为 project.getBasePath()） */
    public static ChatSessionEntity createSession(String title, String projectPath) {
        String id = getUUID();
        long now = System.currentTimeMillis();
        ChatSessionEntity session = new ChatSessionEntity(id, title != null ? title : "新会话");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setProjectPath(projectPath);

        String sql = "INSERT INTO sessions (id, title, mode, created_at, updated_at, project_path) VALUES (?, ?, 'chat', ?, ?, ?)";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, session.getTitle());
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, projectPath);
            ps.executeUpdate();
            return session;
        } catch (SQLException e) {
            LOG.error("Failed to create session", e);
            return null;
        }
    }

    public static void updateSessionTitle(String id, String title) {
        String sql = "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Failed to update session title", e);
        }
    }

    /** @deprecated use updateSessionTitle */
    @Deprecated
    public static void updateSessionName(String id, String name) {
        updateSessionTitle(id, name);
    }

    public static void deleteSession(String id) {
        // 无外键约束，手动按依赖顺序级联清理（事务保证原子性）
        String delParts = "DELETE FROM message_parts WHERE session_id = ?";
        String delMessages = "DELETE FROM messages WHERE session_id = ?";
        String delSession = "DELETE FROM sessions WHERE id = ?";
        Connection conn = null;
        try {
            conn = SqliteDatabaseManager.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(delParts)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(delMessages)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(delSession)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            LOG.error("Failed to delete session", e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                SqliteDatabaseManager.closeConnection(conn);
            }
        }
    }

    public static List<ChatSessionEntity> listSessions() {
        return listSessions(null);
    }

    /** 列出属于指定项目的会话；projectPath 为 null 时返回全部（向后兼容） */
    public static List<ChatSessionEntity> listSessions(String projectPath) {
        List<ChatSessionEntity> list = new ArrayList<>();
        String sql = """
            SELECT s.id, s.title, s.mode, s.model_id, s.preview, s.pinned,
                   s.created_at, s.updated_at, s.project_path,
                   (SELECT COUNT(DISTINCT qa_round) FROM messages WHERE messages.session_id = s.id AND qa_round > 0) AS msg_count
            FROM sessions s
            WHERE (? IS NULL OR s.project_path = ?)
            ORDER BY s.updated_at DESC
            """;
        // ⚠️ 不能把 executeQuery 写进 try-with-resources 声明处：
        // 那样会在参数绑定前执行 SQL，未绑定的 ? 为 NULL，
        // "(? IS NULL OR ...)" 恒为真 → 历史面板返回所有项目的会话。
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatSessionEntity s = new ChatSessionEntity();
                    s.setId(rs.getString("id"));
                    s.setTitle(rs.getString("title"));
                    s.setMode(rs.getString("mode"));
                    s.setModelId(rs.getString("model_id"));
                    s.setPreview(rs.getString("preview"));
                    s.setPinned(rs.getBoolean("pinned"));
                    s.setCreatedAt(rs.getLong("created_at"));
                    s.setUpdatedAt(rs.getLong("updated_at"));
                    s.setProjectPath(rs.getString("project_path"));
                    s.setMessageCount(rs.getInt("msg_count"));
                    list.add(s);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to list sessions", e);
        }
        return list;
    }

    public static ChatSessionEntity getRecentSession() {
        return getRecentSession(null);
    }

    /** 取指定项目内最近更新的会话；projectPath 为 null 时取全局最近（向后兼容） */
    public static ChatSessionEntity getRecentSession(String projectPath) {
        String sql = """
            SELECT id, title FROM sessions s
            WHERE (? IS NULL OR s.project_path = ?)
            ORDER BY updated_at DESC LIMIT 1
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ChatSessionEntity(rs.getString("id"), rs.getString("title"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to get recent session", e);
        }
        return null;
    }

    /** @deprecated use getRecentSession() */
    @Deprecated
    public static ChatSessionEntity getRecentConversation() {
        return getRecentSession();
    }

    /** 查询单个会话的标题（用于切换会话时同步内存中的当前标题） */
    public static String getSessionTitle(String id) {
        String sql = "SELECT title FROM sessions WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("title");
            }
        } catch (Exception e) {
            LOG.error("getSessionTitle failed", e);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Messages (header)
    // ═══════════════════════════════════════════════════════════════

    public static void saveMessage(ChatMessageEntity msg) {
        String sql = """
            INSERT INTO messages (id, session_id, role, seq, model_id, tokens, qa_round,
                                  created_at, updated_at, token_usage, meta)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msg.getId());
            ps.setString(2, msg.getSessionId());
            ps.setString(3, msg.getRole());
            ps.setInt(4, msg.getSeq());
            ps.setString(5, msg.getModelId());
            ps.setInt(6, msg.getTokens());
            ps.setInt(7, msg.getQaRound());
            ps.setLong(8, msg.getCreatedAt());
            ps.setLong(9, msg.getUpdatedAt());
            ps.setString(10, msg.getTokenUsage());
            ps.setString(11, msg.getMeta());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("saveMessage failed", e);
        }
    }

    /** 保存消息及其所有 parts（事务） */
    public static void saveMessageWithParts(ChatMessageEntity msg, List<MessagePartEntity> parts) {
        Connection conn = null;
        try {
            conn = SqliteDatabaseManager.getConnection();
            conn.setAutoCommit(false);

            // 1. 消息头
            String msgSql = """
                INSERT INTO messages (id, session_id, role, seq, model_id, tokens, qa_round,
                                      created_at, updated_at, token_usage, meta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(msgSql)) {
                ps.setString(1, msg.getId());
                ps.setString(2, msg.getSessionId());
                ps.setString(3, msg.getRole());
                ps.setInt(4, msg.getSeq());
                ps.setString(5, msg.getModelId());
                ps.setInt(6, msg.getTokens());
                ps.setInt(7, msg.getQaRound());
                ps.setLong(8, msg.getCreatedAt());
                ps.setLong(9, msg.getUpdatedAt());
                ps.setString(10, msg.getTokenUsage());
                ps.setString(11, msg.getMeta());
                ps.executeUpdate();
            }

            // 2. 所有 parts
            if (parts != null && !parts.isEmpty()) {
                String partSql = """
                    INSERT INTO message_parts (id, message_id, session_id, kind, content, meta, status, seq, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = conn.prepareStatement(partSql)) {
                    for (MessagePartEntity p : parts) {
                        ps.setString(1, p.getId());
                        ps.setString(2, p.getMessageId());
                        ps.setString(3, p.getSessionId());
                        ps.setString(4, p.getKind());
                        ps.setString(5, p.getContent());
                        ps.setString(6, p.getMeta());
                        ps.setString(7, p.getStatus());
                        ps.setInt(8, p.getSeq());
                        ps.setLong(9, p.getCreatedAt());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }

            conn.commit();
        } catch (Exception e) {
            LOG.error("saveMessageWithParts failed", e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                SqliteDatabaseManager.closeConnection(conn);
            }
        }
    }

    /** 单独保存一个 part */
    public static void savePart(MessagePartEntity part) {
        String sql = """
            INSERT INTO message_parts (id, message_id, session_id, kind, content, meta, status, seq, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, part.getId());
            ps.setString(2, part.getMessageId());
            ps.setString(3, part.getSessionId());
            ps.setString(4, part.getKind());
            ps.setString(5, part.getContent());
            ps.setString(6, part.getMeta());
            ps.setString(7, part.getStatus());
            ps.setInt(8, part.getSeq());
            ps.setLong(9, part.getCreatedAt());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("savePart failed", e);
        }
    }

    /** 更新 part 的 content 和 status（流式追加用） */
    public static void updatePartContent(String partId, String content, String status) {
        String sql = "UPDATE message_parts SET content = ?, status = ? WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, status);
            ps.setString(3, partId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("updatePartContent failed", e);
        }
    }

    /** 更新 tool part 的 meta（包含 toolOutput）和 status（pending → done） */
    public static void updatePartMetaAndStatus(String partId, String meta, String status) {
        String sql = "UPDATE message_parts SET meta = ?, status = ? WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, meta);
            ps.setString(2, status);
            ps.setString(3, partId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.error("updatePartMetaAndStatus failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Query
    // ═══════════════════════════════════════════════════════════════

    /** 获取会话消息头列表（不含 parts） */
    public static List<ChatMessageEntity> getRecentMessages(String sessionId, int limit, int offset) {
        List<ChatMessageEntity> list = new ArrayList<>();
        String sql = """
            SELECT id, session_id, role, seq, model_id, tokens, qa_round,
                   created_at, updated_at, token_usage, meta
            FROM messages
            WHERE session_id = ?
              AND (meta IS NULL OR meta LIKE '%compressed_summary%' OR meta NOT LIKE '%compressed%')
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessageEntity m = mapMessage(rs);
                    list.add(m);
                }
            }
            java.util.Collections.reverse(list);
        } catch (Exception e) {
            LOG.error("getRecentMessages failed", e);
        }
        return list;
    }

    /** 按时间正序获取消息（从最早的开始，offset 从第一条消息算起） */
    public static List<ChatMessageEntity> getMessagesByRange(String sessionId, int offset, int limit) {
        List<ChatMessageEntity> list = new ArrayList<>();
        String sql = """
            SELECT id, session_id, role, seq, model_id, tokens, qa_round,
                   created_at, updated_at, token_usage, meta
            FROM messages
            WHERE session_id = ?
            ORDER BY created_at ASC
            LIMIT ? OFFSET ?
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessageEntity m = mapMessage(rs);
                    list.add(m);
                }
            }
        } catch (Exception e) {
            LOG.error("getMessagesByRange failed", e);
        }
        return list;
    }

    /** 获取一条消息的所有 parts（按 seq 排序） */
    public static List<MessagePartEntity> getMessageParts(String messageId) {
        List<MessagePartEntity> list = new ArrayList<>();
        String sql = """
            SELECT id, message_id, session_id, kind, content, meta, status, seq, created_at
            FROM message_parts
            WHERE message_id = ?
            ORDER BY seq ASC
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPart(rs));
                }
            }
        } catch (Exception e) {
            LOG.error("getMessageParts failed", e);
        }
        return list;
    }

    /** 获取会话所有消息 + parts（Map 结构，用于渲染历史） */
    public static Map<ChatMessageEntity, List<MessagePartEntity>> getSessionMessagesWithParts(
            String sessionId, int limit, int offset) {
        Map<ChatMessageEntity, List<MessagePartEntity>> result = new LinkedHashMap<>();
        List<ChatMessageEntity> messages = getRecentMessages(sessionId, limit, offset);
        for (ChatMessageEntity msg : messages) {
            List<MessagePartEntity> parts = getMessageParts(msg.getId());
            result.put(msg, parts);
        }
        // 合并同一 qa_round 内连续的 assistant 消息：实时流中工具调用边界会为同一轮 AI 回复
        // 拆成多条 messages 行，回显时每条都会渲染成独立帧（出现多个 L 头像）。这里把同轮连续的
        // assistant 段合并为单条，保证历史回显只有一个头像、parts 按时间序排列。
        Map<ChatMessageEntity, List<MessagePartEntity>> merged = new LinkedHashMap<>();
        ChatMessageEntity last = null;
        for (Map.Entry<ChatMessageEntity, List<MessagePartEntity>> entry : result.entrySet()) {
            ChatMessageEntity rec = entry.getKey();
            List<MessagePartEntity> parts = entry.getValue();
            if (last != null && "assistant".equals(rec.getRole()) && "assistant".equals(last.getRole())
                    && rec.getQaRound() == last.getQaRound()) {
                merged.get(last).addAll(parts);
                continue;
            }
            merged.put(rec, parts);
            last = rec;
        }
        return merged;
    }

    public static int getMessagesCount(String sessionId) {
        String sql = """
            SELECT COUNT(*) AS cnt FROM messages
            WHERE session_id = ?
              AND (meta IS NULL OR meta LIKE '%compressed_summary%' OR meta NOT LIKE '%compressed%')
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("cnt");
            }
        } catch (Exception e) {
            LOG.error("getMessagesCount failed", e);
        }
        return 0;
    }

    /** 按 QA 轮次删除消息 — 无外键，手动按 message_id 级联清理 parts */
    public static void deleteMessagesByRound(String sessionId, int qaRound) {
        String delParts = "DELETE FROM message_parts WHERE message_id IN (SELECT id FROM messages WHERE session_id = ? AND qa_round = ?)";
        String delMessages = "DELETE FROM messages WHERE session_id = ? AND qa_round = ?";
        int deleted = 0;
        Connection conn = null;
        try {
            conn = SqliteDatabaseManager.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(delParts)) {
                ps.setString(1, sessionId);
                ps.setInt(2, qaRound);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(delMessages)) {
                ps.setString(1, sessionId);
                ps.setInt(2, qaRound);
                deleted = ps.executeUpdate();
            }
            conn.commit();
            System.out.println("[DB] 删除 round=" + qaRound + " 共 " + deleted + " 条消息");
        } catch (Exception e) {
            LOG.error("deleteMessagesByRound failed", e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                SqliteDatabaseManager.closeConnection(conn);
            }
        }
    }

    /** 按关键词搜索消息（搜索 parts 的 content + meta 中的 toolName） */
    public static List<ChatMessageEntity> searchMessages(String sessionId, String keyword, int limit, int offset) {
        List<ChatMessageEntity> list = new ArrayList<>();
        String sql = """
            SELECT DISTINCT m.id, m.session_id, m.role, m.seq, m.model_id, m.tokens, m.qa_round,
                   m.created_at, m.updated_at, m.token_usage, m.meta
            FROM messages m
            JOIN message_parts p ON p.message_id = m.id
            WHERE m.session_id = ?
              AND (p.content LIKE ? OR p.meta LIKE ?)
            ORDER BY m.seq ASC
            LIMIT ? OFFSET ?
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            String like = "%" + keyword + "%";
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapMessage(rs));
                }
            }
        } catch (Exception e) {
            LOG.error("searchMessages failed", e);
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    // Compression (压缩持久化)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 标记指定 qaRound 之前的所有消息为已压缩（不含 system 消息，不含已有摘要消息）
     */
    public static void markMessagesCompressedBeforeRound(String sessionId, int qaRound) {
        String selectSql = "SELECT id, meta FROM messages WHERE session_id = ? AND qa_round < ? AND role != 'system' AND (meta IS NULL OR meta NOT LIKE '%compressed_summary%true%')";
        String updateSql = "UPDATE messages SET meta = ? WHERE id = ?";
        try (Connection conn = SqliteDatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            List<String[]> toUpdate = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, sessionId);
                ps.setInt(2, qaRound);
                try (ResultSet rs = ps.executeQuery()) {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String existingMeta = rs.getString("meta");
                        com.google.gson.JsonObject metaObj;
                        if (existingMeta != null && !existingMeta.isBlank()) {
                            try {
                                metaObj = com.google.gson.JsonParser.parseString(existingMeta).getAsJsonObject();
                            } catch (Exception e) {
                                metaObj = new com.google.gson.JsonObject();
                            }
                        } else {
                            metaObj = new com.google.gson.JsonObject();
                        }
                        metaObj.addProperty("compressed", true);
                        toUpdate.add(new String[]{id, gson.toJson(metaObj)});
                    }
                }
            }
            if (!toUpdate.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    for (String[] pair : toUpdate) {
                        ps.setString(1, pair[1]);
                        ps.setString(2, pair[0]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            LOG.error("Failed to mark messages compressed by qaRound for session: " + sessionId, e);
        }
    }

    /**
     * 查找现有的压缩摘要消息（meta.compressed_summary = true）
     */
    public static ChatMessageEntity findSummaryMessage(String sessionId) {
        String sql = """
            SELECT id, session_id, role, seq, model_id, tokens, qa_round,
                   created_at, updated_at, token_usage, meta
            FROM messages
            WHERE session_id = ? AND meta LIKE '%compressed_summary%true%'
            ORDER BY created_at ASC
            LIMIT 1
            """;
        try (Connection conn = SqliteDatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapMessage(rs);
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to find summary message for session: " + sessionId, e);
        }
        return null;
    }

    /**
     * 插入或更新压缩摘要消息
     * - 如果已有摘要消息，更新其 content 和 meta
     * - 如果没有，插入新的摘要消息（放在第一条非压缩消息之前）
     */
    public static void upsertSummaryMessage(String sessionId, String summary, int compressedCount) {
        ChatMessageEntity existing = findSummaryMessage(sessionId);
        long now = System.currentTimeMillis();
        String summaryMeta = "{\"compressed_summary\": true, \"compressed_count\": " + compressedCount + "}";

        if (existing != null) {
            // 更新现有摘要消息的 content（更新 text part）
            String updateMsgSql = "UPDATE messages SET meta = ?, updated_at = ? WHERE id = ?";
            String updatePartSql = "UPDATE message_parts SET content = ? WHERE message_id = ? AND kind = 'text'";
            try (Connection conn = SqliteDatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(updateMsgSql)) {
                    ps.setString(1, summaryMeta);
                    ps.setLong(2, now);
                    ps.setString(3, existing.getId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(updatePartSql)) {
                    ps.setString(1, summary);
                    ps.setString(2, existing.getId());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                LOG.error("Failed to update summary message for session: " + sessionId, e);
            }
        } else {
            // 插入新的摘要消息
            // 找到第一条非压缩消息的 created_at，摘要放在它前面
            long firstNonCompressedCreatedAt = 0;
            int seq = 0;
            String findPosSql = """
                SELECT created_at, seq FROM messages
                WHERE session_id = ?
                  AND (meta IS NULL OR meta NOT LIKE '%compressed%true%')
                ORDER BY created_at ASC
                LIMIT 1
                """;
            try (Connection conn = SqliteDatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(findPosSql)) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        firstNonCompressedCreatedAt = rs.getLong("created_at");
                        seq = rs.getInt("seq");
                    }
                }
            } catch (SQLException e) {
                LOG.error("Failed to find position for summary message", e);
            }

            long summaryCreatedAt = firstNonCompressedCreatedAt > 0
                ? firstNonCompressedCreatedAt - 1 : now;

            String msgId = getUUID();
            String partId = getUUID();
            String insertMsgSql = """
                INSERT INTO messages (id, session_id, role, seq, model_id, tokens, qa_round,
                                      created_at, updated_at, token_usage, meta)
                VALUES (?, ?, 'user', ?, NULL, 0, 0, ?, ?, NULL, ?)
                """;
            String insertPartSql = """
                INSERT INTO message_parts (id, message_id, session_id, kind, content, meta, status, seq, created_at)
                VALUES (?, ?, ?, 'text', ?, NULL, 'done', 0, ?)
                """;

            try (Connection conn = SqliteDatabaseManager.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(insertMsgSql)) {
                    ps.setString(1, msgId);
                    ps.setString(2, sessionId);
                    ps.setInt(3, seq > 0 ? seq - 1 : 0);
                    ps.setLong(4, summaryCreatedAt);
                    ps.setLong(5, now);
                    ps.setString(6, summaryMeta);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(insertPartSql)) {
                    ps.setString(1, partId);
                    ps.setString(2, msgId);
                    ps.setString(3, sessionId);
                    ps.setString(4, summary);
                    ps.setLong(5, summaryCreatedAt);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                LOG.error("Failed to insert summary message for session: " + sessionId, e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private static ChatMessageEntity mapMessage(ResultSet rs) throws SQLException {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(rs.getString("id"));
        m.setSessionId(rs.getString("session_id"));
        m.setRole(rs.getString("role"));
        m.setSeq(rs.getInt("seq"));
        m.setModelId(rs.getString("model_id"));
        m.setTokens(rs.getInt("tokens"));
        m.setQaRound(rs.getInt("qa_round"));
        m.setCreatedAt(rs.getLong("created_at"));
        m.setUpdatedAt(rs.getLong("updated_at"));
        m.setTokenUsage(rs.getString("token_usage"));
        m.setMeta(rs.getString("meta"));
        return m;
    }

    private static MessagePartEntity mapPart(ResultSet rs) throws SQLException {
        MessagePartEntity p = new MessagePartEntity();
        p.setId(rs.getString("id"));
        p.setMessageId(rs.getString("message_id"));
        p.setSessionId(rs.getString("session_id"));
        p.setKind(rs.getString("kind"));
        p.setContent(rs.getString("content"));
        p.setMeta(rs.getString("meta"));
        p.setStatus(rs.getString("status"));
        p.setSeq(rs.getInt("seq"));
        p.setCreatedAt(rs.getLong("created_at"));
        return p;
    }

    public static String getUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
