package com.codepal.session;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.codepal.common.Constant;
import com.codepal.db.DBChatHistoryRepository;
import com.codepal.memory.ConversationManager;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatMessageEntity;
import com.codepal.model.ChatSessionEntity;
import com.codepal.model.MessagePartEntity;
import com.codepal.common.StringUtils;
import com.codepal.utils.ThreadHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话管理器
 *
 * <p>职责：
 * <ul>
 *   <li>会话生命周期管理（创建、切换、删除）</li>
 *   <li>会话配置持久化（模型、Agent、模式选择）</li>
 *   <li>历史消息懒加载</li>
 *   <li>消息持久化</li>
 *   <li>QA轮次管理</li>
 * </ul>
 *
 * <p>借鉴 AutoDev 的 ConversationManager 思想，将会话业务逻辑从 UI 层解耦。
 * UI 渲染通过回调接口与本类交互。
 *
 * @author CP Refactor
 */
public class ChatSessionManager {

    private static final String KEY_PREFIX = "codepal.session.";
    private static final Gson GSON = new Gson();

    private final Project project;
    private final ConversationManager conversationManager;

    private String currentSessionId;
    private String currentSessionTitle;
    private int currentQaRound;
    private int historyLoadedCount;
    private int historyTotalCount;
    private boolean isLoadingHistory;
    private boolean loadingSessionConfig;

    public interface UiCallbacks {
        void clearMessages();
        void clearTodoList();
        void clearAllSessionState();
        /** 懒加载：前置插入更早的历史批次（已合并同轮），含消息头 + 所有 parts，由 UI 层复用 replay 链路渲染 */
        void prependHistoryRecords(java.util.Map<ChatMessageEntity, List<MessagePartEntity>> records);
        void setHasMoreHistory(boolean hasMore);
        void removeMessagesByRound(int qaRound);
        void setQaRound(int qaRound);
        /** 新接口：传入消息头 + 所有 parts，由 UI 层自行渲染 */
        void renderMessageWithParts(ChatMessageEntity msg, List<MessagePartEntity> parts);
        void onHistoryLoaded();

        /** 会话标题被自动（或手动）改写后通知 UI 刷新（如历史列表） */
        void onSessionRenamed(String title);

        /** 会话被激活（新建或切换）后通知 UI：用于登记/高亮顶部 Tab 条 */
        void onActivateSession(String sessionId, String title);

        JComboBox<?> getModelCombo();
        JComboBox<String> getAgentCombo();
        JComboBox<String> getModeCombo();

        boolean showConfirmDialog(String message, String title);
    }

    private UiCallbacks uiCallbacks;

    public ChatSessionManager(Project project, ConversationManager conversationManager) {
        this.project = project;
        this.conversationManager = conversationManager;
    }

    public void setUiCallbacks(UiCallbacks callbacks) {
        this.uiCallbacks = callbacks;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentSessionTitle() {
        return currentSessionTitle;
    }

    public int getCurrentQaRound() {
        return currentQaRound;
    }

    public void setCurrentQaRound(int round) {
        this.currentQaRound = round;
    }

    /**
     * 首条用户消息后，用输入内容自动生成会话标题，替换默认的「新会话」。
     * 幂等：仅当当前标题仍是默认值时才改写，已自定义标题不会被覆盖。
     *
     * @param userText 用户本轮输入（纯文本；含图片时由调用方传入可读文本）
     */
    public void autoTitleFromUserMessage(String userText) {
        if (currentSessionId == null) return;
        // 已是自定义标题则不覆盖
        if (currentSessionTitle != null && !"新会话".equals(currentSessionTitle)) return;

        String title = deriveTitle(userText);
        if (title == null) return;

        currentSessionTitle = title;
        final String sid = currentSessionId;
        // 写入提交后再刷新列表（完成回调里触发），避免 listSessions 读到改写前的旧标题
        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.updateSessionTitle(sid, title),
                () -> {
                    if (uiCallbacks != null) uiCallbacks.onSessionRenamed(title);
                });
    }

    /** 从用户输入派生一个简短标题：取首行、折叠空白、超长截断并加省略号 */
    private static String deriveTitle(String text) {
        if (text == null) return null;
        String clean = text.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return null;
        final int max = 24;
        if (clean.length() > max) {
            clean = clean.substring(0, max).strip() + "…";
        }
        return clean;
    }

