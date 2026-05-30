package com.loongc.db;

import com.loongc.model.ChatMessageEntity;
import com.loongc.model.ChatSessionEntity;
import com.intellij.openapi.diagnostic.Logger;
import com.loongc.settings.LoongCSettings;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DBChatHistoryRepository {

    private static final Logger LOG = Logger.getInstance(DBChatHistoryRepository.class);

    public static void main(String[] args) {

    }

    // 1. 异步落盘：大模型每回一句，或者用户每发一句，立刻调用此方法
    public static void saveMessage(ChatMessageEntity message) {
        System.out.println("saveMessage:"+message.toString());
//        if(!LoongCSettings.getInstance().isEnableMessagePersistence()){
//            LOG.info("Message persistence is disabled");
//            System.err.println("Message persistence is disabled");
//            return;
//        }
        // 纯 Java 异步线程池执行，不阻塞 UI

        String sql = "INSERT INTO chat_messages (id,conversation_id, role, content,tool_calls_json,tool_call_id,name, reasoning_content,created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = H2DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, message.getId());
            pstmt.setString(2, message.getConversationId());
            pstmt.setString(3, message.getRole());
            pstmt.setString(4, message.getContent());
            pstmt.setString(5, message.getToolCallsJson());
            pstmt.setString(6, message.getToolCallId());
            pstmt.setString(7, message.getName());
            pstmt.setString(8, message.getReasoningContent());
            pstmt.setTimestamp(9, message.getCreatedAt());
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("saveMessage插入异常。"+e.getMessage());
            e.printStackTrace();
        }
    }

    // 2. 分页查询：用于滑动窗口初始化，或者用户向上滚动鼠标查看更早的历史
    public static List<ChatMessageEntity> getRecentMessages(String conversationId, int limit, int offset) {
        List<ChatMessageEntity> list = new ArrayList<>();
//        if(!LoongCSettings.getInstance().isEnableMessagePersistence()){
//            return list;
//        }
        // 按时间倒序查出最近的，然后再在内存里反转，保证顺序是正确的
        String sql = "SELECT id,role, content,tool_calls_json,tool_call_id,name,reasoning_content,created_at FROM chat_messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = H2DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            pstmt.setInt(2, limit);
            pstmt.setInt(3, offset);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new ChatMessageEntity(
                            rs.getString("id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("tool_calls_json"),
                            rs.getString("tool_call_id"),
                            rs.getString("name"),
                            rs.getString("reasoning_content"),
                            rs.getTimestamp("created_at")
                            )
                    );
                }
            }
            // 比如原本查出的是 [今天, 昨天, 前天]，反转后变成 [前天, 昨天, 今天]
            java.util.Collections.reverse(list);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static int getMessagesCount(String conversationId) {
        List<ChatMessageEntity> list = new ArrayList<>();
        // 按时间倒序查出最近的，然后再在内存里反转，保证顺序是正确的
        String sql = "SELECT count(*) as message_count FROM chat_messages WHERE conversation_id = ?";
        int messageCount = 0;
        try (Connection conn = H2DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, conversationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messageCount = rs.getInt("message_count");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return messageCount;
    }

    // 创建会话
    public static ChatSessionEntity createSession(String name) {
//        if(!LoongCSettings.getInstance().isEnableMessagePersistence()){
//            return null;
//        }
        String id = UUID.randomUUID().toString().replace("-", "");
        ChatSessionEntity session = new ChatSessionEntity(id, name,new Timestamp(System.currentTimeMillis()));
        String sql = "INSERT INTO conversations (id, name,created_at) VALUES (?, ?, ?)";
        try (Connection conn = H2DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setTimestamp(3, session.getCreatedAt());
            ps.executeUpdate();
            return session;
        } catch (SQLException e) {
            LOG.error("Failed to create session", e);
            return null;
        }
    }

    // 更新会话名称
    public void updateSessionName(String id, String name) {
        String sql = "UPDATE conversations SET name = ? WHERE id = ?";
        try (Connection conn = H2DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Failed to update session title", e);
        }
    }

    // 删除会话
    public static void deleteSession(String id) {
        String sql = "DELETE FROM conversations WHERE id = ?";
        try (Connection conn = H2DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Failed to delete session", e);
        }

        String sql2 = "DELETE FROM chat_messages WHERE conversation_id = ?";
        try (Connection conn = H2DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("Failed to delete chatMessages", e);
        }
    }

    // 会话列表
    public static List<ChatSessionEntity> listSessions() {
        List<ChatSessionEntity> list = new ArrayList<>();
        // 按时间倒序查出最近的，然后再在内存里反转，保证顺序是正确的
        String sql = "SELECT id,name,created_at,(SELECT COUNT(*) FROM chat_messages WHERE chat_messages.conversation_id = conversations.id) AS chat_message_count FROM conversations ORDER BY created_at DESC";
        try (Connection conn = H2DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(
                            new ChatSessionEntity(
                                    rs.getString("id"),
                                    rs.getString("name"),
                                    rs.getTimestamp("created_at"),
                                    rs.getInt("chat_message_count")
                            ));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // 最近一条会话
    public static ChatSessionEntity getRecentConversation() {
        ChatSessionEntity entity = null;
        // 按时间倒序查出最近的，然后再在内存里反转，保证顺序是正确的
        String sql = "SELECT id,name FROM conversations ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = H2DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entity = new ChatSessionEntity(
                            rs.getString("id"),
                            rs.getString("name")
                    );
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return entity;
    }

    public static String getUUID(){
        return UUID.randomUUID().toString().replace("-", "");
    }
}
