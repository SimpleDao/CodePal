package com.codepal.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.codepal.model.ChatMessage;
import com.codepal.model.UserAnswer;
import com.codepal.model.UserQuestion;
import com.intellij.openapi.project.Project;
import com.codepal.settings.CPSettings;
import com.codepal.utils.FileReaderUtil;
import com.codepal.db.DataSourceDao;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具执行器 —— "四大金刚" 工具执行
 * 接收 LM 模型返回的工具调用，在本地执行相应操作并返回结果
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ToolExecutor {

    private static final Gson GSON = new Gson();

    /**
     * 当前选中的默认数据源名（ChatPanel 选中某条数据源后通过 setDefaultDataSource 写入）；
     * execQueryDatabase 在模型没传 db_name 时回退到它。
     * 通过静态字段暴露，因为 ToolExecutor 是 static 的，无法直接拿到 ChatPanel 实例。
     */
    private static volatile String defaultDataSourceName = null;

    /** 写入/读取当前选中的默认数据源名（ChatPanel 选中某条数据源后调用） */
    public static void setDefaultDataSource(String name) {
        defaultDataSourceName = (name == null || name.isBlank()) ? null : name;
    }

    /** 给系统提示注入用——返回当前默认名（可能为 null） */
    public static String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    /** 工具确认接口：由 UI 层实现，用于显示确认卡片并等待用户响应 */
    public interface ToolConfirmProvider {
        /**
         * 请求用户确认
         * @param toolCallId 工具调用ID
         * @param command 命令/操作内容描述
         * @param level 危险等级：warning / danger / info
         * @param canTrust 是否允许"本次会话不再询问"
         * @param kind 确认类型：command=终端命令，delete=删除文件操作（用于决定卡片标题）
         * @return CompletableFuture<Boolean> true=确认执行，false=拒绝
         */
        java.util.concurrent.CompletableFuture<Boolean> requestConfirm(
                String toolCallId, String command, String level, boolean canTrust, String kind);

        /** 检查命令是否在信任名单中（会话内不再询问） */
        boolean isTrusted(String command);

        /** 添加命令到信任名单 */
        void addTrusted(String command);
    }

    /** 模型主动向用户提问的回调接口（ask_user_question 工具用） */
    public interface AskUserQuestionProvider {
        /**
         * 向用户提问并阻塞等待回答
         * @param questions 问题列表（单选/多选/自由输入）
         * @return 每题的回答结果
         */
        CompletableFuture<List<UserAnswer>> requestAskUserQuestion(List<UserQuestion> questions);
    }

    /** 工具执行上下文：封装确认提供者、子智能体管理器等依赖，替代静态字段 */
    public static class ToolExecutionContext {
        public ToolConfirmProvider confirmProvider;
        public AskUserQuestionProvider askQuestionProvider;
        public com.codepal.agent.subagent.SubAgentManager subAgentManager;
    }

    // TMP-MARKER-CONFIRM-PROVIDER
    private static ToolConfirmProvider confirmProvider;
    private static AskUserQuestionProvider askQuestionProvider;
    /** SubAgentManager: dispatches search_agent / view_image etc. (injected by UI layer) */
    // TMP-MARKER-A
    private static com.codepal.agent.subagent.SubAgentManager subAgentManager;

    /** register confirm provider (UI layer) */
    public static void setConfirmProvider(ToolConfirmProvider provider) {
        confirmProvider = provider;
    }

    /** register ask-user-question provider (UI layer) */
    public static void setAskQuestionProvider(AskUserQuestionProvider provider) {
        askQuestionProvider = provider;
    }

    /** register sub-agent manager (UI layer) */
    public static void setSubAgentManager(com.codepal.agent.subagent.SubAgentManager manager) {
        subAgentManager = manager;
    }

    /** whether the currently running command was cancelled by the user */
    private static final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** ProcessHandler of the console command currently running (for external cancel) */
    private static volatile com.intellij.execution.process.ProcessHandler currentConsoleHandler;

    /**
     * cancel all tools currently executing (mainly for terminating run_command)
     */
    public static void cancelCurrent() {
        cancelled.set(true);
        com.intellij.execution.process.ProcessHandler h = currentConsoleHandler;
        if (h != null && !h.isProcessTerminated()) {
            h.destroyProcess();
        }
    }

    /**
     * reset cancel state (called before each new tool call)
     */
    private static void resetCancelled() {
        cancelled.set(false);
        currentConsoleHandler = null;
    }

    /**
     * check whether the execution has been cancelled
     */
    public static boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 编辑应用结果
     */
    public static class EditResult {
        public final boolean success;
        public final String content;
        public final String error;

        private EditResult(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        public static EditResult ok(String newContent) {
            return new EditResult(true, newContent, null);
        }

        public static EditResult error(String msg) {
            return new EditResult(false, null, msg);
        }
    }

    /**
     * 对给定文本批量应用 search/replace 编辑（顺序应用，唯一匹配校验）
     * 不读写文件，纯内存操作。
     *
     * 匹配策略（移植自 auto-dev-master 的 SmartEditTool，三级回退）：
     *   1. 精确匹配：search 与内容逐字符相等（容错 CRLF/LF 差异）；
     *   2. 柔性匹配：逐行 trim 后滑动窗口比较，容忍缩进/空格差异；
     *   3. 正则匹配：将 search 分词后用 \s* 连接，容忍任意空白。
     *
     * 行尾处理：全程在 LF 域匹配，结束后若原文为 CRLF 则统一还原，保证行尾稳定。
     *
     * @param baseContent 原始文本
     * @param editPairs   编辑对列表，每个元素为 {search, replace}
     * @return EditResult
     */
    public static EditResult applyEdits(String baseContent, List<String[]> editPairs) {
        if (baseContent == null) baseContent = "";
        boolean originalCrlf = baseContent.contains("\r\n");
        String work = baseContent.replace("\r\n", "\n");
        for (int i = 0; i < editPairs.size(); i++) {
            String[] pair = editPairs.get(i);
            String search = pair[0];
            String replace = pair[1];
            EditResult r = applySingleEdit(work, search, replace, i + 1);
            if (!r.success) return r;
            work = r.content;
        }
        if (originalCrlf) work = work.replace("\n", "\r\n");
        return EditResult.ok(work);
    }

    /** 对单处编辑依次尝试 精确 → 柔性 → 正则，返回新内容或错误 */
    private static EditResult applySingleEdit(String work, String search, String replace, int idx) {
        search = search.replace("\r\n", "\n");
        replace = replace.replace("\r\n", "\n");
        if (search.isEmpty()) {
            return EditResult.error("错误（第 " + idx + " 处编辑）：search 为空，无法定位替换位置。");
        }

        // 1. 精确匹配
        int exact = countOccurrences(work, search);
        if (exact == 1) {
            return EditResult.ok(work.replace(search, replace));
        }
        if (exact > 1) {
            return EditResult.error("匹配到 " + exact + " 处相同文本（第 " + idx + " 处编辑），"
                    + "请提供更多上下文使 search 唯一。");
        }

        // 2. 柔性匹配（逐行 trim，容忍缩进/空格差异）
        EditResult flexible = applyFlexible(work, search, replace, idx);
        if (flexible != null) return flexible;

        // 3. 正则匹配（分词 + \s* 连接，容忍任意空白）
        EditResult regex = applyRegex(work, search, replace, idx);
        if (regex != null) return regex;

        // 4. 标准化空格匹配（去除所有空白字符后比较，适用于单行内容如 CSS 值、函数调用等）
        EditResult normalized = applyNormalizedMatch(work, search, replace, idx);
        if (normalized != null) return normalized;

        return EditResult.error("错误（第 " + idx + " 处编辑）：在文件中未找到指定的文本（已尝试精确/柔性/正则/标准化空格四种匹配）。"
                + "请确认 search 与文件实际内容一致，或用 read_file_range 核对真实缩进与空格。");
    }

    /** 柔性匹配：逐行 trim 后滑动窗口比对；命中后保留首行缩进回填 replace */
    private static EditResult applyFlexible(String work, String search, String replace, int idx) {
        List<String> sourceLines = toLines(work);
        List<String> searchLines = new ArrayList<>();
        for (String l : toLines(search)) searchLines.add(l.trim());
        List<String> replaceLines = toLines(replace);
        if (searchLines.isEmpty() || sourceLines.size() < searchLines.size()) return null;

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + searchLines.size() <= sourceLines.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < searchLines.size(); k++) {
                if (!sourceLines.get(i + k).trim().equals(searchLines.get(k))) {
                    ok = false;
                    break;
                }
            }
            if (ok) starts.add(i);
        }
        if (starts.isEmpty()) return null;
        if (starts.size() > 1) {
            return EditResult.error("柔性匹配到 " + starts.size() + " 处相同文本（第 " + idx + " 处编辑），"
                    + "请提供更多上下文使 search 唯一。");
        }

        int start = starts.get(0);
        String indent = leadingWhitespace(sourceLines.get(start));
        List<String> newBlock = new ArrayList<>();
        for (String rl : replaceLines) newBlock.add(indent + rl);
        List<String> resultLines = new ArrayList<>(sourceLines);
        resultLines.subList(start, start + searchLines.size()).clear();
        resultLines.addAll(start, newBlock);
        String modified = restoreTrailing(String.join("\n", resultLines), work);
        return EditResult.ok(modified);
    }

    /** 正则匹配：将 search 分词（保留分隔符），词间用 \s* 连接，按行首锚定 */
    private static EditResult applyRegex(String work, String search, String replace, int idx) {
        String[] delimiters = {"(", ")", ":", "[", "]", "{", "}", ">", "<", "="};
        String processed = search;
        for (String d : delimiters) processed = processed.replace(d, " " + d + " ");
        List<String> tokens = new ArrayList<>();
        for (String t : processed.split("\\s+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
        if (tokens.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("^(\\s*)");
        for (int t = 0; t < tokens.size(); t++) {
            if (t > 0) sb.append("\\s*");
            sb.append(Pattern.quote(tokens.get(t)));
        }
        Pattern regex;
        try {
            regex = Pattern.compile(sb.toString(), Pattern.MULTILINE);
        } catch (Exception e) {
            return null;
        }

        Matcher m = regex.matcher(work);
        int count = 0;
        while (m.find()) count++;
        if (count == 0) return null;
        if (count > 1) {
            return EditResult.error("正则匹配到 " + count + " 处相同文本（第 " + idx + " 处编辑），"
                    + "请提供更多上下文使 search 唯一。");
        }

        m.reset();
        if (!m.find()) return null;
        String indent = m.group(1) != null ? m.group(1) : "";
        List<String> replaceLines = toLines(replace);
        StringBuilder block = new StringBuilder();
        for (int r = 0; r < replaceLines.size(); r++) {
            if (r > 0) block.append("\n");
            block.append(indent).append(replaceLines.get(r));
        }
        String modified = restoreTrailing(
                m.replaceFirst(Matcher.quoteReplacement(block.toString())), work);
        return EditResult.ok(modified);
    }

    /**
     * 标准化空格匹配：去除所有空白字符后逐行比较，适用于单行内容（CSS 值、函数调用等）
     *
     * <p>解决问题：模型传入的 search 可能和文件中的实际内容在空格/逗号后空格/换行等
     * 方面有细微差异，尤其是 rgba(0.93, 0.93, 0.93) 这类格式。
     */
    private static EditResult applyNormalizedMatch(String work, String search, String replace, int idx) {
        List<String> sourceLines = toLines(work);
        List<String> searchLines = toLines(search);
        List<String> replaceLines = toLines(replace);
        if (searchLines.isEmpty() || sourceLines.size() < searchLines.size()) return null;

        List<String> normSearch = new ArrayList<>();
        for (String l : searchLines) normSearch.add(normalizeWhitespace(l));

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + searchLines.size() <= sourceLines.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < searchLines.size(); k++) {
                if (!normalizeWhitespace(sourceLines.get(i + k)).equals(normSearch.get(k))) {
                    ok = false;
                    break;
                }
            }
            if (ok) starts.add(i);
        }
        if (starts.isEmpty()) return null;
        if (starts.size() > 1) {
            return EditResult.error("标准化空格匹配到 " + starts.size() + " 处相同文本（第 " + idx + " 处编辑），"
                    + "请提供更多上下文使 search 唯一。");
        }

        int start = starts.get(0);
        String indent = leadingWhitespace(sourceLines.get(start));
        List<String> newBlock = new ArrayList<>();
        for (String rl : replaceLines) newBlock.add(indent + rl.trim());
        List<String> resultLines = new ArrayList<>(sourceLines);
        resultLines.subList(start, start + searchLines.size()).clear();
        resultLines.addAll(start, newBlock);
        String modified = restoreTrailing(String.join("\n", resultLines), work);
        return EditResult.ok(modified);
    }

    /** 去除所有空白字符（空格、制表符、换行等），用于标准化比较 */
    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** 将文本切分为行（与 Kotlin lines() 一致：按 \n 切分并丢弃末尾空行） */
    private static List<String> toLines(String s) {
        List<String> lines = new ArrayList<>(Arrays.asList(s.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && s.endsWith("\n")) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** 取行首空白（缩进） */
    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return line.substring(0, i);
    }

    /** 还原尾随换行：保持与原文本行尾一致（auto-dev-master restoreTrailingNewline） */
    private static String restoreTrailing(String modified, String original) {
        boolean origEnds = original.endsWith("\n");
        boolean modEnds = modified.endsWith("\n");
        if (origEnds && !modEnds) return modified + "\n";
        if (!origEnds && modEnds) return modified.substring(0, modified.length() - 1);
        return modified;
    }

    /**
     * 执行单个工具调用，返回工具执行结果字符串
     */
    public static String execute(ChatMessage.ToolCall toolCall, Project project) {
        return execute(toolCall, project, false);
    }

    /**
     * 执行单个工具调用（可跳过写操作的 Diff 确认），使用静态注入的 provider
     */
    public static String execute(ChatMessage.ToolCall toolCall, Project project, boolean skipConfirmation) {
        ToolExecutionContext ctx = new ToolExecutionContext();
        ctx.confirmProvider = confirmProvider;
        ctx.askQuestionProvider = askQuestionProvider;
        ctx.subAgentManager = subAgentManager;
        return execute(toolCall, project, skipConfirmation, ctx);
    }

    /**
     * 窗口级执行入口：携带发起窗口的 ToolConfirmManager，
     * 确保确认弹窗 / ask_user_question 提问 UI 渲染到正确的 ChatWebView，
     * 而不是被全局静态 provider 错误路由到其它窗口。
     */
    public static String execute(ChatMessage.ToolCall toolCall, Project project,
                                 boolean skipConfirmation, ToolConfirmManager manager) {
        ToolExecutionContext ctx = new ToolExecutionContext();
        ctx.confirmProvider = manager != null ? manager : confirmProvider;
        ctx.askQuestionProvider = manager != null ? manager : askQuestionProvider;
        ctx.subAgentManager = subAgentManager;
        return execute(toolCall, project, skipConfirmation, ctx);
    }

    /**
     * 执行单个工具调用（可跳过写操作的 Diff 确认）
     *
     * @param skipConfirmation true=Craft 模式，edit_file 直接写盘不弹窗
     */
    public static String execute(ChatMessage.ToolCall toolCall, Project project, boolean skipConfirmation, ToolExecutionContext ctx) {
        if (toolCall == null || toolCall.getFunction() == null) return "错误：无效的工具调用";
        String name = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();
        // 工具调用 ID：用于确认卡 key；缺失时退回生成（SearchAgent/Registry 构造的精简 ToolCall 可能无 id）
        String toolCallId = (toolCall.getId() != null && !toolCall.getId().isBlank())
                ? toolCall.getId()
                : ("tool-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000));

        resetCancelled();

        try {
            JsonObject params;
            if (args != null && !args.isBlank()) {
                try {
                    params = JsonParser.parseString(args).getAsJsonObject();
                } catch (Exception parseEx) {
                    // 严格解析失败时，尝试修复JSON中未转义的控制字符后重试
                    System.err.println("[ToolExecutor] 严格解析失败 tool=" + name
                            + " error=" + parseEx.getMessage() + "，尝试sanitize后重试");
                    String sanitized = sanitizeJsonControlChars(args);
                    params = JsonParser.parseString(sanitized).getAsJsonObject();
                }
            } else {
                params = new JsonObject();
            }

            switch (name) {
                case "search_agent":
                    return execSearchProject(params, project, ctx);
                case "locate_code_by_symbol":
                    return execLocateCodeBySymbol(params, project);
                case "view_file_outline":
                    return execViewFileOutline(params, project);
                case "read_file_range":
                    return execReadFileRange(params, project);
                case "list_files":
                    return execListFiles(params, project);
                case "search_tool":
                    return execSearchGrep(params, project);
                case "code_review":
                    return execCodeReview(params, project);
                case "run_command":
                    return execRunCommand(params, project, ctx);
                case "validate_code":
                    return execValidateCode(params, project);
                case "compile_files":
                    return execCompileFiles(params, project);
                case "edit_file":
                    return execEditFile(params, project, skipConfirmation);
                case "write_file":
                    return execWriteFile(params, project, skipConfirmation);
                case "create_new_file":
                    return execCreateNewFile(params, project, skipConfirmation);
                case "create_directory":
                    return execCreateDirectory(params, project, skipConfirmation);
                case "delete_file":
                    return execDeleteFile(params, project, skipConfirmation, toolCallId, ctx);
                case "view_class_source":
                    return execViewClassSource(params, project, toolCallId, ctx);
                case "load_skill":
                    return execLoadSkill(params, project);
                case "ask_user_question":
                    return execAskUserQuestion(params, ctx);
                case "view_image":
                    return execViewImage(params, ctx);
                case "query_database":
                    return execQueryDatabase(params);
                default:
                    return "错误：未知的工具 \"" + name + "\"";
            }
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 四大金刚工具具体实现
    // ─────────────────────────────────────────────────────────────────────────

    private static String execCodeReview(JsonObject params, Project project) {
        String filePath = getString(params, "file_path", "");
        String mode = getString(params, "mode", "auto");
        String focus = getString(params, "focus", "all");
        int startLine = getInt(params, "start_line", 0);
        int endLine = getInt(params, "end_line", 0);

        if (filePath.isBlank()) return "错误：请提供文件路径";
        if (project == null) return "错误：未打开项目";

        try {
            if (startLine > 0 || endLine > 0) {
                return CodeReviewer.reviewRange(filePath, startLine, endLine, mode, focus, project);
            } else {
                return CodeReviewer.reviewFile(filePath, mode, focus, project);
            }
        } catch (Exception e) {
            return "代码审查失败：" + e.getMessage();
        }
    }

    private static String execSearchProject(JsonObject params, Project project, ToolExecutionContext ctx) {
        String query = getString(params, "query", "");
        if (query.isBlank()) return "错误：请提供搜索任务描述";
        if (project == null) return "错误：未打开项目";

        System.out.println("[SearchAgent] 开始搜索任务: " + query);
        // 统一走 SubAgentManager 的搜索子智能体；未接线时回退到直接调用 SearchAgent
        if (ctx.subAgentManager == null) {
            SearchAgent.SearchResult direct = SearchAgent.search(query, project);
            System.out.println("[SearchAgent] 搜索完成, success=" + direct.success);
            return direct.toString();
        }
        com.codepal.agent.subagent.AgentResult result = ctx.subAgentManager.search(query, null);
        System.out.println("[SearchAgent] 搜索完成, success=" + result.isSuccess());
        return result.isSuccess() ? result.getContent() : "搜索失败：" + result.getErrorMessage();
    }

    private static String execLocateCodeBySymbol(JsonObject params, Project project) {
        String symbol = getString(params, "symbol", "");
        if (symbol.isBlank()) return "错误：请提供类名或方法名";
        if (project == null) return "错误：未打开项目";

        List<String> paths = FileReaderUtil.locateSymbol(symbol, project);
        if (paths.isEmpty()) {
            return "未找到符号 \"" + symbol + "\" 的定义位置";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("找到符号 \"").append(symbol).append("\" 的定义：\n\n");
        for (int i = 0; i < paths.size(); i++) {
            sb.append(i + 1).append(". ").append(paths.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String execViewFileOutline(JsonObject params, Project project) {
        String filePath = getString(params, "file_path", "");
        if (filePath.isBlank()) return "错误：请提供文件路径";
        if (project == null) return "错误：未打开项目";
        return FileReaderUtil.viewFileOutline(filePath, project);
    }

    /**
     * 查看类源码工具（只读）—— 自动定位依赖类并反编译，源码落盘 temp，返回路径。
     *
     * <p>反编译（无 -sources.jar 时，或整 jar）属于有资源消耗的操作，必要时弹一次确认
     * （canTrust=true：允许"本次会话不再询问"）。有 -sources.jar / 项目内源码则免确认。
     */
    private static String execViewClassSource(JsonObject params, Project project, String toolCallId, ToolExecutionContext ctx) {
        String target = getString(params, "target", "");
        String outputDir = getString(params, "output_dir", "");
        if (target.isBlank()) return "错误：请提供类名或路径 (target)";
        if (project == null) return "错误：未打开项目";

        // 需要反编译时弹确认；有 -sources.jar / 项目内源码则免确认
        if (ctx.confirmProvider != null && FileReaderUtil.needsDecompile(target, project)) {
            try {
                boolean confirmed = ctx.confirmProvider.requestConfirm(
                        toolCallId, "反编译并查看类源码：" + target, "warning", true, "command").get();
                if (!confirmed) {
                    return "已取消：用户拒绝反编译查看 " + target;
                }
            } catch (Exception e) {
                return "错误：确认被中断 - " + e.getMessage();
            }
        }

        return FileReaderUtil.viewClassSource(target, outputDir, project);
    }

    private static String execReadFileRange(JsonObject params, Project project) {
        String filePath = getString(params, "file_path", "");
        int startLine = getInt(params, "start_line", 0);
        int endLine   = getInt(params, "end_line", 0);
        if (filePath.isBlank()) return "错误：请提供文件路径";

        VirtualFile file = findFile(filePath, project);
        if (file == null) {
            return "错误：文件不存在——" + filePath;
        }

        long fileSize = file.getLength();
        String sizeStr = formatFileSize(fileSize);
        boolean isLargeFile = fileSize > 50 * 1024; // 50KB 以上算大文件

        String content = FileReaderUtil.readFileContentLines(file, startLine, endLine, 50000);
        if (content == null) {
            return "错误：无法读取文件——" + filePath;
        }

        StringBuilder result = new StringBuilder();
        result.append("文件: ").append(filePath)
                .append(" (大小: ").append(sizeStr).append(")\n");

        if (startLine <= 0 && endLine <= 0) {
            result.append("读取范围: 整个文件\n");
        } else {
            result.append("读取范围: ");
            if (startLine > 0) result.append("第").append(startLine).append("行");
            if (endLine > 0) {
                if (startLine > 0) result.append(" - ");
                result.append("第").append(endLine).append("行");
            }
            result.append("\n");
        }

        if (isLargeFile && startLine <= 0 && endLine <= 0) {
            result.append("⚠️  提示: 这是一个大文件 (>50KB)，建议分段读取，避免占用过多上下文。\n");
        }

        result.append("\n---\n").append(content);
        return result.toString();
    }

    
     private static String execSearchGrep(JsonObject params, Project project) {
        String keyword = getString(params, "keyword", "");
        String filePattern = getString(params, "file_pattern", "");
        String path = getString(params, "path", "");
        int maxResults = getInt(params, "max_results", 10);
        boolean regex = getBool(params, "regex", false);
        String mode = getString(params, "mode", "text");
        if (keyword.isBlank()) return "错误：请提供搜索关键词";

        // ★ mode=usages：语义引用查找（原独立工具 find_usages 已合并至此），
        if ("usages".equalsIgnoreCase(mode)) {
            if (project == null) return "错误：未打开项目";
            return FileReaderUtil.findSymbolUsages(keyword, Math.max(maxResults, 20), project);
        }

        // 传了 path（且不是当前 IDEA 项目根）-> 任意目录模式（不依赖 IDEA 项目索引）
        if (path != null && !path.isBlank() && !path.equals(".")) {
            return FileReaderUtil.searchGrepInPath(keyword, path, filePattern, maxResults, regex);
        }
        if (project == null) return "错误：未打开项目";
        return FileReaderUtil.searchGrep(keyword, filePattern, maxResults, project, regex);
    }

    /**
     * 列目录工具 —— 列出目录内容，支持glob模式匹配
     */
    private static String execListFiles(JsonObject params, Project project) {
        String path = getString(params, "path", ".");
        String pattern = getString(params, "pattern", "");
        boolean recursive = getBool(params, "recursive", false);
        int maxDepth = getInt(params, "max_depth", 3);
        if (project == null) return "错误：未打开项目";

        // 任意目录模式：path 是绝对路径且不在当前 IDEA 项目内 -> 用 nio 遍历本地文件系统
        if (path != null && !path.isBlank() && !path.equals(".")
                && Paths.get(path).isAbsolute()) {
            Path projRoot = project.getBaseDir() != null
                    ? Paths.get(project.getBaseDir().getPath()) : null;
            Path p = Paths.get(path);
            if (projRoot == null || !p.startsWith(projRoot)) {
                return FileReaderUtil.listDirectoryInPath(path, pattern, recursive, maxDepth, 200);
            }
        }

        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) return "错误：无法获取项目根目录";

        VirtualFile dir;
        if (path == null || path.isBlank() || path.equals(".")) {
            dir = baseDir;
        } else {
            dir = baseDir.findFileByRelativePath(path);
            if (dir == null) {
                dir = LocalFileSystem.getInstance().findFileByPath(path);
            }
        }
        if (dir == null || !dir.exists()) return "错误：目录不存在 — " + path;
        if (!dir.isDirectory()) return "错误：路径不是目录 — " + path;

        boolean hasGlobStar = pattern.contains("**");
        if (hasGlobStar) recursive = true;
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 10) maxDepth = 10;

        StringBuilder sb = new StringBuilder();
        sb.append("📁 ").append(dir.getPath()).append("\n");
        if (!pattern.isBlank()) sb.append("   模式: ").append(pattern).append("\n");
        sb.append("\n");

        List<VirtualFile> matched = new ArrayList<>();
        listFilesRecursive(dir, pattern, recursive ? maxDepth : 1, 0, matched, 200);

        if (matched.isEmpty()) {
            sb.append("(未找到匹配的文件)");
        } else {
            String basePath = baseDir.getPath();
            for (VirtualFile f : matched) {
                String relPath = f.getPath();
                if (relPath.startsWith(basePath)) {
                    relPath = relPath.substring(basePath.length() + 1);
                }
                if (f.isDirectory()) {
                    sb.append("  📂 ").append(relPath).append("/\n");
                } else {
                    String sizeStr = formatFileSize(f.getLength());
                    sb.append("  📄 ").append(relPath)
                            .append("  ").append(sizeStr).append("\n");
                }
            }
            if (matched.size() >= 200) {
                sb.append("\n...(结果超过200条，已截断)");
            }
        }
        return sb.toString();
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private static void listFilesRecursive(VirtualFile dir, String pattern,
                                           int maxDepth, int depth,
                                           List<VirtualFile> results, int maxResults) {
        if (depth > maxDepth || results.size() >= maxResults) return;
        // pattern 是否包含路径分隔符
        boolean hasPathSep = pattern != null && (pattern.contains("/") || pattern.contains("\\"));
        for (VirtualFile child : dir.getChildren()) {
            if (results.size() >= maxResults) break;
            boolean isDir = child.isDirectory();
            if (isDir && shouldSkipDir(child.getName())) continue;

            boolean match;
            if (hasPathSep) {
                // 包含路径分隔符时，用相对路径匹配（这里简单处理：去掉 **/ 前缀后看后缀是否匹配）
                String relPath = child.getName();
                // 简化处理：如果 pattern 含 **/，则匹配文件名或路径后缀
                String simplePattern = pattern;
                while (simplePattern.startsWith("**/")) {
                    simplePattern = simplePattern.substring(3);
                }
                // 对于目录，只看目录名是否匹配；对于文件，看路径后缀
                if (isDir) {
                    match = matchesGlob(child.getName(), simplePattern.contains("/")
                            ? simplePattern.substring(simplePattern.lastIndexOf('/') + 1)
                            : simplePattern);
                } else {
                    // 文件：检查 pattern 的最后一段（文件名部分）是否匹配
                    String fileNamePart = simplePattern.contains("/")
                            ? simplePattern.substring(simplePattern.lastIndexOf('/') + 1)
                            : simplePattern;
                    match = matchesGlob(child.getName(), fileNamePart);
                }
            } else {
                match = matchesGlob(child.getName(), pattern);
            }

            if (match) {
                results.add(child);
            }

            if (isDir) {
                listFilesRecursive(child, pattern, maxDepth, depth + 1, results, maxResults);
            }
        }
    }

    /**
     * 简单glob匹配：支持 * 匹配任意字符（不含路径分隔符），** 已由递归处理
     */
    private static boolean matchesGlob(String fileName, String pattern) {
        if (pattern == null || pattern.isBlank()) return true;
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "")
                .replace("**", ".*")
                .replace("*", "[^/\\\\]*")
                .replace("?", ".");
        return fileName.matches("(?i)" + regex);
    }

    private static boolean shouldSkipDir(String name) {
        if (name.startsWith(".")) return true;
        String[] skip = {"node_modules", "target", "build", "dist", "out",
                ".git", ".idea", ".gradle", "__pycache__", "vendor"};
        for (String s : skip) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * 判断命令是否在写文件（通过重定向或其他方式）
     * 用于拦截模型用命令写文件的行为，引导使用专门的文件操作工具
     */
    private static boolean isFileWritingCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase().trim();

        // 重定向写文件：>、>>（排除 >/dev/null、2>/dev/null 等特殊情况）
        if (lower.matches(".*\\s>\\s*[^>\\s].*")
                || lower.matches(".*\\s>>\\s+.*")
                || lower.matches(".*\\d>\\s*[^>\\s].*")
                || lower.matches(".*\\d>>\\s+.*")) {
            // 排除 /dev/null、nul（Windows）等非文件写入
            if (lower.matches(".*\\s>?\\s*/dev/null.*")
                    || lower.matches(".*\\s>?\\s*nul.*")) {
                return false;
            }
            return true;
        }

        // 典型写文件命令（带写操作参数）
        if (lower.matches("^\\s*(echo|printf|cat|tee)\\s+.*>\\s*.*")) return true;
        if (lower.matches("^\\s*tee\\s+.*\\.\\w+\\s*$")) return true;

        // 直接创建/写入文件的命令（不常用但可能被滥用）
        if (lower.matches("^\\s*dd\\s+.*of=.*")) return true;

        return false;
    }

    /**
     * 判断命令是否为删除文件/目录的命令
     */
    private static boolean isFileDeletingCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase().trim();

        // Linux/Mac: rm、rmdir
        if (lower.matches("^\\s*(sudo\\s+)?rm\\s+.*")) return true;
        if (lower.matches("^\\s*(sudo\\s+)?rmdir\\s+.*")) return true;

        // Windows: del、erase、rmdir、rd
        if (lower.matches("^\\s*del\\s+.*")) return true;
        if (lower.matches("^\\s*erase\\s+.*")) return true;
        if (lower.matches("^\\s*(rmdir|rd)\\s+.*")) return true;

        // PowerShell: Remove-Item、rm、del、erase、rd、ri
        if (lower.matches("^\\s*remove-item\\s+.*")) return true;
        if (lower.matches("^\\s*ri\\s+.*")) return true;

        return false;
    }

    /**
     * 执行命令行工具 —— 智能分类：只读静默执行，有影响控制台执行，高风险需确认
     */
    private static String execRunCommand(JsonObject params, Project project, ToolExecutionContext ctx) {
        String command = getString(params, "command", "");
        String cwd = getString(params, "cwd", "");
        int timeout = getInt(params, "timeout", 30);

        if (command.isBlank()) return "错误：请提供要执行的命令 (command)";
        if (project == null) return "错误：未打开项目";

        // 0. 拦截：禁止用命令写文件，引导使用工具
        if (isFileWritingCommand(command)) {
            return "❌ 禁止通过命令创建/修改文件。\n\n"
                    + "请使用专门的文件操作工具：\n"
                    + "  - write_file：创建或覆盖文件内容\n"
                    + "  - create_new_file：创建新文件\n"
                    + "  - edit_file：精确编辑现有文件\n\n"
                    + "这些工具提供更好的安全性、Diff 预览和撤销支持。";
        }

        // 0.5 拦截：禁止用命令删除文件/目录，引导使用 delete_file 工具
        if (isFileDeletingCommand(command)) {
            return "❌ 禁止通过命令删除文件或目录。\n\n"
                    + "请使用专门的 delete_file 工具：\n"
                    + "  - delete_file：安全删除文件或目录\n"
                    + "  - 支持 recursive 参数递归删除目录\n\n"
                    + "比 rm/del 命令更安全，有明确的操作记录和确认机制。";
        }

        // 1. 命令分类
        CommandClassifier.Category category = CommandClassifier.classify(command);

        // 2. 高风险/可写命令：需要用户确认
        if (category == CommandClassifier.Category.DANGEROUS || category == CommandClassifier.Category.CONSOLE) {
            String level = category == CommandClassifier.Category.DANGEROUS ? "danger" : "warning";
            boolean canTrust = category != CommandClassifier.Category.DANGEROUS; // 高危命令不能跳过

            // 先检查信任名单
            if (canTrust && ctx.confirmProvider != null && ctx.confirmProvider.isTrusted(command)) {
                // 已信任，直接执行
                System.out.println("[ToolConfirm] command trusted, skip confirm: " + command);
            } else if (ctx.confirmProvider != null) {
                // 使用内嵌消息体确认
                String toolCallId = "cmd-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 1000);
                System.out.println("[ToolConfirm] requesting confirm for toolCallId=" + toolCallId + ", command=" + command);
                try {
                    boolean confirmed = ctx.confirmProvider.requestConfirm(toolCallId, command, level, canTrust, "command").get();
                    System.out.println("[ToolConfirm] confirm result: " + confirmed + " for toolCallId=" + toolCallId);
                    if (!confirmed) {
                        return "已取消：用户拒绝执行命令。";
                    }
                    // 如果用户勾选了"不再询问"，加入信任名单
                    // （信任状态由UI层通过 addTrusted 管理，这里只检查）
                } catch (Exception e) {
                    System.out.println("[ToolConfirm] confirm exception: " + e.getMessage());
                    return "错误：确认被中断 - " + e.getMessage();
                }
            } else {
                // 降级：同步弹窗（当没有 UI 确认提供者时）
                final boolean[] confirmed = {false};
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    int choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                            project,
                            "检测到" + (category == CommandClassifier.Category.DANGEROUS ? "高风险" : "")
                                    + "命令，确认执行吗？\n\n命令：" + command +
                                    "\n\n此操作可能对您的系统或代码造成影响。",
                            "命令执行确认",
                            "执行",
                            "取消",
                            com.intellij.openapi.ui.Messages.getWarningIcon()
                    );
                    confirmed[0] = (choice == com.intellij.openapi.ui.Messages.YES);
                });
                if (!confirmed[0]) {
                    return "已取消：用户拒绝执行命令。";
                }
            }
        }

        // 3. 只读命令：静默执行
        if (category == CommandClassifier.Category.READ_ONLY) {
            CommandRunner.Result result = CommandRunner.execute(command, project, cwd, timeout);
            return result.formatOutput();
        }

        // 4. 控制台执行（CONSOLE 和 DANGEROUS 都走这里，DANGEROUS 已经确认过了）
        String workDir = (cwd != null && !cwd.isBlank()) ? cwd : project.getBasePath();
        long timeoutMs = Math.min(timeout, 120) * 1000L;
        CommandRunner.Result result = ConsoleCommandRunner.runInConsole(
                project, command, workDir, timeoutMs,
                handler -> currentConsoleHandler = handler,
                cancelled
        );
        return result.formatOutput();
    }

    private static String execValidateCode(JsonObject params, Project project) {
        com.google.gson.JsonArray filePathsArr = params.getAsJsonArray("file_paths");
        if (filePathsArr == null || filePathsArr.isEmpty()) {
            return "错误：请提供要验证的文件路径列表 (file_paths)";
        }
        if (project == null) return "错误：未打开项目";

        java.util.List<String> filePaths = new java.util.ArrayList<>();
        for (int i = 0; i < filePathsArr.size(); i++) {
            filePaths.add(filePathsArr.get(i).getAsString());
        }

        System.out.println("[ErrorValidationAgent] 开始验证 " + filePaths.size() + " 个文件");

        com.codepal.agent.subagent.ErrorValidationAgent agent =
                new com.codepal.agent.subagent.ErrorValidationAgent(project);
        com.codepal.agent.subagent.AgentResult result = agent.execute(filePaths, null);

        System.out.println("[ErrorValidationAgent] 验证完成, success=" + result.isSuccess());

        return result.getContent();
    }

    /**
     * 增量编译工具 —— 调用 IDEA 自带编译器只编译指定文件（及其编译必需的依赖），
     * 不受项目其他文件预存错误影响，用于验证模型改过的文件能否编译通过。
     *
     * <p>相比 run_command 跑 mvn/gradle 全量编译：全量编译会因任意一处预存错误整体失败，
     * 掩盖模型改动的文件本身是否正确；本工具按文件作用域编译，能精确反映改动文件的编译状态。
     * 工具执行跑在后台线程（executeOnPooledThread），用 CountDownLatch 等待编译结束不会阻塞 EDT。
     */
    private static String execCompileFiles(JsonObject params, Project project) {
        com.google.gson.JsonArray arr = params.getAsJsonArray("file_paths");
        if (arr == null || arr.isEmpty()) {
            return "错误：请提供要编译的文件路径列表 (file_paths)";
        }
        String scope = getString(params, "scope", "files");
        if (project == null) return "错误：未打开项目";

        List<VirtualFile> files = new ArrayList<>();
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            String p = arr.get(i).getAsString();
            VirtualFile vf = findFile(p, project);
            if (vf == null || !vf.exists()) {
                missing.append("  - ").append(p).append("\n");
                continue;
            }
            files.add(vf);
        }
        if (files.isEmpty()) {
            return "错误：以下文件均不存在，无法编译：\n" + missing;
        }

        // 用 CompilerManager.compile 的回调拿错误计数（CompileStatusNotification 给出
        // aborted/errors/warnings/time）。不依赖 CompileTask 注册机制——其在新版 IntelliJ
        // Platform 反复变动（addCompileTask/CompileTaskRegistry 均不稳定），而「验证能否编译通过」
        // 只需 errors 计数即可（0 错误即通过）。详情由用户/IDEA  Problems 视图进一步查看。
        // module 作用域：收集这些文件所属模块（2026.1 无 compile(Module[])，需逐个模块编译）
        final boolean moduleScope = "module".equals(scope);
        final java.util.List<Module> mods = new java.util.ArrayList<>();
        if (moduleScope) {
            for (VirtualFile vf : files) {
                Module m = ModuleUtilCore.findModuleForFile(vf, project);
                if (m != null && !mods.contains(m)) mods.add(m);
            }
            if (mods.isEmpty()) {
                return "错误：无法确定这些文件所属模块，无法按模块编译。可改用默认的 files 作用域。";
            }
        }

                final int[] counts = {-1, -1}; // [errors, warnings]，-1 表示尚未回调
                // ★ 逐条错误收集：通过 MessageBus 订阅 CompilerTopics.COMPILATION_STATUS，
                //   在编译结束回调里从 CompileContext 拉取全部 CompilerMessage（路径/行/列/描述），
                //   让工具直接返回错误详情，模型无需再让用户去 Problems 面板人工查看。
                final java.util.List<String> errorLines = new ArrayList<>();
                final java.util.List<String> warnLines = new ArrayList<>();
                final Object msgLock = new Object();
                MessageBusConnection msgConn = project.getMessageBus().connect();
                msgConn.subscribe(CompilerTopics.COMPILATION_STATUS, new CompilationStatusListener() {
                    @Override
                    public void compilationFinished(boolean aborted, int errors, int warnings,
                                                    com.intellij.openapi.compiler.CompileContext context) {
                        try {
                            if (context != null) {
                                CompilerMessage[] msgs = context.getMessages(CompilerMessageCategory.ERROR);
                                if (msgs != null) {
                                    synchronized (msgLock) {
                                        for (CompilerMessage m : msgs) {
                                            if (m == null) continue;
                                            String line = formatCompilerMessage(m, "ERROR");
                                            if (!line.isEmpty()) errorLines.add(line);
                                        }
                                    }
                                }
                                CompilerMessage[] warns = context.getMessages(CompilerMessageCategory.WARNING);
                                if (warns != null && warns.length > 0) {
                                    synchronized (msgLock) {
                                        warnLines.add("（另有 " + warns.length + " 个警告，未逐条列出）");
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            System.err.println("[compile_files] 收集编译消息失败: " + t);
                        }
                    }
                });
                // files 作用域 1 次回调；module 作用域每个模块各回调一次
                java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(moduleScope ? mods.size() : 1);
         
         try {
             CompilerManager cm = CompilerManager.getInstance(project);
             com.intellij.openapi.compiler.CompileStatusNotification callback =
                    (aborted, errors, warnings, time) -> {
                        // 真正记录错误/警告计数（此前只 countDown，counts 永远是 -1，
                        // 导致编译成功也误报「共 -1 个错误」）
                        counts[0] = errors;
                        counts[1] = warnings;
                        latch.countDown();
                    };
            // ★ 2024.1+ 平台要求 compile() 的「启动」必须在 EDT（write-safe context）上执行，
            //   在后台线程直接调用会抛 "Access is allowed from Event Dispatch Thread (EDT) only"。
            //   故用 invokeLater 切到 EDT 启动编译；latch.await 仍在后台线程等待，不阻塞 EDT。
            final CompilerManager cmFinal = cm;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    if (moduleScope) {
                        for (Module m : mods) {
                            cmFinal.compile(m, callback);
                        }
                    } else {
                        VirtualFile[] vfs = files.toArray(new VirtualFile[0]);
                        cmFinal.compile(vfs, callback);
                    }
                } catch (Throwable t) {
                    // EDT 上启动失败也要放行 latch，避免后台线程白等 180s
                    System.err.println("[compile_files] 编译启动失败: " + t);
                    long n = latch.getCount();
                    for (long i = 0; i < n; i++) latch.countDown();
                }
            });
                        // 阻塞等待编译结束（本工具在后台线程执行，await 不会阻塞 EDT，安全）
                         latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
                     } catch (InterruptedException e) {
                         Thread.currentThread().interrupt();
                         msgConn.disconnect();
                         return "编译等待被中断：" + e.getMessage();
                } catch (Exception e) {
                     msgConn.disconnect();
                     return "编译执行异常：" + e.getMessage();
                 }
                // 编译已结束，及时断开 MessageBus 监听，避免泄漏
                msgConn.disconnect();
         
                 int errCount = counts[0];
        int warnCount = counts[1];
        if (errCount < 0) {
            // 180s 内未收到任何编译回调：启动失败或超时
            return "❌ 编译未能在时限内完成（未收到编译器回调）。"
                    + "\n可能原因：编译器未启动、项目索引未就绪或超时（>180s）。"
                    + "\n可在 IDEA 的 Build 窗口查看是否有编译任务在执行，稍后重试。";
        }

                 StringBuilder sb = new StringBuilder();
                 if (errCount == 0) {
                     sb.append("✅ 编译通过（").append(files.size()).append(" 个文件，0 错误");
                     if (warnCount > 0) sb.append("，").append(warnCount).append(" 个警告");
                     sb.append("）。每文件一行汇总：\n");
                     for (VirtualFile vf : files) {
                         sb.append("  - ").append(vf.getName()).append("：成功\n");
                     }
                 } else {
                     sb.append("❌ 编译未通过：共 ").append(errCount).append(" 个错误");
                     if (warnCount > 0) sb.append("，").append(warnCount).append(" 个警告");
                     sb.append("。逐条错误如下（文件:行:列 描述）：\n");
                     synchronized (msgLock) {
                         for (String line : errorLines) {
                             sb.append(line);
                         }
                     }
                     if (errorLines.isEmpty()) {
                         sb.append("（未从编译上下文取到逐条错误详情，可在 IDEA 的 Problems/编译输出面板查看）\n");
                     }
                     sb.append("\n提示：以上错误针对你请求编译的文件及其编译必需依赖；")
                       .append("若其中存在你未改动的文件（项目预存问题）引发的错误，请忽略它们，专注修复你改动的文件。");
                 }
        if (!missing.toString().isEmpty()) {
            sb.append("\n⚠️ 以下文件未找到，已跳过：\n").append(missing);
        }
        return sb.toString();
    }

    /** 把 CompilerMessage 格式化为「路径 描述」一行（带换行）；无关联文件时退化为纯描述 */
    private static String formatCompilerMessage(CompilerMessage m, String category) {
        String txt = m.getMessage();
        if (txt == null || txt.isEmpty()) return "";
        StringBuilder line = new StringBuilder();
        com.intellij.openapi.vfs.VirtualFile vf = m.getVirtualFile();
        if (vf != null) {
            line.append(vf.getPath()).append(" ");
        }
        line.append("[").append(category).append("] ").append(txt.trim()).append('\n');
        return line.toString();
    }

    /**
     * 整文件写入工具（可写）—— 覆盖写入文件，Plan模式禁止，Craft模式直接写
     */
    private static String execWriteFile(JsonObject params, Project project, boolean skipConfirmation) {
        if (!skipConfirmation) {
            return "错误：Plan 模式下不允许创建或修改文件。请切换到 Craft 模式后再试。";
        }
        String filePath = getString(params, "file_path", "");
        String content = getString(params, "file_content", "");

        if (filePath.isBlank()) return "错误：请提供文件路径 (file_path)";
        if (project == null) return "错误：未打开项目";

        java.io.File targetFile = FileOperationService.resolveFilePath(filePath, project);
        boolean fileExists = targetFile.exists();

        try {
            FileOperationService.writeFile(filePath, content, project);
            java.io.File writtenFile = FileOperationService.resolveFilePath(filePath, project);
            if (!writtenFile.exists()) {
                return "错误：写入失败 — 文件不存在（未知原因）";
            }
            String action = fileExists ? "覆盖写入" : "创建并写入";
            long fileSize = writtenFile.length();
            return "✅ " + action + "成功。文件：" + writtenFile.getAbsolutePath()
                    + "（" + fileSize + " 字节）";
        } catch (Exception e) {
            return "错误：写入失败 — " + e.getMessage();
        }
    }

    /**
     * 编辑文件工具（可写）—— Plan 模式禁止；Craft 模式直接修改文件
     */
    private static String execEditFile(JsonObject params, Project project, boolean skipConfirmation) {
        if (!skipConfirmation) {
            return "错误：Plan 模式下不允许修改文件。请切换到 Craft 模式后再试。";
        }
        String filePath = getString(params, "file_path", "");

        if (filePath.isBlank()) return "错误：请提供文件路径 (file_path)";
        if (project == null) return "错误：未打开项目";

        // 读取文件内容
        String originalContent;
        try {
            originalContent = FileOperationService.readFile(filePath, project);
        } catch (Exception e) {
            return "错误：读取文件失败 — " + e.getMessage();
        }

        // ── 解析编辑列表：支持单次 search/replace 或批量 edits ──
        List<String[]> editPairs = new ArrayList<>();
        if (params.has("edits") && params.get("edits").isJsonArray()) {
            for (com.google.gson.JsonElement e : params.getAsJsonArray("edits")) {
                JsonObject pair = e.getAsJsonObject();
                String s = getString(pair, "search", "");
                String r = getString(pair, "replace", "");
                if (s.isBlank()) return "错误：edits 中某元素的 search 为空";
                editPairs.add(new String[]{s, r});
            }
        } else {
            String search = getString(params, "search", "");
            String replace = getString(params, "replace", "");
            if (search.isBlank()) return "错误：请提供 search 或 edits 参数";
            editPairs.add(new String[]{search, replace});
        }

        // 逐一校验唯一性，再顺序应用替换（精确/柔性/正则三级匹配）
        EditResult er = applyEdits(originalContent, editPairs);
        if (!er.success) return er.error;
        String newContent = er.content;

        // ── Craft 模式：通过 VFS 写盘（支持 IDE 撤销） ──
        try {
            FileOperationService.writeFileViaVfs(filePath, newContent, project, "CP - AI 批量修改文件");
        } catch (Exception e) {
            return "错误：写入失败 — " + e.getMessage();
        }
        VirtualFile vf = FileOperationService.findVirtualFile(filePath, project);
        openFileInEditorIfEnabled(project, vf);
        return "✅ 修改已应用。文件 \"" + filePath + "\" 共应用 "
                + editPairs.size() + " 处编辑。";
    }

    /**
     * 新建文件工具（可写）—— Plan 模式禁止；Craft 模式直接创建并打开
     */
    private static String execCreateNewFile(JsonObject params, Project project, boolean skipConfirmation) {
        if (!skipConfirmation) {
            return "错误：Plan 模式下不允许创建文件。请切换到 Craft 模式后再试。";
        }
        String filePath = getString(params, "file_path", "");
        String content = getString(params, "content", "");
        if (content.isEmpty()) {
            content = getString(params, "file_content", "");
        }

        if (filePath.isBlank()) return "错误：请提供文件路径 (file_path)";
        if (project == null) return "错误：未打开项目";

        try {
            java.io.File targetFile = FileOperationService.createNewFile(filePath, content, project);
            java.io.File verifyFile = FileOperationService.resolveFilePath(filePath, project);
            if (!verifyFile.exists()) {
                return "错误：创建失败 — 文件不存在（未知原因）";
            }
            long fileSize = verifyFile.length();
            VirtualFile vf = FileOperationService.findVirtualFile(filePath, project);
            openFileInEditorIfEnabled(project, vf);
            return "✅ 文件已创建。路径：" + verifyFile.getAbsolutePath()
                    + "（" + fileSize + " 字节）";
        } catch (Exception e) {
            return "错误：创建文件失败 — " + e.getMessage();
        }
    }

    /**
     * 按设置决定是否在编辑器中打开文件。
     * <ul>
     *   <li>设置关闭（openFileOnEdit=false）→ 不打开</li>
     *   <li>文件已打开 → 不重复打开，避免抢占焦点/闪烁</li>
     *   <li>否则在 EDT 中打开（focus=false，不抢占用户输入焦点）</li>
     * </ul>
     */
    private static void openFileInEditorIfEnabled(Project project, VirtualFile vf) {
        if (vf == null || project == null) return;
        if (!CPSettings.getInstance().isOpenFileOnEdit()) return;
        FileEditorManager fem = FileEditorManager.getInstance(project);
        if (fem.isFileOpen(vf)) return; // 已打开的不必再打开
        ApplicationManager.getApplication().invokeLater(() -> fem.openFile(vf, false));
    }

    private static String execCreateDirectory(JsonObject params, Project project, boolean skipConfirmation) {
        if (!skipConfirmation) {
            return "错误：Plan 模式下不允许创建目录。请切换到 Craft 模式后再试。";
        }
        String dirPath = getString(params, "dir_path", "");
        if (dirPath.isBlank()) return "错误：请提供目录路径 (dir_path)";
        if (project == null) return "错误：未打开项目";

        try {
            java.io.File targetDir = FileOperationService.createDirectory(dirPath, project);
            return "✅ 目录已创建。路径：" + targetDir.getAbsolutePath();
        } catch (Exception e) {
            return "错误：创建目录失败 — " + e.getMessage();
        }
    }

    /**
     * 删除文件工具（可写）—— Plan 模式禁止；Craft 模式删除前弹确认卡
     *
     * <p>删除属于不可逆操作，即便在 Craft 模式也走确认弹出（canTrust=false，不可"不再询问"），
     * 复用与 run_command 相同的 ToolConfirmProvider 链路（内嵌 HTML 确认卡）。
     */
    private static String execDeleteFile(JsonObject params, Project project, boolean skipConfirmation, String toolCallId, ToolExecutionContext ctx) {
        if (!skipConfirmation) {
            return "错误：Plan 模式下不允许删除文件。请切换到 Craft 模式后再试。";
        }
        String filePath = getString(params, "file_path", "");
        boolean recursive = getBool(params, "recursive", false);

        if (filePath.isBlank()) return "错误：请提供文件路径 (file_path)";
        if (project == null) return "错误：未打开项目";

        java.io.File targetFile = FileOperationService.resolveFilePath(filePath, project);
        if (!targetFile.exists()) {
            return "错误：文件不存在 — " + targetFile.getAbsolutePath();
        }

        String type = targetFile.isDirectory() ? "目录" : "文件";

        // ── 删除前确认（不可逆操作，始终弹窗；canTrust=false 不允许"本次会话不再询问"）──
        if (ctx.confirmProvider != null) {
            String desc = "删除" + type + "：" + targetFile.getAbsolutePath()
                    + (recursive && targetFile.isDirectory() ? "\n（递归删除目录及其全部内容，操作不可逆）" : "");
            try {
                boolean confirmed = ctx.confirmProvider.requestConfirm(toolCallId, desc, "danger", false, "delete").get();
                if (!confirmed) {
                    return "已取消：用户拒绝删除" + type + " — " + targetFile.getAbsolutePath();
                }
            } catch (Exception e) {
                return "错误：删除确认被中断 - " + e.getMessage();
            }
        }

        try {
            boolean success = FileOperationService.deleteFile(filePath, recursive, project);
            if (success) {
                return "✅ 已删除" + type + "：" + targetFile.getAbsolutePath();
            } else {
                return "错误：删除失败 — " + targetFile.getAbsolutePath();
            }
        } catch (Exception e) {
            return "错误：删除失败 — " + e.getMessage();
        }
    }

    /**
     * 加载技能文档（按需）。优先读取用户机器可写目录的技能文件，
     * 缺失时回退到插件内置资源；首次访问会自动从内置资源拷贝出厂默认。
     */
    /**
     * 查看图片工具 —— 把图片交给独立视觉模型理解，返回文字描述。
     * 主模型（可能是纯文本模型）通过它间接"看"用户附带的图片。
     */
    private static String execViewImage(JsonObject params, ToolExecutionContext ctx) {
        String imagePath = getString(params, "image_path", "");
        if (imagePath.isBlank()) return "错误：请提供图片路径 (image_path)";
        String question = getString(params, "question", "");
        long t0 = System.currentTimeMillis();
        System.out.println("[view_image] 开始执行（视觉子智能体）thread=" + Thread.currentThread().getName()
                + " image_path=" + imagePath);

        try {
            // 统一走 SubAgentManager 的视觉子智能体；未接线时回退到直接调用 VisionClient
            if (ctx.subAgentManager == null) {
                com.codepal.api.VisionClient client = new com.codepal.api.VisionClient();
                String desc = client.describe(imagePath, "", question);
                System.out.println("[view_image] 执行完成 耗时=" + (System.currentTimeMillis() - t0)
                        + "ms 返回长度=" + (desc == null ? 0 : desc.length()));
                if (desc == null || desc.isEmpty()) return "视觉模型未返回任何内容。";
                return "【视觉模型返回的图片内容】\n" + desc;
            }
            com.codepal.agent.subagent.AgentResult result =
                    ctx.subAgentManager.viewImage(imagePath, "", question, null);
            System.out.println("[view_image] 执行完成 耗时=" + (System.currentTimeMillis() - t0)
                    + "ms success=" + result.isSuccess()
                    + " 返回长度=" + (result.getContent() == null ? 0 : result.getContent().length()));
            if (result.isSuccess()) return result.getContent();
            return "查看图片失败：" + result.getErrorMessage();
        } catch (Exception e) {
            System.out.println("[view_image] 执行异常 耗时=" + (System.currentTimeMillis() - t0) + "ms: " + e.getMessage());
            return "查看图片失败：" + e.getMessage();
        }
    }

    private static String execLoadSkill(JsonObject params, Project project) {
        boolean wantList = params.has("list") && params.get("list").getAsBoolean();
        com.codepal.settings.CPSettings settings = com.codepal.settings.CPSettings.getInstance();
        if (wantList) {
            // 仅列出已启用的技能（未勾选的模型不可见）
            java.util.List<String> names = com.codepal.skills.SkillStore.listSkills();
            names.removeIf(n -> !settings.isSkillEnabled(n));
            if (names.isEmpty()) return "当前没有已启用的技能（请在输入框的 Skills 下拉框中勾选启用）。";
            StringBuilder sb = new StringBuilder("已启用技能：\n");
            for (String n : names) sb.append("  - ").append(n).append("\n");
            return sb.toString();
        }

        String name = getString(params, "name", "source-navigation");
        // 未启用（未勾选）的技能对模型不可见、也不可读
        if (!settings.isSkillEnabled(name)) {
            return "错误：未找到技能 \"" + name + "\"（该技能未启用，请在输入框的 Skills 下拉框中勾选启用；"
                    + "已启用技能可通过 load_skill(list=true) 查看）。";
        }
        String content = com.codepal.skills.SkillStore.readSkill(name);
        if (content == null || content.isBlank()) {
            return "错误：未找到技能 \"" + name + "\"。已启用技能可通过 load_skill(list=true) 查看。";
        }
        return "【技能：" + name + "】\n" + content;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  数据库查询工具：连接已配置数据源（MySQL / SQLite）执行 SQL
    //  schema 模式返回表结构；data 模式执行 SQL 并硬性限制 10 行
    // ─────────────────────────────────────────────────────────────────────────

    /** 表数据查询硬上限（按需求硬编码为 10 行） */
    private static final int QUERY_DATABASE_MAX_ROWS = 10;

    // ═════════════════════════════════════════════════════════════════════════
    //  SQL 安全校验（参考 yours_agent DatabaseTools 的四层校验）
    //  注意：SHOW 属于只读查询（SHOW TABLES/DATABASES/COLUMNS 等对探索很有用），
    //        因此【不】把 SHOW 列入禁用词，反而允许其作为合法开头。
    // ═════════════════════════════════════════════════════════════════════════

    /** 禁止的写操作/危险关键词（不区分大小写） */
    private static final String[] SQL_FORBIDDEN_KEYWORDS = {
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER",
            "TRUNCATE", "CREATE", "REPLACE", "RENAME", "CALL",
            "GRANT", "REVOKE", "SET", "LOCK", "UNLOCK",
            "COMMIT", "ROLLBACK", "SAVEPOINT", "EXEC", "EXECUTE",
            "MERGE", "UPSERT", "LOAD_FILE", "OUTFILE", "DUMPFILE",
            "INTO"
    };

    /** 预编译禁止词正则（static 块一次性编译，避免每次查询重复编译） */
    private static final java.util.regex.Pattern[] SQL_FORBIDDEN_PATTERNS;

    /** 允许作为 SQL 开头的只读关键字 */
    private static final String[] SQL_ALLOWED_PREFIXES = {"SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"};

    /** 表名白名单：仅字母、数字、下划线（防注入） */
    private static final java.util.regex.Pattern TABLE_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");

    static {
        SQL_FORBIDDEN_PATTERNS = new java.util.regex.Pattern[SQL_FORBIDDEN_KEYWORDS.length];
        for (int i = 0; i < SQL_FORBIDDEN_KEYWORDS.length; i++) {
            SQL_FORBIDDEN_PATTERNS[i] = java.util.regex.Pattern.compile(
                    "\\b" + SQL_FORBIDDEN_KEYWORDS[i] + "\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
        }
    }

    /**
     * 校验 data 模式的 SQL：只允许只读查询。
     * @return 错误信息；合法时返回 null
     */
    private static String validateReadOnlySql(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "SQL 为空。";
        String s = sql.trim();

        // 1) 禁止多语句（分号注入）
        if (s.contains(";")) {
            return "禁止执行多条语句（SQL 中不得包含分号 `;`）。当前 SQL：" + s;
        }
        // 2) 必须以只读关键字开头（SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN）
        String upper = s.replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
        boolean okPrefix = false;
        for (String p : SQL_ALLOWED_PREFIXES) {
            if (upper.startsWith(p)) { okPrefix = true; break; }
        }
        if (!okPrefix) {
            return "只允许只读查询（必须以 " + String.join(" / ", SQL_ALLOWED_PREFIXES) + " 开头）。当前 SQL 开头为：`"
                    + upper.substring(0, Math.min(20, upper.length())) + "`";
        }
        // 3) 禁止危险关键词
        for (int i = 0; i < SQL_FORBIDDEN_PATTERNS.length; i++) {
            if (SQL_FORBIDDEN_PATTERNS[i].matcher(s).find()) {
                return "禁止在查询中使用写操作/危险关键词 `" + SQL_FORBIDDEN_KEYWORDS[i]
                        + "`。本工具仅支持只读查询。当前 SQL：" + s;
            }
        }
        return null;
    }

    /** 校验表名合法性（防注入） */
    private static String validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) return "表名为空。";
        if (!TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            return "表名只允许字母、数字、下划线，当前为：`" + tableName + "`";
        }
        return null;
    }

    private static String execQueryDatabase(JsonObject params) {
        String dbName = getString(params, "db_name", "");
        String dbType = getString(params, "db_type", "").toLowerCase(java.util.Locale.ROOT);
        String mode = getString(params, "mode", "").toLowerCase(java.util.Locale.ROOT);
        String tableName = getString(params, "table_name", "");
        String sql = getString(params, "sql", "");

        // 模型未传 db_name 时回退到 ChatPanel 当前选中的数据源（让"选了就能直接用"，无需每次重复指定）。
        if (dbName.isBlank()) {
            String fallback = defaultDataSourceName;
            if (fallback != null && !fallback.isBlank()) {
                dbName = fallback;
            } else {
                return "错误：缺少 db_name（数据源名称），且输入框左侧「数据源」下拉框未选中具体数据源。"
                        + "请先在「数据源」按钮里选择/配置一个数据源，或在调用时显式传 db_name。";
            }
        }
        if (!"schema".equals(mode) && !"data".equals(mode)) {
            return "错误：mode 必须是 \"schema\"（表结构查询）或 \"data\"（表数据查询），当前收到 \"" + mode + "\"。";
        }

        // ── 执行前安全校验（连库之前先拦，避免无谓的连接开销）──
        if ("data".equals(mode)) {
            String err = validateReadOnlySql(sql);
            if (err != null) return "SQL 安全校验未通过：" + err
                    + "\n本工具仅支持只读查询（SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN）。";
        } else {
            String err = validateTableName(tableName);
            if (err != null) return "SQL 安全校验未通过：" + err;
        }

        DataSourceDao.DatabaseConnectionInfo info = DataSourceDao.getByName(dbName);
        if (info == null) {
            return "错误：未找到名为 \"" + dbName + "\" 的数据源。可用数据源见工具描述中的清单；"
                    + "若确实需要该库，请让用户先在工具栏左侧的「数据源」按钮里配置。";
        }
        // db_type 允许省略：从数据源配置自动推断（去掉 required 后模型可能不传）
        String actualType = info.type == null ? "mysql" : info.type.toLowerCase(java.util.Locale.ROOT);
        if (dbType.isBlank()) {
            dbType = actualType;
        } else if (!actualType.equals(dbType)) {
            return "错误：数据源 \"" + dbName + "\" 的类型是 \"" + actualType
                    + "\"，与请求 db_type=\"" + dbType + "\" 不一致。";
        }

        String jdbcUrl = info.buildJdbcUrl();
        try {
            if ("sqlite".equals(dbType)) {
                Class.forName("org.sqlite.JDBC");
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
        } catch (ClassNotFoundException e) {
            return "错误：缺少 JDBC 驱动（" + dbType + "）。请联系开发者在 build.gradle.kts 中添加对应依赖。";
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl,
                (info.user == null || info.user.isEmpty()) ? null : info.user,
                (info.password == null) ? null : info.password)) {

            // 记录实际提交给数据库的 SQL（data 模式下含拼接的 LIMIT），供异常反馈如实回显
            final String[] actualSqlHolder = new String[]{null};
            try {
                if ("schema".equals(mode)) {
                    if (tableName.isBlank()) {
                        return "错误：schema 模式需要 table_name 参数（要查看结构的表名）。";
                    }
                    return describeTable(conn, tableName);
                }
                if (sql.isBlank()) {
                    return "错误：data 模式需要 sql 参数（要执行的查询语句）。";
                }
                return queryTableData(conn, sql, actualSqlHolder);
            } catch (SQLException e) {
                // ★ 如实反馈：不简化、不吞异常，把完整诊断交还给模型
                return formatSqlError(e, dbName, dbType, info, mode, tableName, sql,
                        actualSqlHolder[0], jdbcUrl);
            }
        } catch (SQLException e) {
            // 连接建立本身失败（getConnection 抛出）
            return formatSqlError(e, dbName, dbType, info, mode, tableName, sql, null, jdbcUrl);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SQL 异常如实反馈
    //  设计：execQueryDatabase 的契约是"返回 String 给模型"，若真把 SQLException 抛出去
    //  会冒泡到工具框架通用兜底、格式不可控；因此在【最外层】catch 后转为完整诊断报告，
    //  而 queryTableData / describeTable 自身不 catch（异常自然冒泡，信息不丢）。
    // ═════════════════════════════════════════════════════════════════════════

    /** 堆栈最大回显帧数（控制 context 体积；原始 message / SQLState / ErrorCode / 异常链不截断） */
    private static final int SQL_STACK_MAX_LINES = 15;

    /**
     * 把 SQLException 完整、如实地格式化为给模型的诊断报告。
     * 包含：异常类型、原始 message、SQLState、ErrorCode、异常链（nextException + cause）、
     * 堆栈摘要、实际执行的 SQL、数据源连接上下文（密码脱敏）、常见错误码释义。
     */
    private static String formatSqlError(SQLException e, String dbName, String dbType,
                                         DataSourceDao.DatabaseConnectionInfo info,
                                         String mode, String tableName, String rawSql,
                                         String actualSql, String jdbcUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("【数据库查询失败 - 完整诊断】\n\n");

        // 1) 主异常
        sb.append("■ 异常类型：").append(e.getClass().getName()).append("\n");
        sb.append("■ 错误信息：").append(e.getMessage() == null ? "(无 message)" : e.getMessage()).append("\n");
        sb.append("■ SQLState：").append(e.getSQLState() == null ? "(无)" : e.getSQLState()).append("\n");
        sb.append("■ ErrorCode：").append(e.getErrorCode()).append("\n");

        // 2) 异常链（MySQL 常把真实原因藏在 nextException 里）
        int idx = 0;
        SQLException next = e.getNextException();
        while (next != null && idx < 5) {
            idx++;
            sb.append("\n■ 链式异常 #").append(idx).append("：")
                    .append(next.getClass().getSimpleName()).append("\n");
            sb.append("  - message：").append(next.getMessage() == null ? "(无)" : next.getMessage()).append("\n");
            sb.append("  - SQLState：").append(next.getSQLState() == null ? "(无)" : next.getSQLState()).append("\n");
            sb.append("  - ErrorCode：").append(next.getErrorCode()).append("\n");
            next = next.getNextException();
        }
        Throwable cause = e.getCause();
        int cIdx = 0;
        while (cause != null && cIdx < 5) {
            cIdx++;
            sb.append("\n■ Cause #").append(cIdx).append("：")
                    .append(cause.getClass().getName())
                    .append("：").append(cause.getMessage() == null ? "(无)" : cause.getMessage()).append("\n");
            cause = cause.getCause();
        }

        // 3) 堆栈摘要
        sb.append("\n■ 堆栈摘要（前 ").append(SQL_STACK_MAX_LINES).append(" 帧）：\n");
        java.io.StringWriter sw = new java.io.StringWriter();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(sw)) {
            e.printStackTrace(pw);
        }
        String[] lines = sw.toString().split("\\R");
        for (int i = 0; i < Math.min(lines.length, SQL_STACK_MAX_LINES); i++) {
            sb.append("  ").append(lines[i]).append("\n");
        }
        if (lines.length > SQL_STACK_MAX_LINES) {
            sb.append("  ... (共 ").append(lines.length).append(" 行，已截断)\n");
        }

        // 4) 执行的 SQL（原始 + 拼接后实际）
        sb.append("\n■ 执行的 SQL：\n");
        sb.append("  - 模式：").append(mode).append("\n");
        if (!tableName.isBlank()) sb.append("  - 表名：").append(tableName).append("\n");
        if (rawSql != null && !rawSql.isBlank()) sb.append("  - 原始 SQL：").append(rawSql).append("\n");
        if (actualSql != null && !actualSql.isBlank()) {
            sb.append("  - 实际执行（已自动拼接 LIMIT ").append(QUERY_DATABASE_MAX_ROWS).append("）：")
                    .append(actualSql).append("\n");
        }

        // 5) 数据源连接上下文（密码脱敏）
        sb.append("\n■ 数据源连接上下文：\n");
        sb.append("  - 数据源名称：").append(dbName).append("\n");
        sb.append("  - 类型：").append(dbType).append("\n");
        if (info != null) {
            sb.append("  - 主机：").append(info.host == null ? "(空)" : info.host).append("\n");
            sb.append("  - 端口：").append(info.port).append("\n");
            sb.append("  - 数据库：").append(info.dbName == null ? "(空)" : info.dbName).append("\n");
            sb.append("  - 用户名：").append(info.user == null ? "(空)" : info.user).append("\n");
            sb.append("  - 密码：******（已脱敏）\n");
        }
        String maskedUrl = maskPassword(jdbcUrl, info == null ? null : info.password);
        if (maskedUrl != null && !maskedUrl.isBlank()) {
            sb.append("  - JDBC URL：").append(maskedUrl).append("\n");
        }

        // 6) 常见错误码释义（仅辅助提示，不覆盖上面的原始信息）
        String hint = hintForSqlError(e.getSQLState(), e.getErrorCode(), e.getMessage());
        if (hint != null) {
            sb.append("\n■ 可能原因（辅助判断，以上述原始信息为准）：").append(hint).append("\n");
        }

        // 7) 整体脱敏：万一异常文本里带出密码
        return maskPassword(sb.toString(), info == null ? null : info.password);
    }

    /** 把文本中出现的真实密码替换为 ******（防泄露给模型） */
    private static String maskPassword(String text, String password) {
        if (text == null) return null;
        if (password == null || password.isEmpty()) return text;
        return text.replace(password, "******");
    }

    /** 依据 SQLState / ErrorCode / message 给出常见故障的中文释义（纯辅助） */
    private static String hintForSqlError(String sqlState, int errorCode, String message) {
        if (errorCode == 1049) return "数据库不存在（1049）—— 检查数据源配置里的库名是否正确。";
        if (errorCode == 1146) return "表不存在（1146）—— 表名写错，或该库下确实没有这张表；可先用 SHOW TABLES 确认。";
        if (errorCode == 1045) return "账号或密码错误（1045）—— 检查数据源配置的用户名/密码。";
        if (errorCode == 1044) return "无权限访问该数据库（1044）。";
        if (errorCode == 1054) return "列名不存在（1054）—— 字段写错，可用 schema 模式先看表结构。";
        if (errorCode == 1064) return "SQL 语法错误（1064）—— 检查 SQL 写法。";
        if (errorCode == 0 && sqlState != null && sqlState.startsWith("08")) {
            return "连接失败/超时（SQLState " + sqlState + "）—— 检查主机、端口是否可达，数据库是否启动。";
        }
        if (sqlState != null && sqlState.equals("28000")) return "权限拒绝（28000）。";
        if (sqlState != null && sqlState.startsWith("42")) return "SQL 语法或对象引用错误（SQLState " + sqlState + "）。";
        if (message != null) {
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unknown database")) return "数据库不存在。";
            if (lower.contains("doesn't exist") || lower.contains("no such table")) return "表不存在。";
            if (lower.contains("access denied")) return "账号密码错误或权限不足。";
            if (lower.contains("timed out") || lower.contains("timeout")) return "连接超时。";
            if (lower.contains("connection refused")) return "连接被拒绝 —— 主机/端口不对或数据库未启动。";
            if (lower.contains("unknown column")) return "列名不存在。";
        }
        return null;
    }

    /** schema 模式：返回表结构（列名/类型/可空/键/注释）。 */
    private static String describeTable(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        StringBuilder sb = new StringBuilder();
        sb.append("表结构：").append(tableName).append("\n");
        sb.append(String.format("%-28s %-16s %-8s %-8s %s\n",
                "COLUMN", "TYPE", "NULL?", "KEY", "DEFAULT/COMMENT"));
        sb.append("------------------------------------------------------------\n");

        // 列基础信息
        try (ResultSet cols = meta.getColumns(null, null, tableName, "%")) {
            boolean any = false;
            while (cols.next()) {
                any = true;
                String colName = cols.getString("COLUMN_NAME");
                String colType = cols.getString("TYPE_NAME");
                String nullable = "YES".equals(cols.getString("IS_NULLABLE")) ? "YES" : "NO";
                String def = cols.getString("COLUMN_DEF");
                String remark = cols.getString("REMARKS");
                sb.append(String.format("%-28s %-16s %-8s %-8s %s\n",
                        colName, colType, nullable, "",
                        (def != null ? "default=" + def : "") + (remark != null && !remark.isEmpty() ? " " + remark : "")));
            }
            if (!any) {
                sb.append("（未找到该表的列信息，可能表名不存在或当前账号无权限。也可用 data 模式执行 `SELECT * FROM ")
                        .append(tableName).append(" LIMIT 0` 验证。）\n");
            }
        }

        // 主键信息
        try (ResultSet pk = meta.getPrimaryKeys(null, null, tableName)) {
            StringBuilder pks = new StringBuilder();
            while (pk.next()) {
                if (pks.length() > 0) pks.append(", ");
                pks.append(pk.getString("COLUMN_NAME"));
            }
            if (pks.length() > 0) {
                sb.append("\n主键：").append(pks).append("\n");
            }
        }
        return sb.toString();
    }

    /** 单列最大回显宽度（参考 yours_agent MAX_COLUMN_WIDTH，防长文本撑爆上下文） */
    private static final int MAX_COLUMN_WIDTH = 50;

    /**
     * data 模式：执行 SQL，硬限制最多 10 行。
     * @param actualSqlOut 长度≥1 的 String[]，用于把"实际执行的 SQL"带回给调用方（异常时如实反馈）。
     */
    private static String queryTableData(Connection conn, String rawSql, String[] actualSqlOut) throws SQLException {
        // 先剥离末尾已有的 LIMIT（避免重复导致语法错误），再强制拼 LIMIT 10
        String cleaned = stripTrailingLimit(rawSql);
        // SHOW / DESC 等语句不能拼 LIMIT，直接原样执行
        boolean needLimit = needsRowLimit(cleaned);
        String finalSql = needLimit ? (cleaned + " LIMIT " + QUERY_DATABASE_MAX_ROWS) : cleaned;
        if (actualSqlOut != null && actualSqlOut.length > 0) actualSqlOut[0] = finalSql;

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(finalSql)) {
            ResultSetMetaData rsMeta = rs.getMetaData();
            int colCount = rsMeta.getColumnCount();

            StringBuilder sb = new StringBuilder();
            // 表头
            StringBuilder header = new StringBuilder();
            for (int c = 1; c <= colCount; c++) {
                if (c > 1) header.append(" | ");
                header.append(rsMeta.getColumnLabel(c));
            }
            sb.append(header).append("\n");
            sb.append(repeat("-", header.length())).append("\n");

            int rows = 0;
            int maxRows = needLimit ? QUERY_DATABASE_MAX_ROWS : Integer.MAX_VALUE;
            while (rs.next() && rows < maxRows) {
                StringBuilder row = new StringBuilder();
                for (int c = 1; c <= colCount; c++) {
                    if (c > 1) row.append(" | ");
                    Object val = rs.getObject(c);
                    String cell = (val == null) ? "NULL" : val.toString();
                    row.append(truncateCell(cell));
                }
                sb.append(row).append("\n");
                rows++;
            }
            sb.append("\n共返回 ").append(rows).append(" 行");
            if (needLimit) {
                sb.append(rows >= QUERY_DATABASE_MAX_ROWS
                        ? "（已达表数据查询上限 " + QUERY_DATABASE_MAX_ROWS + " 行，更多数据请收窄条件或分页）。"
                        : "。");
            } else {
                sb.append("。");
            }
            sb.append(" 数据源为只读查询。");
            return sb.toString();
        }
    }

    /** 单元格限宽（超出加 …），防止长文本撑爆上下文 */
    private static String truncateCell(String cell) {
        if (cell == null) return "NULL";
        if (cell.length() <= MAX_COLUMN_WIDTH) return cell;
        return cell.substring(0, MAX_COLUMN_WIDTH) + "…";
    }

    /**
     * 判断是否需要追加 LIMIT：SHOW / DESC / DESCRIBE / EXPLAIN 等语句
     * 语法上不接受 LIMIT（或加了没意义），原样执行。
     */
    private static boolean needsRowLimit(String sql) {
        String s = sql.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        return !(s.startsWith("SHOW") || s.startsWith("DESC") || s.startsWith("DESCRIBE")
                || s.startsWith("EXPLAIN"));
    }

    /** 去掉 SQL 末尾的 LIMIT n [OFFSET m]，不区分大小写。 */
    private static String stripTrailingLimit(String sql) {
        String s = sql.trim();
        if (s.isEmpty()) return s;
        // 从尾部逐字符回退到最后一个分号前的 LIMIT
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)\\s+LIMIT\\s+\\d+\\s*(?:OFFSET\\s+\\d+)?\\s*;?\\s*$",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(s);
        if (m.find()) {
            return s.substring(0, m.start()).trim();
        }
        return s;
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String execAskUserQuestion(JsonObject params, ToolExecutionContext ctx) {
        if (ctx.askQuestionProvider == null) {
            return "（当前环境不支持交互提问，已跳过。请在后续对话中继续与用户确认。）";
        }

        List<UserQuestion> questions = new ArrayList<>();
        if (params.has("questions") && params.get("questions").isJsonArray()) {
            for (com.google.gson.JsonElement qEl : params.getAsJsonArray("questions")) {
                if (!qEl.isJsonObject()) continue;
                JsonObject qObj = qEl.getAsJsonObject();
                String questionText = getString(qObj, "question", "");
                if (questionText.isBlank()) continue;

                List<com.codepal.model.UserOption> options = new ArrayList<>();
                if (qObj.has("options") && qObj.get("options").isJsonArray()) {
                    for (com.google.gson.JsonElement oEl : qObj.getAsJsonArray("options")) {
                        if (!oEl.isJsonObject()) continue;
                        JsonObject oObj = oEl.getAsJsonObject();
                        String label = getString(oObj, "label", "");
                        if (label.isBlank()) continue;
                        String desc = getString(oObj, "description", "");
                        options.add(new com.codepal.model.UserOption(label, desc));
                    }
                }
                boolean multiSelect = getBool(qObj, "multiSelect", false);
                questions.add(new UserQuestion(questionText, options, multiSelect));
            }
        }

        if (questions.isEmpty()) {
            return "错误：未提供任何有效问题。";
        }

        try {
            java.util.concurrent.CompletableFuture<List<UserAnswer>> future =
                    ctx.askQuestionProvider.requestAskUserQuestion(questions);
            List<UserAnswer> answers = future.get(30, java.util.concurrent.TimeUnit.MINUTES);

            StringBuilder sb = new StringBuilder("用户回答如下：\n\n");
            for (UserAnswer a : answers) {
                String status = a.isAnswered() ? "已回答" : "未回答（用户跳过/超时）";
                sb.append("### ").append(a.getQuestion()).append("\n");
                sb.append(status).append("：").append(a.getAnswer()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (java.util.concurrent.TimeoutException e) {
            StringBuilder sb = new StringBuilder("（用户未在限定时间内回答，提问超时。）\n\n");
            for (UserQuestion q : questions) {
                sb.append("### ").append(q.getQuestion()).append("\n未回答（超时）\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "提问失败：" + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    private static VirtualFile findFile(String filePath, Project project) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (file != null && file.exists()) return file;
        if (project != null && project.getBaseDir() != null) {
            file = project.getBaseDir().findFileByRelativePath(filePath);
            if (file != null && file.exists()) return file;
        }
        for (Project p : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (p.getBaseDir() == null) continue;
            VirtualFile found = findByName(p.getBaseDir(), filePath);
            if (found != null) return found;
        }
        return null;
    }

    private static VirtualFile findByName(com.intellij.openapi.vfs.VirtualFile dir, String name) {
        if (dir.isDirectory()) {
            for (com.intellij.openapi.vfs.VirtualFile child : dir.getChildren()) {
                if (child.getName().equals(name)) return child;
                if (child.isDirectory()) {
                    VirtualFile found = findByName(child, name);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static String getString(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    private static int getInt(JsonObject obj, String key, int defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    private static boolean getBool(JsonObject obj, String key, boolean defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsBoolean(); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    private static int countOccurrences(String source, String search) {
        if (search.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = source.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replace("\n", "\\n").replace("\r", "\\r");
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 转义 JSON 字符串（用于构建 diff 数据）
     */
    private static String escapeJson(String text) {
        if (text == null) return "\"\"";
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 修复JSON字符串中未转义的控制字符（换行/回车/制表等）。
     * 遍历时跟踪是否在字符串值内部，将字面控制字符重新转义。
     * 用于解决SSE流反序列化后arguments中file_content等字段包含字面控制字符的问题。
     */
    public static String sanitizeJsonControlChars(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                switch (c) {
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
