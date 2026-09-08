package com.codepal.session;

import com.codepal.common.Constant;
import com.codepal.memory.ConversationManager;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatMessageEntity;
import com.codepal.db.DBChatHistoryRepository;

import java.util.List;

/**
 * 迭代守卫 —— 管理工具迭代限制和断点续传。
 *
 * <p>功能：
 * <ol>
 *   <li><b>迭代限制</b>：防止模型陷入无限循环
 *     <ul>
 *       <li>相同工具连续调用超过 MAX_CONSECUTIVE_SAME_TOOL 次 → 停止</li>
 *       <li>单轮工具调用总数超过 MAX_TOOL_CALLS_PER_ROUND 次 → 停止</li>
 *     </ul>
 *   </li>
 *   <li><b>断点续传</b>：用户停止或异常关闭时，写入带 SYSTEM_AUTO 标识的 user 消息，
 *       模型下次从上下文即可感知任务中断，自行决定是否询问用户继续。</li>
 * </ol>
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>不污染系统提示词</b>：所有中断通知都通过 user 消息携带 SYSTEM_AUTO 标识，
 *       不会修改 system prompt，也不会影响后续新会话。</li>
 *   <li><b>幂等</b>：同一轮次重复调用 stop 不会重复插入中断消息。</li>
 * </ul>
 *
 * @author CP
 */
public class IterationGuard {

    // ==================== 常量 ====================

    public static final int MAX_CONSECUTIVE_SAME_TOOL = 20;

    public static final int MAX_TOOL_CALLS_PER_ROUND = 100;

    public static final String SYSTEM_AUTO_PREFIX = "[SYSTEM AUTO] ";

    public static final String SYSTEM_AUTO_STOP_REASON_USER = "user_stop";
    public static final String SYSTEM_AUTO_STOP_REASON_CRASH = "crash_recovery";
    public static final String SYSTEM_AUTO_STOP_REASON_ITERATION_LIMIT = "iteration_limit";

    // ==================== 实例字段 ====================

    private final ConversationManager conversationManager;
    private final ChatSessionManager chatSessionManager;

    private int currentRoundToolCallCount = 0;
    private String lastToolName = null;
    private int consecutiveSameToolCount = 0;
    private boolean stopMessageInserted = false;

    // ==================== 构造 ====================

    public IterationGuard(ConversationManager conversationManager, ChatSessionManager chatSessionManager) {
        this.conversationManager = conversationManager;
        this.chatSessionManager = chatSessionManager;
    }

    // ==================== 迭代限制 ====================

    /**
     * 重置当前轮次的计数（新的一轮模型回复开始时调用）。
     */
    public void resetRoundCounters() {
        currentRoundToolCallCount = 0;
        lastToolName = null;
        consecutiveSameToolCount = 0;
        stopMessageInserted = false;
        nudgeInserted = false;
    }

    /**
     * 记录一次工具调用并检查是否超限。
     *
     * @param toolName 工具名称
     * @return true 表示超限应停止，false 表示正常
     */
    public boolean recordToolCallAndCheck(String toolName) {
        currentRoundToolCallCount++;

        if (toolName != null && toolName.equals(lastToolName)) {
            consecutiveSameToolCount++;
        } else {
            lastToolName = toolName;
            consecutiveSameToolCount = 1;
        }

        if (currentRoundToolCallCount >= MAX_TOOL_CALLS_PER_ROUND) {
            return true;
        }
        if (consecutiveSameToolCount >= MAX_CONSECUTIVE_SAME_TOOL) {
            return true;
        }
        return false;
    }

    /**
     * 获取超限原因描述（用于用户提示）。
     */
    public String getLimitReason() {
        if (currentRoundToolCallCount >= MAX_TOOL_CALLS_PER_ROUND) {
            return "单轮工具调用次数已达上限（" + MAX_TOOL_CALLS_PER_ROUND + " 次）";
        }
        if (consecutiveSameToolCount >= MAX_CONSECUTIVE_SAME_TOOL) {
            return "相同工具连续调用已达上限（" + MAX_CONSECUTIVE_SAME_TOOL + " 次：" + lastToolName + "）";
        }
        return "";
    }

