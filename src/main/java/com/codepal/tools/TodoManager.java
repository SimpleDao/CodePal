package com.codepal.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.codepal.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo 任务管理器 —— 从 ChatPanel 抽离的 todo 业务逻辑
 *
 * <p>职责：
 * <ul>
 *   <li>维护 currentTodos 状态</li>
 *   <li>解析 todo 工具参数</li>
 *   <li>处理 create/update/list 操作</li>
 *   <li>格式化输出文本</li>
 *   <li>通过 UiCallbacks 回调 UI 层渲染</li>
 * </ul>
 *
 * @author CP Refactoring
 */
public class TodoManager {

    public interface UiCallbacks {
        void renderTodoList(String todosJson);
        void renderTodoListAndReEmit(String todosJson);
        void clearTodoList();
    }

    public static class TodoItem {
        public String id;
        public String content;
        public String status;
        public String priority;

        public TodoItem(String id, String content, String status, String priority) {
            this.id = id;
            this.content = content;
            this.status = status;
            this.priority = priority;
        }
    }

    private final List<TodoItem> currentTodos = new ArrayList<>();
    private final UiCallbacks uiCallbacks;
    private final Gson gson = new Gson();
    /** 当前待办所属会话（持久化键）；null 表示未绑定（不落库） */
    private String sessionId = null;
    /** 重放历史时是否跳过持久化（避免把历史回放写成当前状态干扰） */
    private boolean replaying = false;

    public TodoManager(UiCallbacks uiCallbacks) {
        this.uiCallbacks = uiCallbacks;
    }

    /** 绑定当前会话：切换/新建会话时调用。若该会话已有持久化待办则加载回填。 */
    public void bindSession(String newSessionId) {
        this.sessionId = newSessionId;
        currentTodos.clear();
        if (newSessionId != null) {
            try {
                String json = com.codepal.db.TodoRepository.load(newSessionId);
                if (json != null && !json.isBlank()) {
                    TodoItem[] arr = gson.fromJson(json, TodoItem[].class);
                    if (arr != null) {
                        currentTodos.addAll(java.util.Arrays.asList(arr));
                    }
                }
            } catch (Exception ignored) {}
        }
        String todosJson = gson.toJson(currentTodos);
        uiCallbacks.renderTodoList(todosJson);
    }

    public String handleTodoTool(ChatMessage.ToolCall tc) {
        JsonObject a = parseArgs(tc);
        String action = getArgStr(a, "action", "list");
        List<TodoItem> items = parseTodoItems(a);

        switch (action) {
            case "create":
                currentTodos.clear();
                currentTodos.addAll(items);
                break;
            case "update":
                updateItems(items);
                break;
            case "list":
            default:
                break;
        }

        String todosJson = gson.toJson(currentTodos);
        persist();
        if ("update".equals(action)) {
            uiCallbacks.renderTodoListAndReEmit(todosJson);
        } else {
            uiCallbacks.renderTodoList(todosJson);
        }

        return "当前任务列表：\n" + formatTodoListText();
    }

    public void replayTodoTool(ChatMessage.ToolCall tc) {
        try {
            replaying = true;
            JsonObject a = parseArgs(tc);
            String action = getArgStr(a, "action", "list");
            List<TodoItem> items = parseTodoItems(a);
            switch (action) {
                case "create":
                    currentTodos.clear();
                    currentTodos.addAll(items);
                    break;
                case "update":
                    updateItems(items);
                    break;
                case "list":
                default:
                    break;
            }
            // 重放历史时只调用 renderTodoList，不触发 ReEmit（避免重复追加完成摘要）
            String todosJson = gson.toJson(currentTodos);
            uiCallbacks.renderTodoList(todosJson);
        } catch (Exception ignored) {}
        finally {
            replaying = false;
        }
    }

    /** 持久化当前待办到当前会话（重放期间跳过，避免历史回放覆盖已存状态）。 */
    private void persist() {
        if (replaying) return;
        if (sessionId == null || sessionId.isBlank()) return;
        com.codepal.db.TodoRepository.save(sessionId, gson.toJson(currentTodos));
    }

    public void clear() {
        currentTodos.clear();
        persist();
        uiCallbacks.clearTodoList();
    }

    /** 仅清空 UI 与内存、不落库：用于切换会话等场景（避免把旧会话的待办误删），
     *  新会话数据由 bindSession 负责。 */
    public void clearUiOnly() {
        currentTodos.clear();
        uiCallbacks.clearTodoList();
    }

    public List<TodoItem> getCurrentTodos() {
        return new ArrayList<>(currentTodos);
    }

    public int size() {
        return currentTodos.size();
    }

    public boolean isEmpty() {
        return currentTodos.isEmpty();
    }

    // ── 内部方法 ──

    private void updateItems(List<TodoItem> items) {
        for (TodoItem item : items) {
            boolean found = false;
            for (int i = 0; i < currentTodos.size(); i++) {
                if (currentTodos.get(i).id.equals(item.id)) {
                    TodoItem existing = currentTodos.get(i);
                    if (item.content != null && !item.content.isEmpty()) {
                        existing.content = item.content;
                    }
                    if (item.status != null && !item.status.isEmpty()) {
                        existing.status = item.status;
                    }
                    if (item.priority != null && !item.priority.isEmpty()) {
                        existing.priority = item.priority;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (item.content == null || item.content.isEmpty()) {
                    item.content = "任务 " + item.id;
                }
                currentTodos.add(item);
            }
        }
    }

    private List<TodoItem> parseTodoItems(JsonObject a) {
        List<TodoItem> items = new ArrayList<>();
        if (!a.has("todos") || !a.get("todos").isJsonArray()) return items;
        JsonArray arr = a.getAsJsonArray("todos");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            String id = getArgStr(obj, "id", String.valueOf(i + 1));
            String content = getArgStr(obj, "content", "");
            String status = getArgStr(obj, "status", "pending");
            String priority = getArgStr(obj, "priority", "medium");
            items.add(new TodoItem(id, content, status, priority));
        }
        return items;
    }

    private String formatTodoListText() {
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : currentTodos) {
            String mark = "completed".equals(item.status) ? "[x]"
                    : ("in_progress".equals(item.status) ? "[>]" : "[ ]");
            sb.append(mark).append(" ").append(item.content).append("\n");
        }
        return sb.toString();
    }

    private static JsonObject parseArgs(ChatMessage.ToolCall tc) {
        if (tc == null || tc.getFunction() == null) return new JsonObject();
        String args = tc.getFunction().getArguments();
        if (args == null || args.isBlank()) return new JsonObject();
        try {
            return com.google.gson.JsonParser.parseString(args).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String getArgStr(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key)) return defaultValue;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