    /**
     * 手动重命名指定会话（历史面板右键「编辑标题」调用）。
     * 同步更新内存中的当前标题（若该会话即当前会话），并通知 UI 刷新历史列表。
     */
    public void renameSession(String sessionId, String newTitle) {
        if (sessionId == null || newTitle == null) return;
        String title = newTitle.trim();
        if (title.isEmpty()) return;
        // 必须在 DB 写入提交后再刷新列表，否则 listSessions 的读可能先于写提交（竞态）读到旧标题，
        // 表现为"改名后列表不更新，重开面板才刷新"。故把刷新放进写入任务的完成回调。
        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.updateSessionTitle(sessionId, title),
                () -> {
                    if (sessionId.equals(currentSessionId)) {
                        currentSessionTitle = title;
                    }
                    if (uiCallbacks != null) uiCallbacks.onSessionRenamed(title);
                });
    }

    public void incrementQaRound() {
        currentQaRound++;
    }

    public int getHistoryLoadedCount() {
        return historyLoadedCount;
    }

    public int getHistoryTotalCount() {
        return historyTotalCount;
    }

    public boolean isLoadingHistory() {
        return isLoadingHistory;
    }

    public boolean isLoadingSessionConfig() {
        return loadingSessionConfig;
    }

    /** 当前项目根路径（会话归属键），用于项目间会话隔离 */
    private String currentProjectPath() {
        return project != null ? project.getBasePath() : null;
    }

    public void openSession() {
        String projectPath = currentProjectPath();
        ThreadHelper.executeAsync(project,
                () -> {
                    // 只匹配当前项目的最近会话；匹配不到就返回 null（新建空会话）。
                    // 不再回退到全局最近会话：不同 IDEA 项目的 project_path 不同，
                    // 回退会把其它项目的历史会话带进当前项目，造成会话串扰。
                    return DBChatHistoryRepository.getRecentSession(projectPath);
                },
                conversation -> {
                    System.out.println("openSession: recent=" + (conversation != null ? conversation.getId() : "null")
                            + " title=" + (conversation != null ? conversation.getTitle() : "")
                            + " project=" + projectPath);
                    if (null == conversation) {
                        // 用户已在此前发送过消息（ensurePersistedSession 已建真实会话）时不覆盖
                        if (currentSessionId == null) {
                            createNewSession();
                        }
                    } else {
                        switchToSession(conversation.getId());
                    }
                });
    }

    public void createNewSession() {
        // 用户尚未发送消息：仅建立内存中的临时会话，不写库。
        // 直到用户发出首条消息（ensurePersistedSession）才真正创建 DB 记录，
        // 避免「打开插件即产生空会话入库、历史面板出现未发消息的会话」。
        currentSessionId = null;
        currentSessionTitle = "新会话";
        if (uiCallbacks != null) uiCallbacks.onActivateSession(null, currentSessionTitle);
        currentQaRound = 0;
        historyLoadedCount = 0;
        historyTotalCount = 0;
        isLoadingHistory = false;
        loadingSessionConfig = false;

        if (uiCallbacks != null) uiCallbacks.clearAllSessionState();

        ChatMessage systemMsg = conversationManager.getSystemMessage();
        conversationManager.getMessages().clear();
        if (systemMsg != null) conversationManager.getMessages().add(systemMsg);
    }

    /**
     * 惰性建会话：将内存中的临时会话真正写入 DB 并返回真实 sessionId。
     * 用户在发送首条消息前会话不落库；一旦要持久化消息，必须先调用本方法，
     * 保证消息归属到已入库的会话（避免孤儿消息）。
     *
     * @return 真实 sessionId；DB 写入失败时返回 null（保持临时态，下次调用会重试）
     */
    public String ensurePersistedSession() {
        if (currentSessionId != null) return currentSessionId;
        ChatSessionEntity newSession = DBChatHistoryRepository.createSession("新会话", currentProjectPath());
        if (newSession == null) return null;
        currentSessionId = newSession.getId();
        currentSessionTitle = newSession.getTitle();
        if (uiCallbacks != null) uiCallbacks.onActivateSession(currentSessionId, currentSessionTitle);
        // 临时会话期间用户选定的模型/Agent/模式配置在此补存
        saveSessionConfig();
        return currentSessionId;
    }

    public void switchToSession(String sessionId) {
        if (sessionId == null || sessionId.equals(currentSessionId)) return;

        saveSessionConfig();

        currentSessionId = sessionId;
        currentSessionTitle = DBChatHistoryRepository.getSessionTitle(sessionId);
        if (uiCallbacks != null) uiCallbacks.onActivateSession(currentSessionId, currentSessionTitle);
        currentQaRound = 0;
        historyLoadedCount = 0;
        historyTotalCount = 0;
        isLoadingHistory = true;  // 历史加载期间阻止发送（避免上下文为空）
        loadingSessionConfig = false;

        if (uiCallbacks != null) uiCallbacks.clearAllSessionState();

        loadSessionConfig(sessionId);

        ChatMessage systemMsg = conversationManager.getSystemMessage();
        conversationManager.getMessages().clear();
        if (systemMsg != null) conversationManager.getMessages().add(systemMsg);

        // 首次加载尽量多（最近 500 条），超长历史再靠 loadMoreHistory 补全
        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.getSessionMessagesWithParts(sessionId, 500, 0),
                result -> {
                    if (result == null || result.isEmpty()) {
                        isLoadingHistory = false;
                        if (uiCallbacks != null) uiCallbacks.onHistoryLoaded();
                        return;
                    }
                    System.out.println("uiMsgs = " + result.keySet());
                    historyLoadedCount = result.size();

                    if (uiCallbacks != null) uiCallbacks.clearMessages();

                    for (Map.Entry<ChatMessageEntity, List<MessagePartEntity>> entry : result.entrySet()) {
                        ChatMessageEntity rec = entry.getKey();
                        List<MessagePartEntity> parts = entry.getValue();

                        List<ChatMessage> builtMsgs = buildChatMessagesFromParts(rec, parts);
                        conversationManager.getMessages().addAll(builtMsgs);

                        // 渲染 UI
                        if (uiCallbacks != null) {
                            uiCallbacks.renderMessageWithParts(rec, parts);
                        }
                    }

                    if (!result.isEmpty()) {
                        int maxRound = result.keySet().stream()
                                .mapToInt(ChatMessageEntity::getQaRound).max().orElse(0);
                        currentQaRound = maxRound;
                        if (uiCallbacks != null) uiCallbacks.setQaRound(maxRound);
                    }
                    if (uiCallbacks != null) uiCallbacks.onHistoryLoaded();
                    isLoadingHistory = false;

                    ThreadHelper.executeAsync(project,
                            () -> DBChatHistoryRepository.getMessagesCount(sessionId),
                            total -> {
                                historyTotalCount = total;
                                if (uiCallbacks != null) {
                                    uiCallbacks.setHasMoreHistory(historyLoadedCount < total);
                                }
                            }
                    );
                }
        );
    }

    public void saveSessionConfig() {
        if (currentSessionId == null || uiCallbacks == null) return;

        String model = null;
        Object modelSel = uiCallbacks.getModelCombo().getSelectedItem();
        if (modelSel instanceof com.codepal.toolwindow.ChatPanel.ModelComboItem) {
            model = ((com.codepal.toolwindow.ChatPanel.ModelComboItem) modelSel).name;
        }
        String agent = (String) uiCallbacks.getAgentCombo().getSelectedItem();
        String mode = (String) uiCallbacks.getModeCombo().getSelectedItem();

        PropertiesComponent pc = PropertiesComponent.getInstance();
        pc.setValue(KEY_PREFIX + currentSessionId + ".model", model != null ? model : "");
        pc.setValue(KEY_PREFIX + currentSessionId + ".agent", agent != null ? agent : "");
        pc.setValue(KEY_PREFIX + currentSessionId + ".mode", mode != null ? mode : "");
    }

    public void clearSessionConfig(String sessionId) {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        pc.setValue(KEY_PREFIX + sessionId + ".model", null);
        pc.setValue(KEY_PREFIX + sessionId + ".agent", null);
        pc.setValue(KEY_PREFIX + sessionId + ".mode", null);
    }

    private void loadSessionConfig(String sessionId) {
        if (uiCallbacks == null) return;

        PropertiesComponent pc = PropertiesComponent.getInstance();
        String model = pc.getValue(KEY_PREFIX + sessionId + ".model");
        String agent = pc.getValue(KEY_PREFIX + sessionId + ".agent");
        String mode = pc.getValue(KEY_PREFIX + sessionId + ".mode");

        loadingSessionConfig = true;
        try {
            JComboBox<String> agentCombo = uiCallbacks.getAgentCombo();
            if (agent != null && !agent.isEmpty()) {
                for (int i = 0; i < agentCombo.getItemCount(); i++) {
                    if (agent.equals(agentCombo.getItemAt(i))) {
                        agentCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }

            JComboBox<?> modelCombo = uiCallbacks.getModelCombo();
            if (model != null && !model.isEmpty()) {
                for (int i = 0; i < modelCombo.getItemCount(); i++) {
                    Object item = modelCombo.getItemAt(i);
                    String itemName = null;
                    if (item instanceof com.codepal.toolwindow.ChatPanel.ModelComboItem) {
                        itemName = ((com.codepal.toolwindow.ChatPanel.ModelComboItem) item).name;
                    } else if (item instanceof String) {
                        itemName = (String) item;
                    }
                    if (model.equals(itemName)) {
                        modelCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }

            JComboBox<String> modeCombo = uiCallbacks.getModeCombo();
            // Plan 选项已移除，始终强制为 Craft 模式
            modeCombo.setSelectedItem("Craft");
        } finally {
            loadingSessionConfig = false;
        }
    }

    public boolean canLoadMoreHistory() {
        return !isLoadingHistory && historyLoadedCount < historyTotalCount && currentSessionId != null;
    }

    public void loadMoreHistory() {
        if (!canLoadMoreHistory()) {
            if (uiCallbacks != null && historyLoadedCount >= historyTotalCount) {
                uiCallbacks.setHasMoreHistory(false);
            }
            return;
        }

        isLoadingHistory = true;
        final int offset = historyLoadedCount;

        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.getSessionMessagesWithParts(currentSessionId, 80, offset),
                result -> {
                    isLoadingHistory = false;
                    if (result == null || result.isEmpty()) {
                        if (uiCallbacks != null) uiCallbacks.setHasMoreHistory(false);
                        return;
                    }

                    historyLoadedCount += result.size();

                    // ★ 关键修复：加载的历史必须真正加入 conversationManager，
                    // 否则 UI 上能看到、模型上下文里却没有 → 模型失忆。
                    // result 是更早批次（offset 递增），插入到 system 之后、现有消息之前。
                    insertHistoryBeforeExisting(result);

                    // 直接把合并后的 records 交给 UI 层，由 UI 复用 replay 实时渲染链路前置插入
                    if (uiCallbacks != null) {
                        uiCallbacks.prependHistoryRecords(result);
                    }

                    boolean hasMore = historyLoadedCount < historyTotalCount;
                    if (uiCallbacks != null) uiCallbacks.setHasMoreHistory(hasMore);
                }
        );
    }

    public void deleteQaMessages(int qaRound) {
        if (currentSessionId == null || uiCallbacks == null) return;

        System.out.println("[Delete] 收到删除请求 qaRound=" + qaRound + ", currentSessionId=" + currentSessionId);

        boolean confirmed = uiCallbacks.showConfirmDialog(
                "确定要删除这轮对话吗？此操作不可恢复。",
                "删除对话"
        );
        if (!confirmed) {
            System.out.println("[Delete] 用户取消删除");
            return;
        }

        uiCallbacks.removeMessagesByRound(qaRound);

        System.out.println("[Delete] 删除前消息列表 (qaRound):");
        synchronized (conversationManager.getMessages()) {
            for (ChatMessage m : conversationManager.getMessages()) {
                System.out.println("  " + m.getRole() + " qaRound=" + m.getQaRound()
                        + " content=" + (m.getContent() != null ? m.getContent().substring(0, Math.min(50, m.getContent().length())) : "null"));
            }
            boolean removed = conversationManager.getMessages().removeIf(msg -> msg.getQaRound() == qaRound);
            System.out.println("[Delete] removeIf 结果: removed=" + removed + ", 当前列表:");
            for (ChatMessage m : conversationManager.getMessages()) {
                System.out.println("  " + m.getRole() + " qaRound=" + m.getQaRound()
                        + " content=" + (m.getContent() != null ? m.getContent().substring(0, Math.min(50, m.getContent().length())) : "null"));
            }
        }

        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.deleteMessagesByRound(currentSessionId, qaRound),
                null
        );
    }

    public void persistMessage(ChatMessageEntity message) {
        ThreadHelper.executeAsync(project, () -> DBChatHistoryRepository.saveMessage(message), null);
    }

    /** 持久化消息头 + 所有 parts（事务） */
    public void persistMessageWithParts(ChatMessageEntity message, List<MessagePartEntity> parts) {
        ThreadHelper.executeAsync(project, () -> DBChatHistoryRepository.saveMessageWithParts(message, parts), null);
    }

    /** 单独持久化一个 part */
    public void persistPart(MessagePartEntity part) {
        ThreadHelper.executeAsync(project, () -> DBChatHistoryRepository.savePart(part), null);
    }

    /** 更新 tool part 的 meta 和 status（pending → done，填入 toolOutput） */
    public void updatePartMetaAndStatus(String partId, String meta, String status) {
        ThreadHelper.executeAsync(project, () -> DBChatHistoryRepository.updatePartMetaAndStatus(partId, meta, status), null);
    }

    // ── Parts → ChatMessage 转换（用于 conversationManager 内存恢复） ──

    /**
     * 将更早批次的历史记录构建成 ChatMessage，插入到 conversationManager 中
     * system 之后、现有消息之前（保持时间正序）。
     */
    private void insertHistoryBeforeExisting(
            Map<ChatMessageEntity, List<MessagePartEntity>> earlierRecords) {
        if (earlierRecords == null || earlierRecords.isEmpty()) return;

        List<ChatMessage> newMsgs = new ArrayList<>();
        for (Map.Entry<ChatMessageEntity, List<MessagePartEntity>> entry : earlierRecords.entrySet()) {
            List<ChatMessage> built = buildChatMessagesFromParts(entry.getKey(), entry.getValue());
            if (built != null && !built.isEmpty()) {
                newMsgs.addAll(built);
            }
        }
        if (newMsgs.isEmpty()) return;

        synchronized (conversationManager.getMessages()) {
            List<ChatMessage> all = conversationManager.getMessages();
            // 找到 system 之后（第一条非 system）的插入位置
            int insertIdx = 0;
            while (insertIdx < all.size() && "system".equals(all.get(insertIdx).getRole())) {
                insertIdx++;
            }
            all.addAll(insertIdx, newMsgs);
        }
    }

    public static List<ChatMessage> buildChatMessagesFromParts(ChatMessageEntity rec, List<MessagePartEntity> parts) {
        List<ChatMessage> result = new ArrayList<>();

        // 检查 meta：压缩消息不进 conversationManager（但 summary 消息要进）
        boolean isCompressed = false;
        boolean isSummary = false;
        if (rec.getMeta() != null && !rec.getMeta().isBlank()) {
            try {
                com.google.gson.JsonObject metaObj = com.google.gson.JsonParser.parseString(rec.getMeta()).getAsJsonObject();
                isCompressed = metaObj.has("compressed") && metaObj.get("compressed").getAsBoolean();
                isSummary = metaObj.has("compressed_summary") && metaObj.get("compressed_summary").getAsBoolean();
            } catch (Exception ignored) {}
        }
        if (isCompressed && !isSummary) {
            return result; // 空列表，不加入 conversationManager
        }

        ChatMessage msg = new ChatMessage();
        msg.setRole(rec.getRole());
        msg.setQaRound(rec.getQaRound());
        result.add(msg);

        if (parts == null || parts.isEmpty()) return result;

        if (Constant.ROLE_user.equals(rec.getRole())) {
            for (MessagePartEntity p : parts) {
                if ("text".equals(p.getKind()) && p.getContent() != null) {
                    msg.setContent(p.getContent());
                    break;
                }
            }
            // 兜底：若没有任何 text part（持久化异常等），content 保持 null 会让 API 400，
            // 这里设为空串，至少保证消息结构合法（内容缺失由持久化层负责）。
            if (msg.getContent() == null) msg.setContent("");
        } else if (Constant.ROLE_assistant.equals(rec.getRole())) {
            List<ChatMessage.ToolCall> toolCalls = new ArrayList<>();
            List<ChatMessage> toolResultMessages = new ArrayList<>();
            StringBuilder reasoningBuf = new StringBuilder();
            StringBuilder textBuf = new StringBuilder();

            for (MessagePartEntity p : parts) {
                switch (p.getKind()) {
                    case "thinking":
                        if (p.getContent() != null) reasoningBuf.append(p.getContent());
                        break;
                    case "text":
                        if (p.getContent() != null) textBuf.append(p.getContent());
                        break;
                    case "tool":
                        if (p.getMeta() != null) {
                            try {
                                JsonObject meta = JsonParser.parseString(p.getMeta()).getAsJsonObject();
                                ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                                tc.setId(p.getId());
                                tc.setType("function");
                                ChatMessage.ToolCall.Function fn = new ChatMessage.ToolCall.Function();
                                fn.setName(meta.has("toolName") ? meta.get("toolName").getAsString() : "unknown");
                                // ★ toolInput 可能是内嵌 JSON 对象（旧数据/手工写入），用兼容提取而非 getAsString()
                                fn.setArguments(com.codepal.model.MessagePartEntity.extractToolInput(meta));
                                tc.setFunction(fn);
                                toolCalls.add(tc);

                                String toolOutput = meta.has("toolOutput") && !meta.get("toolOutput").isJsonNull()
                                        ? meta.get("toolOutput").getAsString() : "";
                                String toolName = meta.has("toolName") ? meta.get("toolName").getAsString() : "unknown";
                                ChatMessage toolResult = ChatMessage.toolResult(p.getId(), toolName, toolOutput);
                                toolResult.setQaRound(rec.getQaRound());
                                toolResultMessages.add(toolResult);
                            } catch (Exception ignored) {}
                        }
                        break;
                }
            }

            if (textBuf.length() > 0) msg.setContent(textBuf.toString());
            if (reasoningBuf.length() > 0) msg.setReasoning_content(reasoningBuf.toString());
            if (!toolCalls.isEmpty()) msg.setTool_calls(toolCalls);

            // 如果 assistant 消息既没有 content 也没有 tool_calls，跳过（API 不允许空 assistant 消息）
            if (msg.getContent() == null && (msg.getTool_calls() == null || msg.getTool_calls().isEmpty())) {
                result.clear();
                return result;
            }

            if (!toolResultMessages.isEmpty()) {
                result.addAll(toolResultMessages);
            }
        }
        return result;
    }

    public boolean deleteSession(ChatSessionEntity session) {
        if (session == null || uiCallbacks == null) return false;

        boolean confirmed = uiCallbacks.showConfirmDialog(
                "确定要删除会话「" + session.getName() + "」吗？",
                "删除会话"
        );
        if (!confirmed) return false;

        DBChatHistoryRepository.deleteSession(session.getId());
        clearSessionConfig(session.getId());
        com.codepal.db.TodoRepository.delete(session.getId());

        if (session.getId().equals(currentSessionId)) {
            createNewSession();
        }

        return true;
    }

    public boolean deleteAllSessions() {
        if (uiCallbacks == null) return false;

        boolean confirmed = uiCallbacks.showConfirmDialog(
                "确定要删除所有历史会话吗？此操作不可恢复。",
                "删除所有会话"
        );
        if (!confirmed) return false;

        List<ChatSessionEntity> sessions = DBChatHistoryRepository.listSessions(currentProjectPath());
        for (ChatSessionEntity s : sessions) {
            DBChatHistoryRepository.deleteSession(s.getId());
            clearSessionConfig(s.getId());
            com.codepal.db.TodoRepository.delete(s.getId());
        }
        createNewSession();

        return true;
    }

    public void addMsgWithRound(ChatMessage msg) {
        msg.setQaRound(currentQaRound);
        conversationManager.add(msg);
    }

    public void resetConversation(String systemPrompt) {
        conversationManager.reset(systemPrompt);
        currentQaRound = 0;
        historyLoadedCount = 0;
        historyTotalCount = 0;
    }
}
