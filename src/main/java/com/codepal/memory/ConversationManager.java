package com.codepal.memory;

import com.codepal.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理器 —— 对 {@code conversationHistory} 统一收口，杜绝直接操弄裸 List。
 *
 * <p>背景：ChatPanel 中 conversationHistory 有 28+ 处直接 add/clear/get 调用，
 * 导致 6 个 bug 都需要在调用点打补丁。收敛到这里后，清理中断轮次、压缩边界等逻辑
 * 只需实现一次。
 *
 * <p>线程安全：所有读写 {@code messages} 的方法均 synchronized，
 * 锁对象为 {@code messages} 自身。
 *
 * @author 水龙吟
 */
public class ConversationManager {

    private final List<ChatMessage> messages = new ArrayList<>();

    // ==================== 基础操作 ====================

    /** 获取底层消息列表（只读意图，调用方不应直接修改） */
    public synchronized List<ChatMessage> getMessages() {
        return messages;
    }

    /** 快照一份副本，用于 API 请求（避免并发修改） */
    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    // ==================== 系统消息 ====================

    /** 初始化：添加系统消息（会话头） */
    public synchronized void initSystem(String systemPrompt) {
        messages.clear();
        messages.add(new ChatMessage("system", systemPrompt));
    }

    /** 更新系统提示词（保留第一条 system 消息位置不变） */
    public synchronized void updateSystemPrompt(String newPrompt) {
        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            messages.set(0, new ChatMessage("system", newPrompt));
        }
    }

    /** 清空并重新初始化系统消息 */
    public synchronized void reset(String systemPrompt) {
        messages.clear();
        messages.add(new ChatMessage("system", systemPrompt));
    }

    /** 获取系统消息（第一条），不存在返回 null */
    public synchronized ChatMessage getSystemMessage() {
        return (!messages.isEmpty() && "system".equals(messages.get(0).getRole()))
                ? messages.get(0) : null;
    }

    // ==================== 添加消息 ====================

    public synchronized void add(ChatMessage msg) {
        messages.add(msg);
    }

    public synchronized void addUserMessage(String text) {
        messages.add(new ChatMessage("user", text));
    }

    // ==================== 查询 ====================

    /** 最后一条消息 */
    public synchronized ChatMessage last() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** 最后一条 user 角色消息 */
    public synchronized ChatMessage lastUser() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) return messages.get(i);
        }
        return null;
    }

    /** 查出最后 N 条消息（用于去重检查等） */
    public synchronized List<ChatMessage> tail(int n) {
        int from = Math.max(0, messages.size() - n);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    // ==================== 去重 ====================

    /** 检查最后一条 user 消息内容是否与给定文本相同（防连击） */
    public synchronized boolean isLastUserMessageEqualTo(String text) {
        ChatMessage last = last();
        return last != null && "user".equals(last.getRole()) && text.equals(last.getContent());
    }

    // ==================== 中断轮次清理 ====================

    /**
     * 清理被用户中断的工具调用轮次：找到最后两个 user 消息之间的一切并移除。
     *
     * <p>典型场景：用户点击停止 → 再次发送消息，旧的工具调用链残留在新 user
     * 消息之前，模型会"继续"未完成的任务。调用此方法后，对话以干净的
     * [user: 旧请求] + [user: 新请求] 结束。
     */
    public synchronized void trimInterruptedTail() {
        if (messages.size() < 3) return;

        // 1. 从末尾找最后一个 user 消息（刚添加的新消息）
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) return;

        // 2. 继续找上一个 user 消息（发起被中断任务的用户请求）
        int prevUserIdx = -1;
        for (int i = lastUserIdx - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                prevUserIdx = i;
                break;
            }
        }
        if (prevUserIdx < 0) return;

        // 3. 两个 user 消息之间没有工具链 → 无需清理
        if (lastUserIdx - prevUserIdx <= 1) return;

        // 4. 清理 [prevUserIdx+1, lastUserIdx) 的所有消息
        messages.subList(prevUserIdx + 1, lastUserIdx).clear();
    }

    // ==================== 非 system 消息计数 ====================

    public synchronized long countNonSystem() {
        return messages.stream().filter(m -> !"system".equals(m.getRole())).count();
    }
}