    // ==================== 断点续传 ====================

    /**
     * 用户点击停止时，写入带 SYSTEM_AUTO 标识的 user 消息，标记任务被用户中断。
     * 幂等：同一轮次重复调用不会重复插入。
     */
    public void insertUserStopMessage() {
        if (stopMessageInserted) return;
        cleanupOrphanTools();
        ensureConversationIntegrity(conversationManager.getMessages());
        String content = SYSTEM_AUTO_PREFIX + SYSTEM_AUTO_STOP_REASON_USER + "\n"
                + "任务已被用户手动中断。当前已生成的回复和已执行的工具结果均已保存。"
                + "请在用户下一次发言时根据上下文判断是否继续之前的任务，如果用户下次发言没有明确提继续任务，你需要询问一下。";
        insertSystemAutoMessage(content);
        stopMessageInserted = true;
    }

    /**
     * 超限停止时，写入带 SYSTEM_AUTO 标识的 user 消息。
     */
    public void insertIterationLimitMessage() {
        if (stopMessageInserted) return;
        cleanupOrphanTools();
        ensureConversationIntegrity(conversationManager.getMessages());
        String reason = getLimitReason();
        String content = SYSTEM_AUTO_PREFIX + SYSTEM_AUTO_STOP_REASON_ITERATION_LIMIT + "\n"
                + "任务已被系统自动停止：" + reason + "。\n"
                + "请在用户下一次发言时根据上下文判断是否继续之前的任务，如果用户下次发言没有明确提继续任务，你需要询问一下。";
        insertSystemAutoMessage(content);
        stopMessageInserted = true;
    }

    /**
     * 软提醒（不停止）：当相同工具连续调用达到预警阈值、或单轮总次数过半时，
     * 以 user 消息形式向模型注入一条系统提醒——目的是让模型"清醒一下"：
     * 核对是否陷入重复循环；若确有需要可继续调用，否则应重新读代码梳理逻辑或向用户提问。
     *
     * <p>触发时机：每次工具调用记录后由 ChatPanel 调用 {@link #shouldNudge()} 判断，
     * 同一轮次内最多提醒一次（nudgeInserted 防重入）。
     */
    public static final int NUDGE_CONSECUTIVE_SAME_TOOL = 8;   // 相同工具连续 8 次 → 提醒
    public static final int NUDGE_TOOL_CALLS_PER_ROUND = 50;  // 单轮总数过半 → 提醒

    private boolean nudgeInserted = false;

    /**
     * 判断当前是否应给模型发软提醒（不停止执行）。
     */
    public boolean shouldNudge() {
        if (nudgeInserted) return false;
        return consecutiveSameToolCount >= NUDGE_CONSECUTIVE_SAME_TOOL
                || currentRoundToolCallCount >= NUDGE_TOOL_CALLS_PER_ROUND;
    }

    /**
     * 注入软提醒消息（以 user 角色模拟系统提示）。幂等：同一轮次只插一次。
     */
    public void insertNudgeMessage() {
        if (nudgeInserted) return;
        String detail;
        if (consecutiveSameToolCount >= NUDGE_CONSECUTIVE_SAME_TOOL) {
            detail = "你已连续调用 " + consecutiveSameToolCount + " 次「" + lastToolName + "」";
        } else {
            detail = "本轮工具调用总数已达 " + currentRoundToolCallCount + " 次";
        }
        String content = "[SYSTEM NUDDGE] 系统提醒\n"
                + "注意：" + detail + "。请先停下来核对：\n"
                + "1. 如果连续出错或结果不符合预期，请重新阅读相关代码文件核实真实内容，不要凭记忆反复重试相同的参数；\n"
                + "2. 编辑失败时先 read_file_range 查看目标片段的真实文本再修改；\n"
                + "3. 如果你认为确实需要继续调用，可以继续；但如果已经陷入不清楚的循环，请及时醒悟：重新梳理思路，或直接向用户提问确认方向。";
        insertSystemAutoMessage(content);
        nudgeInserted = true;
    }

    /**
     * 启动时检测：如果会话最后一条消息是 assistant(tool_calls) 或 tool 结果不完整，
     * 说明上次异常关闭，插入中断恢复消息。
     *
     * <p>调用时机：会话加载完成后，发送第一条消息前。
     */
    public void detectAndRecoverCrash() {
        List<ChatMessage> msgs = conversationManager.getMessages();
        if (msgs == null || msgs.isEmpty()) return;

        ChatMessage last = null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (!"system".equals(m.getRole())) {
                last = m;
                break;
            }
        }
        if (last == null) return;

        String role = last.getRole();
        boolean hasIncompleteToolCalls = "assistant".equals(role)
                && last.getTool_calls() != null && !last.getTool_calls().isEmpty();
        boolean lastIsTool = "tool".equals(role);

        if (hasIncompleteToolCalls || lastIsTool) {
            cleanupOrphanTools();
            ensureConversationIntegrity(conversationManager.getMessages());
            String content = SYSTEM_AUTO_PREFIX + SYSTEM_AUTO_STOP_REASON_CRASH + "\n"
                    + "检测到上次会话异常中断（可能是插件关闭或程序崩溃）。"
                    + "当前上下文中保留了中断前的消息和已执行的工具结果。"
                    + "请不要重复调用已经执行过的工具。根据已有进度，在下次用户发言时，"
                    + "向用户报告当前状态并询问是否继续之前的任务。";
            insertSystemAutoMessage(content);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 清理不完整的工具调用轮次：
     * <ol>
     *   <li>有 tool_calls 但结果不完整的 assistant 消息 + 其后续所有 tool 消息</li>
     *   <li>没有前置 assistant(tool_calls) 的孤儿 tool 消息</li>
     * </ol>
     * 只清理最后一个不完整的轮次，保留之前所有正常完成的任务进度。
     * 让模型从上下文感知中断状态，自行决定如何恢复。
     *
     * <p>只在中断时调用一次，正常流程不会产生不完整轮次。
     */
    private void cleanupOrphanTools() {
        List<ChatMessage> msgs = conversationManager.getMessages();
        if (msgs == null || msgs.isEmpty()) return;

        // 第一步：从后往前找最后一条 assistant 消息
        int lastAssistantIdx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if ("assistant".equals(msgs.get(i).getRole())) {
                lastAssistantIdx = i;
                break;
            }
        }

        // 第二步：如果最后一条 assistant 有 tool_calls，检查结果是否完整
        if (lastAssistantIdx >= 0) {
            ChatMessage lastAssistant = msgs.get(lastAssistantIdx);
            if (lastAssistant.getTool_calls() != null && !lastAssistant.getTool_calls().isEmpty()) {
                int expectedCount = lastAssistant.getTool_calls().size();
                // 统计后面有多少个 tool 结果
                int actualCount = 0;
                for (int i = lastAssistantIdx + 1; i < msgs.size(); i++) {
                    if ("tool".equals(msgs.get(i).getRole())) {
                        actualCount++;
                    } else {
                        break;
                    }
                }
                // 结果不完整 → 删除这条 assistant + 后面所有 tool 消息
                if (actualCount < expectedCount) {
                    System.out.println("[IterationGuard] 清理不完整的工具调用轮次：tool_calls=" + expectedCount
                            + "，实际结果=" + actualCount + "，删除 assistant + 不完整的 tool 消息");
                    for (int i = msgs.size() - 1; i >= lastAssistantIdx; i--) {
                        msgs.remove(i);
                    }
                    // 清理完成后直接返回，不需要再清理孤儿了
                    return;
                }
            }
        }

        // 第三步：清理孤儿 tool 消息（没有前置 assistant 的 tool 消息）
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage msg = msgs.get(i);
            if (!"tool".equals(msg.getRole())) continue;

            boolean hasPrecedingToolCalls = false;
            for (int j = i - 1; j >= 0; j--) {
                ChatMessage prev = msgs.get(j);
                if ("assistant".equals(prev.getRole())
                        && prev.getTool_calls() != null
                        && !prev.getTool_calls().isEmpty()) {
                    hasPrecedingToolCalls = true;
                    break;
                }
                if (!"tool".equals(prev.getRole())) {
                    break;
                }
            }

            if (!hasPrecedingToolCalls) {
                msgs.remove(i);
            }
        }
    }

    // ==================== 对话完整性修复（三遍管道） ====================

    /**
     * 三遍修复管道，确保对话消息在发送 API 前结构完整。
     *
     * <p>借鉴 Grok Build 的 {@code ensure_conversation_integrity} 设计：
     * <ol>
     *   <li><b>去重</b>：同一 tool_call_id 有多个 ToolResult → 只保留最后一个</li>
     *   <li><b>删孤儿</b>：ToolResult 无对应 Assistant tool_calls → 删除（已有 {@link #cleanupOrphanTools}）</li>
     *   <li><b>补缺口</b>：Assistant 有 tool_calls 但缺少 ToolResult → 插入合成结果</li>
     * </ol>
     *
     * <p>调用时机：每次发送 API 请求前（{@code ChatPanel.sanitizeMessages}），
     * 以及用户中断/崩溃恢复时（{@link #insertUserStopMessage} / {@link #detectAndRecoverCrash}）。
     *
     * @param msgs 消息列表（原地修改）
     * @return 修复的消息数量（去重删除数 + 合成插入数）
     */
    public static int ensureConversationIntegrity(List<ChatMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return 0;
        int count = 0;
        count += dedupDuplicateToolResults(msgs);
        count += repairDanglingToolCalls(msgs);
        return count;
    }

    /**
     * 第一遍：去重 —— 同一 tool_call_id 有多个 ToolResult 时，只保留最后一个。
     *
     * <p>场景：用户中断时工具已执行完，结果返回了，但中断前的合成结果和真实结果
     * 同时存在，导致 API 报错 "each tool_use must have a single result"。
     *
     * <p>规则：只检查紧跟在 Assistant 后面的连续 ToolResult 块。同一 tool_call_id
     * 出现多次时，保留最后一次出现的，删除之前的。
     *
     * @param msgs 消息列表（原地修改）
     * @return 删除的重复条目数
     */
    static int dedupDuplicateToolResults(List<ChatMessage> msgs) {
        int removed = 0;
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage msg = msgs.get(i);
            if (!"assistant".equals(msg.getRole())
                    || msg.getTool_calls() == null
                    || msg.getTool_calls().isEmpty()) {
                i++;
                continue;
            }

            // 收集该 assistant 声明的所有 tool_call_id
            java.util.Set<String> callIds = new java.util.LinkedHashSet<>();
            for (ChatMessage.ToolCall tc : msg.getTool_calls()) {
                if (tc.getId() != null) {
                    callIds.add(tc.getId());
                }
            }

            // 扫描紧跟在后面的连续 ToolResult，记录每个 id 最后一次出现的位置
            java.util.Map<String, Integer> lastPos = new java.util.LinkedHashMap<>();
            java.util.List<Integer> duplicatePositions = new java.util.ArrayList<>();
            int j = i + 1;
            while (j < msgs.size() && "tool".equals(msgs.get(j).getRole())) {
                String tid = msgs.get(j).getTool_call_id();
                if (tid != null && callIds.contains(tid)) {
                    if (lastPos.containsKey(tid)) {
                        // 之前出现过 → 标记为重复
                        duplicatePositions.add(lastPos.get(tid));
                    }
                    lastPos.put(tid, j);
                }
                j++;
            }

            // 从后往前删除重复项（避免索引偏移）
            if (!duplicatePositions.isEmpty()) {
                duplicatePositions.sort(java.util.Collections.reverseOrder());
                for (int pos : duplicatePositions) {
                    msgs.remove(pos);
                    removed++;
                }
                // 重新计算 j（删除后索引前移了）
                j -= removed;
            }

            i = j; // 跳过已处理的 ToolResult 块
        }
        return removed;
    }

    /**
     * 第三遍：补缺口 —— Assistant 有 tool_calls 但缺少对应 ToolResult 时，
     * 插入合成 ToolResult 告知模型工具未执行。
     *
     * <p>场景：用户中断模型输出时，assistant 消息已包含 tool_calls，
     * 但工具还没来得及执行。此时发送给 API 会报 400：
     * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"。
     *
     * <p>合成结果内容模仿 Grok 的措辞，让模型知道需要重试而非报错。
     *
     * @param msgs 消息列表（原地修改）
     * @return 插入的合成 ToolResult 数量
     */
    static int repairDanglingToolCalls(List<ChatMessage> msgs) {
        // 阶段 1：正向扫描，收集需要修复的位置
        java.util.List<java.util.AbstractMap.SimpleEntry<Integer, java.util.List<ChatMessage>>> repairs
                = new java.util.ArrayList<>();
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage msg = msgs.get(i);
            if (!"assistant".equals(msg.getRole())
                    || msg.getTool_calls() == null
                    || msg.getTool_calls().isEmpty()) {
                i++;
                continue;
            }

            // 收集该 assistant 声明的 tool_call_id → name 映射
            java.util.Map<String, String> callNames = new java.util.LinkedHashMap<>();
            for (ChatMessage.ToolCall tc : msg.getTool_calls()) {
                if (tc.getId() != null) {
                    String name = (tc.getFunction() != null && tc.getFunction().getName() != null)
                            ? tc.getFunction().getName() : "unknown";
                    callNames.put(tc.getId(), name);
                }
            }

            // 扫描紧跟在后面的连续 ToolResult，标记已应答的
            int j = i + 1;
            while (j < msgs.size() && "tool".equals(msgs.get(j).getRole())) {
                String tid = msgs.get(j).getTool_call_id();
                if (tid != null) {
                    callNames.remove(tid);
                }
                j++;
            }

            // 未应答的 → 生成合成 ToolResult
            if (!callNames.isEmpty()) {
                java.util.List<ChatMessage> synthetic = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, String> entry : callNames.entrySet()) {
                    String toolName = entry.getValue();
                    synthetic.add(ChatMessage.toolResult(
                            entry.getKey(),
                            toolName,
                            "Tool execution was interrupted (tool `" + toolName
                                    + "` was not executed). Please retry if needed."
                    ));
                }
                repairs.add(new java.util.AbstractMap.SimpleEntry<>(j, synthetic));
            }

            i = j;
        }

        // 阶段 2：反向插入（保持索引稳定）
        int inserted = 0;
        java.util.Collections.reverse(repairs);
        for (java.util.AbstractMap.SimpleEntry<Integer, java.util.List<ChatMessage>> repair : repairs) {
            int insertAt = repair.getKey();
            java.util.List<ChatMessage> synthetic = repair.getValue();
            msgs.addAll(insertAt, synthetic);
            inserted += synthetic.size();
        }
        return inserted;
    }

    // ==================== 内部方法 ====================

    private void insertSystemAutoMessage(String content) {
        ChatMessage msg = new ChatMessage(Constant.ROLE_user, content);
        conversationManager.add(msg);

        String sessionId = chatSessionManager.getCurrentSessionId();
        if (sessionId != null) {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setId(DBChatHistoryRepository.getUUID());
            entity.setSessionId(sessionId);
            entity.setRole(Constant.ROLE_user);
            entity.setQaRound(chatSessionManager.getCurrentQaRound());
            entity.setCreatedAt(System.currentTimeMillis());
            entity.setUpdatedAt(System.currentTimeMillis());
            com.codepal.model.MessagePartEntity textPart = com.codepal.model.MessagePartEntity.text(
                    entity.getId(), sessionId, content, 0);
            chatSessionManager.persistMessageWithParts(entity, java.util.Collections.singletonList(textPart));
        }
    }

    // ==================== Getters ====================

    public int getCurrentRoundToolCallCount() {
        return currentRoundToolCallCount;
    }

    public int getConsecutiveSameToolCount() {
        return consecutiveSameToolCount;
    }

    public boolean isStopMessageInserted() {
        return stopMessageInserted;
    }
}
