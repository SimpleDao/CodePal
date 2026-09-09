package com.codepal.toolwindow;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.*;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import com.codepal.common.StringUtils;
import com.codepal.model.*;
import com.codepal.common.CollectUtils;
import com.codepal.common.Constant;
import com.codepal.db.DBChatHistoryRepository;
import com.codepal.db.DataSourceDao;

import com.codepal.memory.ConversationManager;
import com.codepal.settings.CPSettings;
import com.codepal.ui.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.components.JBLabel;
import com.codepal.mcp.McpService;
import com.codepal.session.ChatSessionManager;
import com.codepal.session.SessionContext;
import com.codepal.session.IterationGuard;
import com.codepal.tools.FileOperationService;
import com.codepal.tools.TodoListPanel;
import com.codepal.utils.FileReaderUtil;
import com.codepal.utils.MarkdownUtil;
import com.codepal.utils.ThreadHelper;
import com.codepal.util.ImageCompressor;

import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CP 聊天面板
 * - 头部操作通过 CPToolWindowFactory.setTitleActions 注入（AllIcons 原生图标）
 * - 消息气泡：左侧 AI（头像+名称+圆角卡片），右侧用户（圆角气泡+颜文字头像）
 * - Markdown 渲染：流式阶段 JTextArea，收到完整内容后切换为 AiMessageBrowser（JBCefBrowser / JEditorPane 回退）
 * - 右键菜单：所有气泡支持复制/全选
 * - 文件上下文条：监听编辑器文件切换 & 选区变化，点击可插入输入框
 */
public class ChatPanel extends JPanel implements ChatSessionManager.UiCallbacks {

    /** 消息卡片背景：用户和AI统一风格 */
    private static JBColor CARD_BG() {
        return JBColor.namedColor("Panel.background",
                !JBColor.isBright() ? new Color(0x3C3F41) : new Color(0xF0F1F3));
    }
    private static Color aiBubbleBg() {
        return JBColor.namedColor("TextField.background",
                !JBColor.isBright() ? new Color(0x323232) : new Color(0xE8E8E8));
    }

    /** 用户消息气泡背景：使用 IDEA 按钮强调色/选中色，与面板背景形成对比 */
    private static Color userBubbleBg() {
        return JBColor.namedColor("Button.default.startBackground",
                !JBColor.isBright() ? new Color(0x2B5B84) : new Color(0xD6E8FF));
    }

    /** 用户消息文字颜色：暗色主题浅色，亮色主题深色 */
    private static Color userBubbleFg() {
        return !JBColor.isBright() ? new Color(0xE8E8E8) : new Color(0x1A1A1A);
    }

    /** 消息卡片边框色（更柔和的灰色） */
    private static Color cardBorderColor() {
        return JBColor.namedColor("Component.borderColor",
                !JBColor.isBright() ? new Color(0x4E5157) : new Color(0xCFD1D6));
    }

    /** 思考过程区块背景 */
    private static Color reasoningBg() {
        return JBColor.namedColor("Plugins.tagBackground",
                !JBColor.isBright() ? new Color(0x252525) : new Color(0xF3E5F5));
    }

    /** 思考过程文字颜色 */
    private static Color reasoningFg() {
        return JBColor.namedColor("Plugins.tagForeground",
                !JBColor.isBright() ? new Color(0xA0A0A0) : new Color(0x666666));
    }

    /** 思考过程左边线装饰色（蓝色） */
    private static final JBColor REASONING_ACCENT() {
        return JBColor.namedColor("Component.focusedBorderColor",
                !JBColor.isBright() ? new Color(0x5E35B1) : new Color(0xCE93D8));
    }

    /** 思考过程边框颜色 */
    private static Color reasoningBd() {
        return JBColor.namedColor("Component.focusedBorderColor",
                !JBColor.isBright() ? new Color(0x3C3F41) : new Color(0xD0D0D0));
    }

    /** 分隔线颜色 */
    private static Color dividerColor() {
        return JBColor.namedColor("Separator.separatorColor",
                !JBColor.isBright() ? new Color(0x4E5157) : new Color(0xC9CCD6));
    }

    /** 输入框边框（固定，比内部略亮，形成微亮边缘确保任何深色主题可见） */
    private static Color inputBorderNormal() {
        return new Color(0x4C5055);
    }

    /** 输入框背景色（固定深灰，比外层面板略深，确保任何主题下都有视觉差） */
    private static Color inputFieldBg() {
        return new Color(0x3A3D42);
    }

    /** 发送按钮激活态背景色（紫罗兰渐变主色，参考现代 AI 聊天 UI） */
    private static Color sendBtnActiveColor() {
        // 不依赖 JBColor.namedColor（IDEA 26 命名色可能解析异常），直接用固定值确保可见
        return !JBColor.isBright() ? new Color(0x7C3AED) : new Color(0x6366F1);
    }

    /** 发送按钮描边色（默认态 ghost 圈） */
    private static Color sendBtnStrokeColor() {
        return JBColor.namedColor("Button.borderColor",
                new JBColor(new Color(0x8C8C8C), new Color(0x6B6B6B)));
    }

    /** 按比例调整颜色明暗（factor>1 变亮，<1 变暗） */
    private static Color shade(Color c, float factor) {
        int r = Math.max(0, Math.min(255, (int) (c.getRed() * factor)));
        int g = Math.max(0, Math.min(255, (int) (c.getGreen() * factor)));
        int b = Math.max(0, Math.min(255, (int) (c.getBlue() * factor)));
        return new Color(r, g, b, c.getAlpha());
    }

    // ── 极简几何图标（自定义绘制，与 ghost 描边风格统一）──

    /** 取命令的第一个单词作为信任匹配的 key */
    @Deprecated
    private static String getCommandTrustKey(String command) {
        return com.codepal.tools.ToolConfirmManager.getCommandTrustKey(command);
    }

    /**
     * 计算 JTextArea 的实际内容高度（考虑自动换行）
     */
    private static int calculateTextAreaHeight(JTextArea textArea) {
        int lineCount = 0;
        try {
            javax.swing.text.Document doc = textArea.getDocument();
            javax.swing.text.Element root = doc.getDefaultRootElement();
            int physicalLines = root.getElementCount();
            for (int i = 0; i < physicalLines; i++) {
                javax.swing.text.Element lineElem = root.getElement(i);
                int start = lineElem.getStartOffset();
                int end = lineElem.getEndOffset();
                String lineText = doc.getText(start, end - start);
                java.awt.FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
                int lineWidth = fm.stringWidth(lineText);
                int availableWidth = textArea.getWidth() - textArea.getInsets().left - textArea.getInsets().right;
                if (availableWidth > 0 && lineWidth > 0) {
                    int wrappedLines = (int) Math.ceil((double) lineWidth / availableWidth);
                    lineCount += Math.max(1, wrappedLines);
                } else {
                    lineCount += 1;
                }
            }
        } catch (Exception e) {
            lineCount = textArea.getLineCount();
        }
        if (lineCount < 1) lineCount = 1;
        int lineHeight = textArea.getFontMetrics(textArea.getFont()).getHeight();
        int insetsHeight = textArea.getInsets().top + textArea.getInsets().bottom;
        return lineCount * lineHeight + insetsHeight;
    }

    /** 状态/提示文字颜色 */
    private static Color mutedFg()
    {
        return JBColor.namedColor("Label.infoForeground", JBColor.GRAY);
    }

    /** 成功状态色（任务完成对勾） */
    private static final JBColor SUCCESS_FG = new JBColor(
            new Color(0x10B981), new Color(0x34D399));

    /** 上下文条强调色 */
    private static Color ctxAccent() {
        return JBColor.namedColor("Link.activeForeground", new Color(0x4B8EF0));
    }

    private static final Gson GSON = new Gson();

    private static final String USER_KAOMOJI_PICK;
    static {
        USER_KAOMOJI_PICK = "\uD83C\uDF93";
    }

    private final Project project;
    private com.codepal.memory.ConversationManager conversationManager;
    private final ChatSessionManager chatSessionManager;

    /** 供 TabManager 等外部类获取当前会话 ID（多标签去重用） */
    public ChatSessionManager getChatSessionManager() {
        return chatSessionManager;
    }
    private final com.codepal.session.IterationGuard iterationGuard;

    // Agent 后端管理器（从 ChatPanel 拆分出来）
    private com.codepal.agent.AgentBackendManager agentBackendManager;

    /** 单实例 ChatWebView：一个会话对应一个 JBCefBrowser，所有消息以 DOM 形式管理 */
    private ChatWebView chatWebView;
    /** 回到底部浮层按钮（Swing POPUP_LAYER，浮于底部面板之上，避免被遮挡） */
    private JButton scrollBottomBtn;
    /** 是否恢复上次会话（首个标签=true，后续标签=false） */
    private boolean restoreLastSession = true;

// 多标签隔离已改为 TabManager + 独立 ChatPanel 实例，不再需要 SessionContext。
    // sessionContexts / activeCtx 字段保留但不再使用，避免大量改动。
    @SuppressWarnings("unused")
    private final java.util.Map<String, SessionContext> sessionContexts = new java.util.LinkedHashMap<>();
    @SuppressWarnings("unused")
    private SessionContext activeCtx = null;
    private final JTextArea inputField;
    /** 按会话隔离的输入框草稿：sessionId → 输入文本，切换会话时保存/恢复 */
    private final java.util.Map<String, String> inputDraftBySession = new java.util.HashMap<>();
    /** 当前已挂载草稿的会话（用于切换时保存上一个会话的内容） */
    private String activeInputSessionId = null;
    private final ModelComboBox modelCombo;
    private final ComboBox<String> agentCombo;
    private final ComboBox<String> modeCombo;
    /** 技能入口：点击弹出勾选面板（多选），非下拉框（JComboBox 语义=点行即关，与多选冲突） */
    private final JButton skillButton;
    /** 技能弹层内的列表（复用 SkillComboRenderer 渲染勾选框），多选手动 toggle，弹层不自动关闭 */
    private JList<SkillListItem> skillList;
    /** 当前技能弹层（JPopupMenu，与 modelCombo 的 BasicComboPopup 同源渲染：透明+自绘圆角阴影，避免重复打开） */
    private javax.swing.JPopupMenu skillPopup;
    /** 已勾选启用的 skill 名集合（与 CPSettings.enabledSkills 同步，供渲染器画勾选框） */
    private final java.util.Set<String> enabledSkillNames = new java.util.HashSet<>();
    /** skillList 渲染器实例（需持有以动态注入勾选集合） */
    private SkillComboRenderer skillComboRenderer;

    // ─── @ 提示弹窗（输入框内输入 @ 弹出数据源面板）──
    /** @ 弹窗列表（数据库名），点选后把名称插入到输入框 @ 位置 */
    private JList<MentionListItem> mentionList;
    /**
     * 当前 @ 弹窗（JBPopup —— 与 showAttachPopup 完全同配置）。
     * ★ 必须用 JBPopup 而非 JPopupMenu：JCEF 是重量级组件，JPopupMenu 走轻量级 popup
     *   会被 JCEF 完全遮住（实测：改 JPopupMenu 后 @ 弹窗"消失"）。JBPopup 自带重型 Window，天然置顶。
     * ★ 双层问题的规避：builder 配置与 showAttachPopup 逐字对齐（见 showMentionPopup 内注释）。
     */
    private com.intellij.openapi.ui.popup.JBPopup mentionPopup;
    /** 触发 @ 弹窗时，@ 符号在输入框文档中的偏移；用于点选后定位插入/替换 */
    private int mentionStartPos = -1;
    /** @ 弹窗内是否正显示 */
    private boolean mentionActive = false;

    // ─── 数据源下拉（按钮 + 圆角阴影弹层，复用技能面板范式）──
    /** 数据源入口：点击弹出数据源列表，支持新增/编辑/删除 */
    private com.codepal.ui.PopupComboButton<com.codepal.model.DatabaseComboItem> databaseCombo;
    /** 数据源弹层行渲染器（供编辑笔命中判定与 hover 重绘） */
    private com.codepal.ui.DatabaseListRenderer databaseListRenderer;
    /** 当前选中的数据源 id（null = 未选择，按钮显示占位文案「数据源」） */
    private String selectedDataSourceId;
    private boolean craftMode = true; // 默认 Craft 模式（可修改文件）
    private final JLabel statusLabel;
    /** 底部 token 用量统计按钮（图标+悬浮提示） */
    private JButton tokenStatsLabel;

    // 发送按钮
    private JButton sendBtn;

    // ── 图片附件（视觉模型）：发送前暂存，发送时挂到 user 消息 ──
    private final List<ChatMessage.Attachment> pendingAttachments = new ArrayList<>();
    private JBPanel<?> attachStrip;
    private JBPanel<?> attachChips;
    /** 图标集：ghost=描边态(默认)，white=激活态白底 */
    private Icon sendIconGhost;   // 默认态：细线纸飞机
    private Icon stopIconGhost;   // 默认态：细线停止
    private Icon sendIconWhite;   // 激活态：白色纸飞机
    private Icon stopIconWhite;   // 激活态：白色停止

    // 上下文压缩
    private com.codepal.compression.ContextCircleProgress contextCircle;
    private com.codepal.compression.CompressionManager compressionManager;
    private boolean compressionHintShown = false; // 阈值提示是否已显示过
    private boolean taskWasInterrupted = false;    // 用户点击停止中断了任务，下次发送时插入上下文切换
    private String pendingLoadingMsgKey = "ai_loading"; // 下一轮 sendToApi 使用的轮播提示词分类

    // ── 真实流式写入可视化状态（onToolArgsDelta 边收边铺，onToolCalls 收尾）──
    private boolean writeStreamCardActive = false;   // 当前是否已有正在流式的写入卡片
    private int streamWriteCardIndex = -1;           // 正在流式的工具调用 index
    // ★ 按工具 index 隔离的参数缓冲区，避免多个工具的参数片段互相污染
    //   （如 todo 的 "content" + write_file 的 "file_path" 混在同一 buffer 导致误判）
    private final java.util.Map<Integer, StringBuilder> streamWriteRawArgsByIndex = new java.util.HashMap<>();
    private int streamWriteShownLen = 0;             // 已铺进卡片的 file_content 字符数
    private String streamWriteTitle = "";            // 当前写入卡片标题（用于头部 +/− 统计定位）
    private int streamWriteRemovedLines = 0;         // 被覆盖文件的原有行数（create_new_file 为 0）
    private boolean streamWriteIsEdit = false;       // 当前流式卡片是否为 edit_file（搜索/替换模式）
    private String streamWriteOriginal = "";         // edit_file 被编辑文件的原始内容（用于 applyEdits 预览）

    // 工具确认管理器（从 ChatPanel 拆分出来）
    private com.codepal.tools.ToolConfirmManager toolConfirmManager;

    // 工具注册中心 + 策略引擎 + 编排器（阶段二新增）
    private com.codepal.tools.ToolRegistry toolRegistry;
    private com.codepal.tools.PolicyEngine policyEngine;
    private com.codepal.tools.ToolOrchestrator toolOrchestrator;

    // Todo 任务管理器（从 ChatPanel 抽离）
    private com.codepal.tools.TodoManager todoManager;

    // 任务 + Diff 变更 Tab 面板（输入框上方）
    private com.codepal.tools.TaskDiffTabPanel taskDiffTabPanel;

    // 流式渲染控制器（从 ChatPanel 拆分出来）
    private com.codepal.ui.StreamRenderController streamRenderController;
    /** ★ 当前激活标签的流式渲染控制器，等价于 streamRenderController，保留旧字段名减少改动量 */
    // streamRenderController 和 conversationManager 现在作为「当前激活标签」的快捷引用

    // 文件上下文条组件
    private final JBPanel<?> ctxBar;
    private final JBLabel ctxFileLabel;
    private final JBLabel  ctxLineLabel;
    private final JButton ctxInsertBtn;
    private String ctxCurrentFile = null;
    private int    ctxStartLine   = -1;
    private int    ctxEndLine     = -1;

    private boolean isCompressing      = false;

    // 当前会话累计 token 用量（用于费用统计，每次请求都会把完整对话作为 prompt 重发，故应累加）
    private int sessionPromptTokens      = 0;
    private int sessionCompletionTokens  = 0;
    private int sessionCacheHitTokens    = 0;
    private int sessionCacheMissTokens   = 0;

    // 最新一次请求的上下文快照（用于圆环的"当前上下文窗口用量"）。
    // 关键：每次请求都把完整对话历史作为 prompt 发往后端，usage.prompt_tokens 即"整个当前上下文"的大小，
    // 绝不可累加（否则同一份上下文会被数倍重复计数，几轮后圆环虚假爆满）。这里只记录最近一次，不累加。
    private long lastPromptTokens     = 0;
    private long lastCompletionTokens = 0;
    // 最新一次请求的缓存命中/未命中快照（token 面板"本次回答"口径，不累加）
    private long lastCacheHitTokens   = 0;
    private long lastCacheMissTokens  = 0;

    // 「本轮发送给模型的完整上下文」快照：key=用户消息 DB id，value=JSON（system/history/userMessage/messageCount）。
    // 仅内存、不落库；重载会话后自然清空（悬浮查看时提示"未记录"，与桌面端一致）。
    // 上限保护，避免极长会话常驻内存。
    private final java.util.Map<String, String> outgoingPayloads =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            });


    // 面板切换（CardLayout）：聊天视图 + 历史视图
    private static final String CHAT_CARD = "chat";
    private static final String HISTORY_CARD = "history";
    private CardLayout cardLayout;
    private JBPanel<?> cardPanel;

    /** CENTER 区域：使用 JLayeredPane 分层布局，JCEF 始终占满全区域（不 resize，避免闪烁），
     *  diff 面板浮动叠加在 JCEF 上方底部（PALETTE 层） */
    private JLayeredPane centerLayer;
    private JComponent inputPanel;

    private boolean isDark;

    // 历史会话面板组件
    private JBList<HistoryItem> historyList;
    private DefaultListModel<HistoryItem> historyListModel;
    private JBPanel<?> historyPanel;

    // 顶部 Tab 条（多会话快速切换，参考 CodeBuddy）
    private JBPanel<?> tabBarPanel;
    private final LinkedHashMap<String, JBPanel<?>> tabPanels = new LinkedHashMap<>();
    /** 已打开的 Tab：sessionId → 标题；保持打开顺序 */
    private final LinkedHashMap<String, String> openTabs = new LinkedHashMap<>();
    private String activeTabSessionId;
    /** 流进行中用户点了其它会话标签时的延迟切换目标；本轮流结束(onComplete/onError)后再执行，避免打断正在进行的回复 */
    private String pendingSessionSwitchId = null;

    /**
     * 多标签隔离回调接口：ChatPanel 内部会话变化时通知 TabManager。
     */
    public interface TabCallbacks {
        /** 会话标题变化时调用（如首条消息后自动生成标题） */
        void onSessionTitleChanged(String title);
        /** 从历史面板打开会话时调用，请求 TabManager 在新标签中打开 */
        void onOpenHistorySession(String sessionId, String title);
    }

    /** TabManager 注册的回调（可为 null，单标签模式下不注册） */
    private TabCallbacks tabCallbacks;

    /**
     * 设置 TabManager 回调。
     */
    public void setTabCallbacks(TabCallbacks callbacks) {
        this.tabCallbacks = callbacks;
    }

    public ChatPanel(Project project) {
        this(project, true);
    }

    /**
     * @param restoreLastSession true=恢复上次会话（首个标签），false=创建新空会话（后续标签）
     */
    public ChatPanel(Project project, boolean restoreLastSession) {
        this.project = project;
        this.restoreLastSession = restoreLastSession;
        this.compressionManager = new com.codepal.compression.CompressionManager(project);

        // 初始化 Agent 后端管理器（从 ChatPanel 拆分出来）
        this.agentBackendManager = new com.codepal.agent.AgentBackendManager(project);

        setLayout(new BorderLayout());
        setBackground(UIUtil.getPanelBackground());

        // ── CardLayout 容器先建 ──
        isDark = MarkdownUtil.isDarkTheme();

        // 面板切换（CardLayout）：聊天视图 + 历史视图
        cardLayout = new CardLayout();
        cardPanel = new JBPanel<>(cardLayout);
        cardPanel.setOpaque(false);

        // ★ 初始 ConversationManager / ChatSessionManager（全局唯一，管理当前会话 ID）
        this.conversationManager = new com.codepal.memory.ConversationManager();
        this.chatSessionManager = new ChatSessionManager(project, conversationManager);
        this.iterationGuard = new com.codepal.session.IterationGuard(conversationManager, chatSessionManager);

        conversationManager.initSystem(CPSettings.getInstance().getSystemPrompt(project, craftMode));

        // ★ 创建初始 ChatWebView
        chatWebView = new ChatWebView(isDark);

        // 注册工具确认管理器（从 ChatPanel 拆分出来）
        toolConfirmManager = new com.codepal.tools.ToolConfirmManager(project, chatWebView);
        toolConfirmManager.registerCallbacks();

        // 初始化工具注册中心 + 策略引擎 + 编排器（阶段二新增）
        toolRegistry = new com.codepal.tools.ToolRegistry(project);
        policyEngine = new com.codepal.tools.PolicyEngine(project, toolRegistry);
        toolOrchestrator = new com.codepal.tools.ToolOrchestrator(project, toolRegistry, policyEngine);
        toolRegistry.initialize();
        toolOrchestrator.setConfirmProvider(toolConfirmManager);

        // 接线子智能体管理器（search_agent / view_image 统一由 SubAgent 调度）
        com.codepal.agent.subagent.SubAgentManager subAgentManager =
                new com.codepal.agent.subagent.SubAgentManager(project);
        subAgentManager.initialize();
        com.codepal.tools.ToolExecutor.setSubAgentManager(subAgentManager);
        toolRegistry.setSubAgentManager(subAgentManager);

        // 初始化任务 + Diff 变更 Tab 面板（输入框上方）
        taskDiffTabPanel = new com.codepal.tools.TaskDiffTabPanel(
                project,
                this::handleFileAccepted,
                this::handleFileRejected,
                this::handleFileRemoved,
                this::handleAllChangesResolved,
                this::handlePanelClose,
                () -> {
                    refreshCenterLayout();
                }
        );

        // 初始化 TodoManager（从 ChatPanel 抽离）
        todoManager = new com.codepal.tools.TodoManager(new com.codepal.tools.TodoManager.UiCallbacks() {
            @Override
            public void renderTodoList(String todosJson) {
                List<com.codepal.tools.TodoListPanel.TodoItem> items = parseTodoItemsFromJson(todosJson);
                taskDiffTabPanel.setTodos(items);
            }
            @Override
            public void renderTodoListAndReEmit(String todosJson) {
                List<com.codepal.tools.TodoListPanel.TodoItem> items = parseTodoItemsFromJson(todosJson);
                taskDiffTabPanel.updateTodos(items);
                // 全部完成时才追加到消息末尾进入上下文（只追加一次）
                if (taskDiffTabPanel.isAllTodosCompleted() && !todoCompletionSummaryAppended) {
                    todoCompletionSummaryAppended = true;
                    appendTodoListToMessage(todosJson);
                }
            }
            @Override
            public void clearTodoList() {
                taskDiffTabPanel.clearTodos();
            }
        });

        // 初始化流式渲染控制器（从 ChatPanel 拆分出来）
        streamRenderController = new com.codepal.ui.StreamRenderController(chatWebView);
        streamRenderController.setTurnTimeoutCallback(this::handleTurnTimeout);

        // 注册历史消息懒加载回调（滚动到顶部触发）
        chatWebView.setHistoryLoadCallback(() -> loadMoreHistory());

        // 注册消息删除回调
        chatWebView.setDeleteCallback(qaRound -> deleteQaMessages(qaRound));

        // 注册文件打开回调（点击消息中的文件路径在 IDEA 编辑器中打开）
        chatWebView.setOpenFileCallback(this::openFileInEditor);

        // 注册「本轮发送给模型的完整上下文」懒加载：悬浮查看时按用户消息 id 取内存快照
        chatWebView.setPayloadLookup(id -> outgoingPayloads.get(id));

        // 注册 ToolExecutor 的确认提供者 —— 已由 ToolConfirmManager 处理

        // 初始化上下文条（先隐藏，有文件时显示）
        ctxFileLabel  = new JBLabel();
        ctxLineLabel  = new JBLabel();
        ctxInsertBtn  = new JButton("插入");
        ctxBar        = buildContextBar();
        ctxBar.setVisible(false);

        statusLabel = new JBLabel("就绪");
        inputField = new JTextArea(3, 0);
        agentCombo = new ComboBox<>(CPSettings.getInstance().getInstalledAgentNamesArray());
        // ── 模式选择：仅保留 Craft 模式（Plan 选项已移除，但底层代码保留）──
        modeCombo = new ComboBox<>(new String[]{"Craft"});
        modeCombo.setSelectedItem("Craft");

        // ── 下拉框图标 + 自定义渲染器（图标+文字，参考现代 AI 聊天 UI）──
        final Icon planIcon   = IconLoader.getIcon("/icons/combo_plan.svg", ChatPanel.class);
        final Icon craftIcon  = IconLoader.getIcon("/icons/combo_craft.svg", ChatPanel.class);
        final Icon modelIcon  = IconLoader.getIcon("/icons/combo_model.svg", ChatPanel.class);
        final Icon addIcon    = IconLoader.getIcon("/icons/add_model.svg", ChatPanel.class);
        final Icon editIcon   = IconLoader.getIcon("/icons/edit_model.svg", ChatPanel.class); // SVG 已固定 fill=#FFFFFF（暗色主题纯白）
        final Icon checkIcon  = IconLoader.getIcon("/icons/check_vision.svg", ChatPanel.class); // 绿色对号，与 editIcon 同源机制

        // modeCombo 渲染器：Plan→眼睛图标, Craft→工具箱图标（和ModelComboRenderer结构一致）
        modeCombo.setRenderer(new ModeComboRenderer(planIcon, craftIcon));

        // 模型下拉框：底部"＋ 配置补全模型"和"＋ 配置自定义模型"选项
        // 使用自定义ModelComboBox，在setSelectedItem层面拦截添加项，防止其被选中
        final String ADD_COMPLETION_ITEM = " 配置补全模型";
        final String ADD_CHAT_ITEM = " 配置自定义模型";
        modelCombo = new ModelComboBox(buildModelComboItems(ADD_COMPLETION_ITEM, ADD_CHAT_ITEM, visionComboLabel()),
                item -> {
                    if (item.isAddCompletion()) {
                        addCompletionModel();
                    } else if (item.isConfigVision()) {
                        openVisionConfig();
                    } else {
                        addModel();
                    }
                });
        modelCombo.setRenderer(new ModelComboRenderer(modelIcon, addIcon, editIcon, checkIcon));

        // ── 统一应用 CP 风格（自定义 UI → 圆角弹窗）──
        ComboStyle.apply(modeCombo);
        ComboStyle.apply(modelCombo);

        // 编辑/删除不再用下拉内的悬浮按钮（贴合 CodeBuddy 原生扁平风）：
        // 双击某模型行 = 编辑；右键某模型行 = 编辑/删除菜单。视觉上零按钮。
        attachListHoverTracking(modeCombo);
        attachModelListActions(modelCombo);

        // ── 数据源入口：复用技能面板范式（按钮 + 圆角阴影弹层），支持新增/编辑/删除 ──
        // 不用 JComboBox：其 BasicComboPopup 的 fixedCellHeight 全局锁死，
        // 占位行无法归零，弹层顶部必然残留一整行空白。
        databaseListRenderer = new com.codepal.ui.DatabaseListRenderer();
        databaseCombo = new com.codepal.ui.PopupComboButton<>(
                ComboStyle.databaseIcon(),
                "数据源",
                databaseListRenderer,
                this::loadDatabaseItems,                       // 数据提供者（不含占位项）
                it -> it.name,                                 // 文本（用于宽度自适应）
                (item, index, p) -> onDataSourceItemClicked(item, index, p),
                null,                                          // 行内 hover 重绘由组件内部的 hover 追踪负责
                10,                                            // 最大可见行
                JBUI.scale(200),                               // 最小宽度
                ComboStyle.itemPaddingX() + 16 + 12,           // 右侧编辑笔预留
                "数据源：点击选择要查询的数据库，或配置新的数据源");
        databaseCombo.refreshData();

        // ── 技能入口：点击弹出勾选面板（多选），不用 JComboBox ──
        // JComboBox 语义是"点一行=选中=关弹层"，与"勾选框不关、连勾多个"冲突；
        // 故改用 JButton + JBPopup 承载 JList<SkillListItem>（复用 SkillComboRenderer 画勾选框），
        // 点击行仅 toggle 该 skill 的启用态、弹层不自动关闭，点外部才关。
        final Icon skillIcon = IconLoader.getIcon("/icons/skill.svg", ChatPanel.class);
        final Icon deleteIcon = IconLoader.getIcon("/icons/delete_model.svg", ChatPanel.class);
        // ★ 必须先 new JList 再构造渲染器：SkillComboRenderer.ownerList 在构造时即传入，
        //   原代码顺序相反导致 ownerList 为 null → hitDeleteIcon/updateDeleteHover 永远失效（删除点击无效、无 hover 框）。
        skillList = new JList<>();
        skillComboRenderer = new SkillComboRenderer(skillList, skillIcon, addIcon, deleteIcon);
        skillList.setCellRenderer(skillComboRenderer);
        skillList.setFixedCellHeight(ComboStyle.rowHeight());
        skillList.setVisibleRowCount(12);
        skillList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        skillList.setOpaque(false);
        skillList.setBackground(new Color(0, 0, 0, 0));
        // 挂 hover 追踪，让 SkillComboRenderer.calcHover 拿到鼠标点（驱动选中/hover 灰色背景）
        attachListHoverTracking(skillList);
        // 点击行：真实 skill = 切换勾选（不关弹层）；Import 项 = 打开文件选择器
        // ★ addMouseListener 只注册 MouseListener，mouseMoved(MouseMotionListener 方法) 不会被分发！
        //   故同一 MouseAdapter 须再 addMouseMotionListener，否则 updateDeleteHover 永不触发→删除图标 hover 框不显示。
        MouseAdapter skillClickMove = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int idx = skillList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                SkillListItem it = skillList.getModel().getElementAt(idx);
                if (it.kind == SkillListItem.KIND_SKILL) {
                    // 先判删除图标命中：命中则删除，不走 toggle
                    if (skillComboRenderer != null && skillComboRenderer.hitDeleteIcon(e.getPoint())) {
                        deleteSkill(it.name);
                        return;
                    }
                    toggleSkillEnabled(it.name);
                } else if (it.kind == SkillListItem.KIND_IMPORT) {
                    SwingUtilities.invokeLater(() -> importSkillFromChooser());
                }
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (skillComboRenderer != null) skillComboRenderer.updateDeleteHover(e.getPoint());
            }
        };
        skillList.addMouseListener(skillClickMove);
        skillList.addMouseMotionListener(skillClickMove);

        skillButton = new JButton("Skills", skillIcon) {
            private boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                if (hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                        // 关键：卡片铺满整个按钮宽高（不再用 Math.min(w,h) 当宽，
                        // 旧实现 w=80 h=36 时 cardSize=32，水平居中画 [24,56]，右半边无 hover 背景）
                        int w = getWidth(), h = getHeight();
                        int r = 8, inset = 2;
                        // 与模型下拉框 hover 背景保持一致：纯黑半透明（亮18/暗32）
                        g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                        g2.fillRoundRect(inset, inset, w - inset * 2, h - inset * 2, r, r);
                    } finally { g2.dispose(); }
                }
                super.paintComponent(g);
            }
        };
        skillButton.setFont(JBUI.Fonts.label(13));
        skillButton.setToolTipText("已导入的 skill：点击勾选/取消启用（勾选后模型可见）");
        skillButton.setFocusable(false);
        skillButton.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        skillButton.setContentAreaFilled(false);
        skillButton.setOpaque(false);
        skillButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        skillButton.addActionListener(e -> showSkillPopup());
        // 初始化 enabled 集合与列表数据
        refreshSkillCombo();

        refreshModelCombo();

        modelCombo.addActionListener(e -> {
            Object sel = modelCombo.getSelectedItem();
            if (!(sel instanceof ModelComboItem)) return;
            ModelComboItem item = (ModelComboItem) sel;
            if (item.isAddItem()) return;
            CPSettings settings = CPSettings.getInstance();
            int idx = settings.findChatModelIndexById(item.id);
            if (idx >= 0) {
                settings.setCurrentChatModelIndex(idx);
                // 切换模型后：用新模型的上下文上限刷新圆环分母，
                // 并刷新请求后端（OpenAI / Anthropic 链路随 apiFormat 切换，否则会一直沿用旧后端）
                if (contextCircle != null) {
                    contextCircle.setMaxContextTokens(settings.getChatMaxTokens());
                }
                if (agentBackendManager != null) agentBackendManager.refreshCurrentBackend();
            }
            if (!chatSessionManager.isLoadingSessionConfig()) saveSessionConfig();
            // 切换模型后，收起态宽度需随新模型名重新自适应（触发父容器重新布局）
            modelCombo.revalidate();
        });
        modelCombo.setEnabled(true);

        // ── 输入框放 SOUTH ──
        inputPanel = buildInputPanel();
        add(inputPanel, BorderLayout.SOUTH);

        // ★ 初始 WebView 直接加入 cardPanel 作为 CHAT_CARD
        cardPanel.add(chatWebView.getComponent(), CHAT_CARD);

        // 构建历史会话面板
        historyPanel = buildHistoryPanel();
        cardPanel.add(historyPanel, HISTORY_CARD);

        // CENTER 区域用 JLayeredPane 分层：JCEF 始终占满全区域（cardPanel 恒定全尺寸，不 resize → 无黑闪）
        // 任务/Diff 面板作为浮动叠加层（MODAL 层，置顶）叠加在 JCEF 上方，出现/消失只影响自身叠加层。
        centerLayer = new JLayeredPane() {
            @Override
            public void doLayout() {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                // cardPanel (JCEF) 始终占满全区域
                cardPanel.setBounds(0, 0, w, h);
                // 顶部浮动面板（Todo 任务计划）
                Component[] topOverlays = getComponentsInLayer(JLayeredPane.MODAL_LAYER.intValue());
                int topOffset = 0;
                for (Component c : topOverlays) {
                    if (c.isVisible()) {
                        // 强制验证子组件，确保 preferredSize 是最新的
                        c.invalidate();
                        c.validate();
                        int ch = c.getPreferredSize().height;
                        // 最小高度保护 + 最大高度限制（不超过聊天区30%，避免遮挡内容）
                        int maxTopH = Math.max(h / 3, JBUI.scale(120));
                        if (ch <= 0) ch = JBUI.scale(40);
                        if (ch > maxTopH) ch = maxTopH;
                        c.setBounds(0, topOffset, w, ch);
                        c.validate();
                        topOffset += ch;
                    } else {
                        c.setBounds(0, -1000, w, 0);
                    }
                }
                // 底部浮动面板（Diff 文件变更）
                Component[] overlays = getComponentsInLayer(JLayeredPane.PALETTE_LAYER.intValue());
                int bottomOffset = h;
                for (Component c : overlays) {
                    if (c.isVisible()) {
                        System.out.println("[DiffDebug] centerLayer.doLayout: laying out PALETTE component, visible=true, " +
                            "w=" + w + " h=" + h + " preferredSize=" + c.getPreferredSize() + " size=" + c.getSize());
                        // 强制验证子组件，确保 preferredSize 是最新的
                        c.invalidate();
                        c.validate();
                        int ch = c.getPreferredSize().height;
                        System.out.println("[DiffDebug] centerLayer.doLayout: after validate, ch=" + ch);
                        // 高度保护：最小高度40px，最大高度不超过聊天区40%（避免遮挡聊天内容）
                        int maxBottomH = Math.max(h * 2 / 5, JBUI.scale(160));
                        if (ch <= 0) ch = JBUI.scale(60);
                        if (ch > maxBottomH) ch = maxBottomH;
                        System.out.println("[DiffDebug] centerLayer.doLayout: clamped ch=" + ch + " maxBottomH=" + maxBottomH);
                        bottomOffset -= ch;
                        // 确保不会超出顶部
                        if (bottomOffset < 0) bottomOffset = 0;
                        c.setBounds(0, bottomOffset, w, ch);
                        c.validate();
                        System.out.println("[DiffDebug] centerLayer.doLayout: setBounds to (0," + bottomOffset + "," + w + "," + ch + ")");
                    } else {
                        c.setBounds(0, h + 1000, w, 0);
                    }
                }
                // 同步浮动面板遮挡高度到 WebView，JS 会调整 #chat 的 padding 避免消息被遮挡
                int topOverlayH = topOffset;
                int bottomOverlayH = h - bottomOffset;
                if (chatWebView != null) {
                    chatWebView.setOverlayInsets(topOverlayH, bottomOverlayH);
                }
                // 回到底部浮层按钮：固定右下角（32×32），浮于底部面板（PALETTE 层）之上，不占位、不挡交互
                if (scrollBottomBtn != null) {
                    int bw = JBUI.scale(30), bh = JBUI.scale(30);
                    int rightPad = JBUI.scale(14), bottomPad = JBUI.scale(14);
                    // 紧贴底部面板顶部上方；面板未出现时（bottomOffset==h）落在聊天区右下角
                    int by = bottomOffset - bh - bottomPad;
                    if (by < bottomPad) by = h - bh - bottomPad;
                    scrollBottomBtn.setBounds(w - bw - rightPad, by, bw, bh);
                }
            }
        };
        centerLayer.setOpaque(true);
        centerLayer.setBackground(UIUtil.getPanelBackground());
        centerLayer.add(cardPanel, JLayeredPane.DEFAULT_LAYER);
        // 任务 + Diff Tab 面板：浮动叠加在聊天区域底部（PALETTE 层），紧贴输入框上方
        centerLayer.add(taskDiffTabPanel.getComponent(), JLayeredPane.PALETTE_LAYER);

        // 回到底部浮层按钮：置于 POPUP_LAYER（高于 PALETTE 层），浮于底部面板之上，不被遮挡
        scrollBottomBtn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                int w = getWidth(), h = getHeight();
                Graphics2D g2 = (Graphics2D) g.create();
                com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                int r = 16, inset = 0;
                boolean hover = getModel().isRollover();
                // 与收起态 hover 同款：淡半透明黑圆角实心
                g2.setColor(hover
                        ? new JBColor(new Color(0, 0, 0, 28), new Color(0, 0, 0, 45))
                        : new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                g2.fillRoundRect(inset, inset, w - inset * 2, h - inset * 2, r, r);

                // 手动缩放并居中绘制 SVG 箭头，避免 1024×1024 SVG 在小按钮上只显示一角
                Icon raw = IconLoader.getIcon("/icons/scroll_bottom.svg", ChatPanel.class);
                if (raw != null) {
                    int pad = JBUI.scale(6);
                    int avail = Math.min(w, h) - pad * 2;
                    if (avail > 0) {
                        int iw = raw.getIconWidth();
                        int ih = raw.getIconHeight();
                        float scale = Math.min((float) avail / iw, (float) avail / ih);
                        int drawW = Math.round(iw * scale);
                        int drawH = Math.round(ih * scale);
                        int x = (w - drawW) / 2;
                        int y = (h - drawH) / 2;
                        g2.translate(x, y);
                        g2.scale(scale, scale);
                        raw.paintIcon(this, g2, 0, 0);
                    }
                }
                g2.dispose();
            }
        };
        scrollBottomBtn.setToolTipText("回到底部");
        scrollBottomBtn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scrollBottomBtn.setContentAreaFilled(false);
        scrollBottomBtn.setOpaque(false);
        scrollBottomBtn.setFocusable(false);
        scrollBottomBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        scrollBottomBtn.setVisible(false);
        scrollBottomBtn.addActionListener(e -> {
            if (chatWebView != null) chatWebView.scrollToBottom();
        });
        centerLayer.add(scrollBottomBtn, JLayeredPane.POPUP_LAYER);

        // JS 滚动检测 → 控制 Swing 浮层按钮可见性
        chatWebView.setScrollBottomCallback(show ->
                SwingUtilities.invokeLater(() -> {
                    if (scrollBottomBtn != null) scrollBottomBtn.setVisible(show);
                }));

        add(centerLayer, BorderLayout.CENTER);

        // 顶部 Tab 条不再需要（多标签由 TabManager 管理）
        // tabBarPanel = buildSessionTabsBar();
        // add(tabBarPanel, BorderLayout.NORTH);

        // 注册编辑器监听器
        if (project != null) {
            registerFileListener();
            registerSelectionListener();
        }

        // 设置会话管理器的 UI 回调
        chatSessionManager.setUiCallbacks(this);

        // 创建默认会话（首个标签恢复上次会话，后续标签创建新空会话）
        if (restoreLastSession) {
            openSession();
        } else {
            createNewSession();
        }
    }

    private void openSession() {
        chatSessionManager.openSession();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口（供 CPToolWindowFactory 的 titleAction 调用）
    // ─────────────────────────────────────────────────────────────────────────

    public void clearChat() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (chatWebView != null) chatWebView.clearMessages();
            todoManager.clear();
            taskDiffTabPanel.clearTodos();
            taskDiffTabPanel.resetChanges();
            todoCompletionSummaryAppended = false;
            if (streamRenderController != null) streamRenderController.resetAll();
            if (conversationManager != null) conversationManager.reset(CPSettings.getInstance().getSystemPrompt(project, craftMode));
            // 重置 token 统计
            sessionPromptTokens     = 0;
            sessionCompletionTokens = 0;
            sessionCacheHitTokens   = 0;
            sessionCacheMissTokens  = 0;
            lastPromptTokens     = 0;
            lastCompletionTokens = 0;
            lastCacheHitTokens   = 0;
            lastCacheMissTokens  = 0;
            if (tokenStatsLabel != null) {
                tokenStatsLabel.setToolTipText("暂无统计数据");
                tokenStatsLabel.putClientProperty("detail_text", null);
            }
            if (contextCircle != null) contextCircle.setTokens(0);
            compressionHintShown = false;
            taskWasInterrupted = false;
            if (toolConfirmManager != null) toolConfirmManager.reset();
            addAiMessage("对话已清空。有什么我可以帮你的吗？");
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 文件上下文条：监听编辑器文件切换 & 选区
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 构建文件上下文条：显示当前文件名和选区行号，点击"插入"按钮将引用写入输入框
     */
    private JBPanel<?> buildContextBar() {
        Color panelBg = UIUtil.getPanelBackground();
        Color accent  = ctxAccent();

        JBPanel<?> bar = new JBPanel<>(new BorderLayout(6, 0));
        bar.setBackground(panelBg);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, dividerColor()),
                JBUI.Borders.empty(5, 12, 5, 8)
        ));

        // 左侧：文件图标 + 文件名
        JBPanel<?>  leftGroup = new JBPanel<> (new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftGroup.setOpaque(false);

        JBLabel iconLbl = new JBLabel(AllIcons.Actions.IntentionBulb);
        iconLbl.setForeground(accent);
        leftGroup.add(iconLbl);

        ctxFileLabel.setFont(JBUI.Fonts.label(11));
        ctxFileLabel.setForeground(accent);
        leftGroup.add(ctxFileLabel);

        ctxLineLabel.setFont(JBUI.Fonts.label(11));
        ctxLineLabel.setForeground(mutedFg());
        leftGroup.add(ctxLineLabel);

        bar.add(leftGroup, BorderLayout.CENTER);

        // 右侧：插入按钮
        ctxInsertBtn.setText("插入");
        ctxInsertBtn.setFont(JBUI.Fonts.label(11));
        ctxInsertBtn.setForeground(accent);
        ctxInsertBtn.setBackground(panelBg);
        ctxInsertBtn.setOpaque(true);
        ctxInsertBtn.setBorderPainted(true);
//        ctxInsertBtn.setBorder(BorderFactory.createCompoundBorder(
//                new LineBorder(accent, 1, true),
//                JBUI.Borders.empty(2, 8)
//        ));
        ctxInsertBtn.setBorder(JBUI.Borders.customLine(accent, 1));
        ctxInsertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ctxInsertBtn.setFocusable(false);
        ctxInsertBtn.addActionListener(e -> insertContextIntoInput());
        ctxInsertBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                ctxInsertBtn.setBackground(JBColor.namedColor("ActionButton.hoverBackground",
                        !JBColor.isBright() ? new Color(0x4B8EF0, true) : new Color(0xE3F2FD)));
            }
            @Override public void mouseExited(MouseEvent e) {
                ctxInsertBtn.setBackground(panelBg);
            }
        });
        bar.add(ctxInsertBtn, BorderLayout.EAST);

        return bar;
    }

    /** 将当前上下文（@文件名 或 @文件名 L行号-行号）插入输入框光标处 */
    private void insertContextIntoInput() {
        if (ctxCurrentFile == null) return;
        String ref;
        if (ctxStartLine > 0 && ctxEndLine >= ctxStartLine) {
            ref = "@" + ctxCurrentFile + " (L" + ctxStartLine + "-L" + ctxEndLine + ")";
        } else {
            ref = "@" + ctxCurrentFile;
        }
        // 在光标处插入，如果末尾没空格则补一个空格
        String cur = inputField.getText();
        if (!cur.isEmpty() && !cur.endsWith(" ") && !cur.endsWith("\n")) {
            ref = " " + ref;
        }
        inputField.insert(ref + " ", inputField.getCaretPosition());
        inputField.requestFocusInWindow();
        // 插入后隐藏上下文条，不占用空间
        ctxCurrentFile = null;
        ctxStartLine   = -1;
        ctxEndLine     = -1;
        updateContextBar();
    }

    /** 更新上下文条显示 */
    private void updateContextBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (ctxCurrentFile == null) {
                ctxBar.setVisible(false);
                return;
            }
            ctxFileLabel.setText(ctxCurrentFile);
            if (ctxStartLine > 0 && ctxEndLine >= ctxStartLine) {
                ctxLineLabel.setText("  L" + ctxStartLine + "–L" + ctxEndLine);
            } else {
                ctxLineLabel.setText("");
            }
            ctxBar.setVisible(true);
            ctxBar.revalidate();
            ctxBar.repaint();
        });
    }

    /**
     * 监听文件切换事件：用户在 Project View 双击文件 / 切换编辑器标签
     * 当前激活文件名自动填入上下文条
     */
    private void registerFileListener() {
        project.getMessageBus().connect().subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        VirtualFile newFile = event.getNewFile();
                        if (newFile != null) {
                            ctxCurrentFile = newFile.getName();
                            ctxStartLine   = -1;
                            ctxEndLine     = -1;
                            updateContextBar();
                        }
                    }

                    @Override
                    public void fileClosed(@NotNull FileEditorManager source,
                                           @NotNull VirtualFile file) {
                        // 如果当前显示的文件被关闭，则隐藏上下文条
                        if (ctxCurrentFile != null && ctxCurrentFile.equals(file.getName())) {
                            ctxCurrentFile = null;
                            ctxStartLine   = -1;
                            ctxEndLine     = -1;
                            updateContextBar();
                        }
                    }
                }
        );
    }

    /**
     * 监听编辑器选区变化：鼠标拖选代码后实时获取行号范围，显示在上下文条
     */
    private void registerSelectionListener() {
        com.intellij.openapi.editor.EditorFactory.getInstance()
                .getEventMulticaster()
                .addSelectionListener(new SelectionListener() {
                    @Override
                    public void selectionChanged(@NotNull SelectionEvent e) {
                        Editor editor = e.getEditor();
                        // 只监听属于当前 project 的编辑器
                        if (editor.getProject() != project) return;
                        SelectionModel sm = editor.getSelectionModel();
                        VirtualFile vf = editor.getVirtualFile();
                        if (vf != null) ctxCurrentFile = vf.getName();

                        if (sm.hasSelection()) {
                            com.intellij.openapi.editor.Document doc = editor.getDocument();
                            ctxStartLine = doc.getLineNumber(sm.getSelectionStart()) + 1;
                            ctxEndLine   = doc.getLineNumber(sm.getSelectionEnd())   + 1;
                        } else {
                            ctxStartLine = -1;
                            ctxEndLine   = -1;
                        }
                        updateContextBar();
                    }
                }, project); // 使用 project 作为 Disposable，插件卸载自动清理

        // 监听设置变更，模型列表变化时自动刷新下拉框
        CPSettings.SettingsChangeListener settingsListener = () ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    refreshModelCombo();
                    refreshAgentCombo();
                    // 模型/上下文配置变更后，刷新圆环分母
                    if (contextCircle != null) {
                        contextCircle.setMaxContextTokens(CPSettings.getInstance().getChatMaxTokens());
                    }
                });
        CPSettings.getInstance().addSettingsListener(settingsListener);
    }

    private String getEditorContext() {
        if (project == null) return "";
        Editor ed = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (ed == null) return "";
        String t = ed.getDocument().getText();
        return t.length() > 5000 ? t.substring(0, 5000) + "\n...(已截断)" : t;
    }

    private void readProjectFilesAndRespond() {
        statusLabel.setText("正在读取项目文件...");
        statusLabel.setForeground(JBColor.ORANGE);
        ApplicationManager.getApplication().invokeLater(() -> {
            List<FileReaderUtil.FileContent> files = FileReaderUtil.readProjectFiles(project);
            if (files.isEmpty()) {
                addAiMessage("未找到代码文件。");
                statusLabel.setText("就绪");
                statusLabel.setForeground(JBColor.GRAY);
                return;
            }
            conversationManager.getMessages().add(new ChatMessage("user",
                    "请分析以下项目文件，总结项目结构、主要功能和潜在问题：\n\n"
                            + FileReaderUtil.buildFileContext(files)));
            addAiMessage("正在分析 " + files.size() + " 个文件...\n");
            sendToApi();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 底部输入区
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildInputPanel() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(dividerColor());
                g2.setStroke(new BasicStroke(1f));
                int r = 10;
                int w = getWidth() - 1;
                // 顶部圆角线：左上角圆弧 + 直线 + 右上角圆弧
                g2.drawArc(0, 0, r * 2, r * 2, 90, 90);
                g2.drawLine(r, 0, w - r, 0);
                g2.drawArc(w - r * 2, 0, r * 2, r * 2, 0, 90);
                g2.dispose();
            }
        };
        panel.setBackground(UIUtil.getPanelBackground());

        // 上下文条（文件+行号引用）
        panel.add(ctxBar, BorderLayout.NORTH);

        // 注意：任务 + Diff Tab 面板不再放在输入框区（ BorderLayout.CENTER ），
        // 否则它出现时会撑高 SOUTH 区域 → 压缩 CENTER 的 JCEF → 离屏渲染黑闪。
        // 改为浮动叠加在 centerLayer（见下方 centerLayer 构建处），JCEF 尺寸恒定不 resize。

        JBPanel<?> inner = new JBPanel<>(new BorderLayout(0, 8));
        inner.setOpaque(false);
        inner.setBorder(JBUI.Borders.empty(12, 14, 12, 14));

        inputField.setFont(JBUI.Fonts.label(13));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setOpaque(false);
        inputField.setBackground(new Color(0, 0, 0, 0));
        inputField.setForeground(UIUtil.getTextFieldForeground());
        inputField.setCaretColor(UIUtil.getTextFieldForeground());
        inputField.setBorder(JBUI.Borders.empty(10, 12));
        // 粘贴图片（截图）即作为附件
        ImageAwareTransferHandler pasteHandler = new ImageAwareTransferHandler();
        inputField.setTransferHandler(pasteHandler);
        InputMap im = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputField.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendMessage");
        am.put("sendMessage", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { sendMessage(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        // 右键弹出：仅「粘贴」
        installInputFieldContextMenu(inputField);
        // Ctrl/Cmd+V 快捷键粘贴：在组件级注册，覆盖 IDEA 全局 Paste（全局 Paste 只处理文本，不处理图片）
        installInputFieldPasteShortcut(inputField);

        // 输入框滚动容器（最小3行，最大约9-10行，超出滚动）
        JScrollPane inputScrollPane = new JBScrollPane(inputField) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                // 根据文本内容计算实际高度
                int contentHeight = calculateTextAreaHeight(inputField);
                int maxHeight = JBUI.scale(224);
                int minHeight = JBUI.scale(60); // 约3行最小高度
                d.height = Math.min(Math.max(contentHeight, minHeight), maxHeight);
                return d;
            }
        };
        inputScrollPane.setOpaque(false);
        inputScrollPane.getViewport().setOpaque(false);
        inputScrollPane.setBorder(JBUI.Borders.empty());
        inputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        // 滚动条样式
        JScrollBar vBar = inputScrollPane.getVerticalScrollBar();
        vBar.setPreferredSize(new Dimension(JBUI.scale(6), 0));
        vBar.setUnitIncrement(JBUI.scale(16));

        // 文本变化：高度调整 + 弹出/隐藏 @ 数据库面板
        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateHeight();
                try {
                    String ins = safeGetEventText(e);
                    int insertPos = e.getOffset();
                    // 仅当插入文本结尾是 @，且 @ 落在词首位置时才触发，避免行内已有 @ 误触
                    if (ins != null && ins.endsWith("@") && !mentionActive) {
                        String cur = inputField.getText();
                        int atIdx = insertPos + ins.length() - 1;
                        if (atIdx >= 0 && atIdx < cur.length() && cur.charAt(atIdx) == '@') {
                            boolean wordStart = (atIdx == 0) || Character.isWhitespace(cur.charAt(atIdx - 1));
                            if (wordStart) {
                                showMentionPopup(atIdx);
                                return;
                            }
                        }
                    }
                    // 弹窗已显示：若 caret 之前无 @（说明已被删除/跨越），关闭弹窗
                    if (mentionActive && findMentionAtCaret() < 0) {
                        hideMentionPopup();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateHeight();
                if (mentionActive) {
                    if (findMentionAtCaret() < 0) hideMentionPopup();
                }
            }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateHeight(); }
            private void updateHeight() {
                inputScrollPane.revalidate();
                inputScrollPane.getParent().revalidate();
                updateSendBtnAppearance();
            }
        });

        // Esc 关闭 @ 弹窗
        InputMap escIm = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap escAm = inputField.getActionMap();
        escIm.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "codepal.hideMention");
        escAm.put("codepal.hideMention", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (mentionActive) hideMentionPopup();
            }
        });

        // 失焦关闭 @ 弹窗（输入框 focus 离开）
        inputField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(() -> {
                    if (!mentionActive) return;
                    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (owner == inputField) return;
                    if (mentionPopup != null && mentionPopup.isVisible()) {
                        // 弹窗正显示：只有焦点"明确转移到弹窗之外"才关闭。
                        // owner == null 通常是弹窗刚显示、焦点被 MenuSelectionManager / JBPopup 窗口接管的过渡态，
                        // 此时若关闭会让弹窗刚弹出就被 focusLost 误杀，故一律跳过（点击外部时 JBPopup 自身会关闭并触发 onClosed 清理）。
                        if (owner == null) return;
                        if (SwingUtilities.isDescendingFrom(owner, mentionPopup.getContent())) return;
                    }
                    hideMentionPopup();
                });
            }
        });

        // 初始化 @ 弹窗底层（JList + 渲染器），首次触发时复用
        initMentionPopup();

        // 按 / 键弹出 CC 命令面板（已移除 ACP 链路）

        // 底部栏（模式 / 模型 / 附件 / 视觉配置）—— 与输入框共框
        JBPanel<?> bottomBar = new JBPanel<>(new BorderLayout(0, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(JBUI.Borders.emptyTop(4));

        // 圆角输入框容器（占满宽度）—— 固定背景 + 固定边框，不走主题不随聚焦变化
        // 同时承载：NORTH=附件条 / CENTER=输入区 / SOUTH=底部栏（模式+模型+附件+视觉配置）
        JBPanel<?> inputWrapper = new JBPanel<>(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int r = 12;

                // 背景填充
                g2.setColor(inputFieldBg());
                g2.fillRoundRect(1, 1, w - 2, h - 2, r, r);

                // 边框（始终一致）
                g2.setColor(inputBorderNormal());
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);

                g2.dispose();
            }
        };
        inputWrapper.setOpaque(false);

        // 图片附件条（默认隐藏，有附件时显示）
        attachStrip = new JBPanel<>(new BorderLayout());
        attachStrip.setOpaque(false);
        attachStrip.setBorder(JBUI.Borders.emptyBottom(6));
        attachChips = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 6, 2));
        attachChips.setOpaque(false);
        attachStrip.add(attachChips, BorderLayout.CENTER);
        attachStrip.setVisible(false);

        inputWrapper.add(attachStrip, BorderLayout.NORTH);
        inputWrapper.add(inputScrollPane, BorderLayout.CENTER);
        inputWrapper.add(bottomBar, BorderLayout.SOUTH);

        // 右侧按钮（上下文圈 + 发送）放在输入框右侧、与输入框底边对齐
        JBPanel<?> inputCol = new JBPanel<>(new BorderLayout(0, 0));
        inputCol.setOpaque(false);
        inputCol.add(inputWrapper, BorderLayout.CENTER);
        inner.add(inputCol, BorderLayout.NORTH);



        selectModelComboByName(CPSettings.getInstance().getChatModelName());
        modelCombo.setFont(JBUI.Fonts.label(13));
        // 宽度完全跟随当前选中模型名称自适应（收起态无边框、名字完整显示，不加省略号）；
        // 不设 setMaximumSize 封顶——封顶会导致长名字被裁剪。FlowLayout 下超长名自然向右延展，
        // 与右侧圆环/发送按钮同行，主窗口足够宽时不会挤压（IDE 主窗口通常 > 600）。
        modelCombo.setMaximumRowCount(20);

        agentCombo.setFont(JBUI.Fonts.label(11));
        agentCombo.setSelectedItem(CPSettings.getInstance().getCurrentAgentName());
        agentCombo.addActionListener(e -> {
            String sel = (String) agentCombo.getSelectedItem();
            if (sel != null) {
                // 根据选中项的名称，查找其在 CPSettings 中的索引
                List<String> agents = CPSettings.getInstance().getInstalledAgentNames();
                for (int i = 0; i < agents.size(); i++) {
                    if (agents.get(i).equals(sel)) {
                        CPSettings.getInstance().setCurrentAgentNameIndex(i);
                        break;
                    }
                }
                modelCombo.setEnabled(true);
            }
            if (!chatSessionManager.isLoadingSessionConfig()) saveSessionConfig();
            // 切换模型后，收起态宽度需随新模型名重新自适应（触发父容器重新布局）
            modelCombo.revalidate();
        });

        modeCombo.setFont(JBUI.Fonts.label(13));
        // 宽度跟随当前选中模式文字自适应（与模型下拉一致：收起态无边框、hover 才出背景）；
        // 不调用 setPreferredSize/setMinimumSize，否则会绕开 CPComboUI 的自适应 getPreferredSize 覆盖。
        modeCombo.setToolTipText("Craft 模式：可直接创建和修改文件");

        modeCombo.addActionListener(e -> {
            craftMode = "Craft".equals(modeCombo.getSelectedItem());
            if (!conversationManager.isEmpty() && "system".equals(conversationManager.getSystemMessage().getRole())) {
                conversationManager.updateSystemPrompt(
                        CPSettings.getInstance().getSystemPrompt(project, craftMode));
            }
            if (!chatSessionManager.isLoadingSessionConfig()) saveSessionConfig();
            // 切换模型后，收起态宽度需随新模型名重新自适应（触发父容器重新布局）
            modelCombo.revalidate();
        });

        JBPanel<?> leftComboRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftComboRow.setOpaque(false);
        // + 号（附件面板）—— 放在模式选择最左侧：高级白图标，hover 时淡灰圆角背景 + 轻阴影
        // 强制缩放到 16x16（SVG viewBox=1024，否则按按钮 preferredSize 拉伸会爆框）
        Icon plusIcon = new SizedIcon(IconLoader.getIcon("/icons/plus.svg", ChatPanel.class), 16, 16);
        JButton attachBtn = new JButton(plusIcon) {
            private boolean hover = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }
            @Override
            protected void paintComponent(Graphics g) {
                if (hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                        int w = getWidth(), h = getHeight();
                        int r = 8;
                        int inset = 3;
                        // 与圆环一致的悬浮卡片：同心居中、淡半透明黑圆角实心，无描边阴影线
                        int cardSize = Math.min(w, h) - inset * 2;
                        int sx = (w - cardSize) / 2;
                        int sy = (h - cardSize) / 2;
                        g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                        g2.fillRoundRect(sx, sy, cardSize, cardSize, r, r);
                    } finally {
                        g2.dispose();
                    }
                }
                super.paintComponent(g);
            }
        };
        attachBtn.setToolTipText("附加图片");
        attachBtn.setPreferredSize(new Dimension(28, 36));
        attachBtn.setFocusable(false);
        attachBtn.setBorderPainted(false);
        attachBtn.setContentAreaFilled(false);
        attachBtn.setOpaque(false);
        attachBtn.setMargin(new Insets(0, 0, 0, 0));
        attachBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachBtn.addActionListener(e -> showAttachPopup(attachBtn));
        leftComboRow.add(attachBtn);

        leftComboRow.add(modeCombo);
        leftComboRow.add(modelCombo);
        leftComboRow.add(databaseCombo);
        leftComboRow.add(skillButton);
        agentCombo.setVisible(false);

        bottomBar.add(leftComboRow, BorderLayout.WEST);

        // 右侧圆形按钮组
        JBPanel<?> rightActions = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightActions.setOpaque(false);
        // 圆环/发送刚缩到 26，相对左侧更高的 + 号按钮(36)显得偏上，整体下移 5px 与其横向对齐
        rightActions.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        // 圆形上下文进度指示器（正方体卡片 + 圆环）
        contextCircle = new com.codepal.compression.ContextCircleProgress(26);
        // 注入用户配置的模型上下文上限作为圆环分母（替代写死的 1M）
        contextCircle.setMaxContextTokens(CPSettings.getInstance().getChatMaxTokens());
        contextCircle.addActionListener(e -> handleCompress());
        rightActions.add(contextCircle);

        // 发送按钮：现代设计（参考 ChatGPT / Claude 输入框发送按钮）
        //   有文字 → 圆角方块 + 紫蓝底色 + 白色纸飞机图标
        //   无文字 → 圆角方块 + 灰底 + 灰色纸飞机图标
        final Color grayBg = JBColor.namedColor("Button.startBackground",
                !JBColor.isBright() ? new Color(0x4B5360) : new Color(0xE8E9EB));
        final int btnSize = 26;          // 与圆环组件同尺寸（正方形，视觉一致），较原 32 缩小 20%
        final int arcR = 8;              // 圆角半径（随直径等比缩小）
        final int sendIconSize = 16;     // 图标尺寸（随按钮缩小，避免图标占满）

        // 发送/停止图标均改用 SVG（描边风格统一）：亮/暗主题灰 + 激活态白，随主题自动切换
        // 用 SizedIcon 强制缩放到 sendIconSize，避免按钮缩小后图标占满
        sendIconGhost = new SizedIcon(IconLoader.getIcon("/icons/send_ghost.svg", ChatPanel.class), sendIconSize, sendIconSize);
        sendIconWhite = new SizedIcon(IconLoader.getIcon("/icons/send_white.svg", ChatPanel.class), sendIconSize, sendIconSize);
        stopIconGhost = new SizedIcon(IconLoader.getIcon("/icons/stop_ghost.svg", ChatPanel.class), sendIconSize, sendIconSize);
        stopIconWhite = new SizedIcon(IconLoader.getIcon("/icons/stop_white.svg", ChatPanel.class), sendIconSize, sendIconSize);
        sendBtn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int w = getWidth(), h = getHeight();
                boolean enabled = isEnabled();
                boolean hasText = inputField != null && !inputField.getText().trim().isEmpty();
                boolean active = enabled && hasText;
                boolean hover = getModel().isRollover() && active;
                boolean press = getModel().isPressed() && active;

                // 统一圆角矩形尺寸
                int inset = 2;
                int rw = w - inset * 2, rh = h - inset * 2;

                if (active) {
                    // ── 有文字：圆角方块 + 紫蓝底 + 白纸飞机 ──
                    Color fill = sendBtnActiveColor();
                    if (press) fill = shade(fill, 0.85f);
                    else if (hover) fill = shade(fill, 1.06f);
                    g2.setColor(fill);
                    g2.fillRoundRect(inset, inset, rw, rh, arcR, arcR);
                } else {
                    // ── 无文字/禁用：灰底圆角方块 ──
                    g2.setColor(grayBg);
                    g2.fillRoundRect(inset, inset, rw, rh, arcR, arcR);
                }

                // 微弱边框（确保暗色主题下按钮轮廓可见）
                Color borderColor = active ? shade(sendBtnActiveColor(), 0.80f) : new JBColor(0x5A6270, 0x5A6270);
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(inset, inset, rw - 1, rh - 1, arcR, arcR);

                // 图标（居中）
                Icon icon = getIcon();
                if (icon != null && icon.getIconWidth() > 0) {
                    icon.paintIcon(this, g2,
                            (w - icon.getIconWidth()) / 2,
                            (h - icon.getIconHeight()) / 2);
                }
                g2.dispose();
            }
        };
        sendBtn.setIcon(sendIconGhost);
        sendBtn.setToolTipText("发送 (Enter)");
        sendBtn.setFocusable(false);
        sendBtn.setDefaultCapable(false);
        sendBtn.setBorderPainted(false);
        sendBtn.setContentAreaFilled(false);
        sendBtn.setOpaque(false);
        sendBtn.setPreferredSize(new Dimension(btnSize, btnSize));  // 正方形，与圆环对齐
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // 根据输入框内容切换图标颜色：有文字→白图标，无文字→灰图标
        sendBtn.addChangeListener(e -> updateSendBtnAppearance());
        sendBtn.addActionListener(e -> sendMessage());
        rightActions.add(sendBtn);

        // 右侧按钮组（上下文圈 + 发送）放进输入框圆角框内的 bottomBar 右侧
        bottomBar.add(rightActions, BorderLayout.EAST);

        panel.add(inner, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * 根据输入框内容 + 当前状态刷新发送按钮外观（图标颜色 + 重绘背景）。
     * 文本变化由 DocumentListener 驱动；按钮自身状态变化由 ChangeListener 驱动。
     */
    private void updateSendBtnAppearance() {
        if (sendBtn == null || inputField == null) return;
        boolean hasText = !inputField.getText().trim().isEmpty();
        boolean isStop = sendBtn.getIcon() == stopIconGhost || sendBtn.getIcon() == stopIconWhite;
        if (hasText && sendBtn.isEnabled()) {
            sendBtn.setIcon(isStop ? stopIconWhite : sendIconWhite);
        } else {
            sendBtn.setIcon(isStop ? stopIconGhost : sendIconGhost);
        }
        sendBtn.repaint();
    }

    /**
     * 刷新 Agent 工具下拉框：从 Settings 读取已安装 Agent 列表
     */
    private void refreshAgentCombo() {
        if (agentCombo == null) return;
        String prevSel = (String) agentCombo.getSelectedItem();
        agentCombo.removeAllItems();
        List<String> installed = CPSettings.getInstance().getInstalledAgentNames();
        for (String name : installed) {
            agentCombo.addItem(name);
        }
        // 恢复之前选中的项，若已不存在则默认选 CP
        if (prevSel != null) {
            for (int i = 0; i < agentCombo.getItemCount(); i++) {
                if (agentCombo.getItemAt(i).equals(prevSel)) {
                    agentCombo.setSelectedIndex(i);
                    return;
                }
            }
        }
        // 尝试从 Settings 恢复当前选中的 Agent
        String saved = CPSettings.getInstance().getCurrentAgentName();
        for (int i = 0; i < agentCombo.getItemCount(); i++) {
            if (agentCombo.getItemAt(i).equals(saved)) {
                agentCombo.setSelectedIndex(i);
                return;
            }
        }
        agentCombo.setSelectedIndex(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 上下文压缩
    // ─────────────────────────────────────────────────────────────────────────

    private void handleCompress() {
        handleCompress(false);
    }

    /**
     * @param skipConfirm true 表示跳过确认框直接压缩。
     *   用于 checkCompressionHint 的自动提示场景——那里已经问过「是否现在压缩？」，
     *   用户点是之后不该再弹第二个确认框（否则连问两遍）。
     */
    private void handleCompress(boolean skipConfirm) {
        if (streamRenderController != null && streamRenderController.isReceiving()) {
            Messages.showWarningDialog(project, "正在生成回复中，无法压缩。请等待生成完成后再试。", "压缩对话");
            return;
        }

        // 上下文使用量低于阈值（默认 80%）时只给建议、不再硬拦截：
        // 把「建议」与「确认」合并成同一个确认框，用户点「继续压缩」即可照常压缩
        // （原实现是 showInfoMessage + return，用户只能取消，无法主动压缩）。
        long used = usedContextTokens();
        long limit = contextCircle != null ? contextCircle.getMaxContextTokens() : 0L;
        boolean belowThreshold = !compressionManager.shouldCompressByUsage(used, limit);

        // ★ 压缩与否只看上下文占用量，不按消息条数设限；切分点按 token 预算算。
        int recentPct = (int) (com.codepal.compression.CompressionPrompts.RECENT_CONTEXT_RATIO * 100);
        String baseMsg = "压缩将使用 AI 对对话历史进行摘要，按上下文占用保留最近的对话原文（约 "
                + recentPct + "% 上下文窗口）。\n\n"
                + "旧消息不会被删除，将保留在本地数据库中，可随时查阅。";

        String confirmMsg;
        if (belowThreshold) {
            int pct = limit > 0 ? (int) (used * 100 / limit) : 0;
            int thresholdPct =
                    (int) (com.codepal.compression.CompressionManager.COMPRESSION_THRESHOLD_RATIO * 100);
            confirmMsg = "当前上下文使用量约 " + (used / 1000) + "K tokens（" + pct + "%），"
                    + "通常建议超过 " + thresholdPct + "% 时再压缩，以避免不必要的 API 消耗。\n\n"
                    + "如果你现在就想整理历史上下文，也可以继续。\n\n"
                    + baseMsg;
        } else {
            confirmMsg = baseMsg;
        }

        if (!skipConfirm) {
            int choice = Messages.showOkCancelDialog(project, confirmMsg, "压缩对话",
                    "继续压缩", "取消", Messages.getQuestionIcon());
            if (choice != Messages.OK) return;
        }

        // 禁用按钮，防止重复点击
        contextCircle.setEnabled(false);

        // 压缩期间禁止发送按钮
        isCompressing = true;
        if (sendBtn != null) {
            sendBtn.setIcon(stopIconGhost);
            sendBtn.setToolTipText("对话压缩中...");
        }
        if (inputField != null) {
            inputField.setEditable(false);
            inputField.setBackground(inputFieldBg().darker());
        }

        // 在聊天区显示压缩进度卡片
        chatWebView.showCompressCard("正在分析对话历史...");

        // 后台执行压缩（异常直接提示到消息窗，让用户重试）
        final long myGen = streamRenderController.getStreamGeneration();
        com.codepal.utils.ThreadHelper.executeAsync(project,
            () -> {
                try {
                    return compressionManager.compress(
                        conversationManager.getMessages(),
                        chatSessionManager.getCurrentSessionId(),
                        statusText -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                            () -> {
                                chatWebView.updateCompressCard(statusText, false, false);
                                if (contextCircle != null) {
                                    contextCircle.setToolTipText(statusText);
                                }
                            }
                        ),
                        limit   // 上下文窗口大小，用于按 token 预算决定保留多少最近消息
                    );
                } catch (Exception e) {
                    System.err.println("[Compress] 异常: " + e.getMessage());
                    return new com.codepal.compression.CompressionManager.CompressionResult(
                        false, e.getMessage(), 0, 0);
                }
            },
            result -> {
                if (!streamRenderController.isCurrentGeneration((int)myGen)) {
                    isCompressing = false;
                    restoreSendButton();
                    restoreInputField();
                    return;
                }
                isCompressing = false;
                contextCircle.setEnabled(true);
                restoreSendButton();
                restoreInputField();

                if (result.success) {
                    // 持久化压缩状态到 DB
                    String sessionId = chatSessionManager.getCurrentSessionId();
                    if (result.firstPreservedQaRound > 0) {
                        com.codepal.db.DBChatHistoryRepository.markMessagesCompressedBeforeRound(
                            sessionId, result.firstPreservedQaRound);
                    }
                    if (result.summaryText != null && !result.summaryText.isBlank()) {
                        // 与内存一致：摘要消息在内存里是 MEMORY_SUMMARY_PREFIX + summary，
                        // DB 写入也必须带前缀，否则重载会话后摘要缺前缀、与压缩时模型看到的上下文不一致。
                        com.codepal.db.DBChatHistoryRepository.upsertSummaryMessage(
                            sessionId,
                            com.codepal.compression.CompressionPrompts.MEMORY_SUMMARY_PREFIX + result.summaryText,
                            result.compressedMessageCount);
                    }
                    updateContextCircleFromHistory();  // 仅在成功时更新圆环
                    String msg = "对话已压缩：" + result.message
                        + "，节省约 " + result.getSavedCount() + " 条消息的上下文空间";
                    chatWebView.updateCompressCard(msg, true, false);
                    contextCircle.setToolTipText(result.message + " - 点击重新压缩");
                } else {
                    // 失败：不修改 conversationHistory，圆环保持 API 上报的精确值
                    String msg = "压缩失败：" + result.message + "\n\n请稍后重试。";
                    chatWebView.updateCompressCard(msg, false, true);
                    contextCircle.setToolTipText("压缩失败，点击重试");
                }

                compressionHintShown = false;
            }
        );
    }

    /**
     * 压缩成功 / 会话加载后刷新圆环。
     * 此时真实 usage 累计已失效（历史被裁剪/尚未累积），统一用 estimateSessionTokens() 兜底估算，
     * 与 onComplete 兜底刷新保持同一口径（~1.6 字符/token）。
     */
    private void updateContextCircleFromHistory() {
        if (contextCircle == null) return;
        contextCircle.setTokens(estimateSessionTokens());
    }

    /**
     * 检查是否需要提示用户压缩（超过阈值时提示一次）
     */
    private void checkCompressionHint() {
        if (compressionHintShown) return;
        if (streamRenderController != null && streamRenderController.isReceiving()) return;
        long used = usedContextTokens();
        long limit = contextCircle.getMaxContextTokens();
        if (compressionManager.shouldCompressByUsage(used, limit)) {
            compressionHintShown = true;
            int pct = limit > 0 ? (int) (used * 100 / limit) : 0;
            ApplicationManager.getApplication().invokeLater(() -> {
                int choice = Messages.showYesNoDialog(project,
                    "当前上下文使用量已达 " + pct + "%，上下文窗口比较紧张。\n\n" +
                        "建议压缩对话历史以释放上下文空间，让模型更专注于当前任务。\n\n" +
                        "是否现在压缩？",
                    "上下文过长提示",
                    Messages.getInformationIcon());
                if (choice == Messages.YES) {
                    // 已在此处确认过，跳过 handleCompress 内部的第二个确认框
                    handleCompress(true);
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 发送逻辑
    // ─────────────────────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() && (streamRenderController == null || !streamRenderController.isReceiving())) return;

        if (isCompressing) return;  // 压缩期间禁止发送

        if (streamRenderController != null && streamRenderController.isReceiving()) {
            streamRenderController.setReceiving(false);
            streamRenderController.incrementGeneration();      // 递增代数，使所有已排队的旧回调失效
            taskWasInterrupted = true; // 标记任务被中断，下次发消息时添加上下文切换提示
            // 停止生成时不清除累积状态，已暂存的文件修改保留
            // 同时终止正在执行的工具命令
            toolOrchestrator.cancelAll();
            agentBackendManager.getCurrentBackend().cancelCurrent();
            String stopReasoning = streamRenderController.getCurrentReasoningText();
            String stopReply = streamRenderController.getCurrentAiRawText();
            if (!stopReply.isEmpty()) {
                // 停止时把已生成的部分回复提交进对话记忆并持久化，避免被剔除
                ChatMessage assistant = new ChatMessage("assistant", stopReply);
                if (!stopReasoning.isEmpty()) {
                    streamRenderController.finalizeReasoningIfOpen();
                    assistant.setReasoning_content(stopReasoning);
                }
                addMsgToConversation(assistant);
                // ── 持久化停止时的部分回复 ──
                String stopMsgId = DBChatHistoryRepository.getUUID();
                ChatMessageEntity stopMsgEntity = new ChatMessageEntity();
                stopMsgEntity.setId(stopMsgId);
                stopMsgEntity.setSessionId(chatSessionManager.getCurrentSessionId());
                stopMsgEntity.setRole(Constant.ROLE_assistant);
                stopMsgEntity.setQaRound(chatSessionManager.getCurrentQaRound());
                stopMsgEntity.setCreatedAt(System.currentTimeMillis());
                stopMsgEntity.setUpdatedAt(System.currentTimeMillis());
                java.util.List<com.codepal.model.MessagePartEntity> stopParts = new java.util.ArrayList<>();
                int stopSeq = 0;
                if (stopReasoning != null && !stopReasoning.isEmpty()) {
                    stopParts.add(com.codepal.model.MessagePartEntity.thinking(stopMsgId, chatSessionManager.getCurrentSessionId(), stopReasoning, stopSeq++));
                }
                stopParts.add(com.codepal.model.MessagePartEntity.text(stopMsgId, chatSessionManager.getCurrentSessionId(), stopReply, stopSeq++));
                chatSessionManager.persistMessageWithParts(stopMsgEntity, stopParts);
                String bodyHtml = MarkdownUtil.toHtmlFragment(stopReply);
                chatWebView.finalizeAiMessage(bodyHtml, stopReply);
                // 纯文本回复被中断：保留为正式消息，下一轮不再裁剪它
                taskWasInterrupted = false;
            } else if (!stopReasoning.isEmpty()) {
                // ★ 模型把正文放进 reasoning_content、content 为空：提升为正文（避免下一轮被跳过）
                promoteReasoningAsAnswer(stopReasoning, chatWebView, conversationManager, chatSessionManager.getCurrentSessionId());
                taskWasInterrupted = false;
            } else if (streamRenderController.isAiStreamStarted()) {
                streamRenderController.cancelPendingRenderAndBump();
                chatWebView.sealStream();
            }
            statusLabel.setText("已停止");
            statusLabel.setForeground(JBColor.GRAY);
            restoreSendButton();
            // ★ 清除 JS 轮播状态（"正在处理任务..."等），否则会一直轮播不停
            chatWebView.clearStatusTimer();
            // ★ 断点续传：写入 SYSTEM_AUTO 标识的 user 消息，标记任务被用户中断
            iterationGuard.insertUserStopMessage();
            // ★ 在 UI 上显示中断提示（insertUserStopMessage 只写内存/DB，不渲染 UI）
            addAiMessage("⚠️ 消息已被用户中断");
            // ★ 重置流式渲染状态，否则下一轮 stream 的 appendStreamChunk 会因
            //   aiStreamStarted 仍为 true 而跳过 startAiStream()，导致 WebView 没有新 bubble
            clearStreamRefs();
            return;
        }

        // 防重复发送：如果最后一条消息与此内容相同，跳过（防止快速连击导致重复 user 消息）
        if (conversationManager.isLastUserMessageEqualTo(text)) {
            inputField.setText("");
            return;
        }

        inputField.setText("");
        // 发送后清空当前会话草稿（重开插件时不再残留已发送内容）
        if (activeInputSessionId != null) inputDraftBySession.put(activeInputSessionId, "");

        // 图片附件：按当前模型能力分支处理
        List<ChatMessage.Attachment> toSend = new ArrayList<>(pendingAttachments);
        final String sendText;
        boolean inlineVision = false;
        if (!toSend.isEmpty()) {
            if (CPSettings.getInstance().shouldInlineImages()) {
                // 主模型自带视觉：图片以多模态 content 直传，不写 view_image 提示
                sendText = text;
                inlineVision = true;
            } else {
                sendText = (text.isEmpty() ? "" : text + "\n") + buildImageHint(toSend);
            }
            pendingAttachments.clear();
            refreshAttachStrip();
        } else {
            sendText = text;
        }

        // 预先生成用户消息 DB id（同一 id 用于 UI 挂载、持久化、payload 快照三者对齐）
        final String userMsgId = DBChatHistoryRepository.getUUID();
        // 捕获「本轮发送给模型的完整上下文」：此时 conversationManager 尚未加入本轮 user 消息，
        // 故 history 为之前的多轮历史，userMessage 单独存本轮输入。
        captureOutgoingPayload(userMsgId, sendText);

        ThreadHelper.runOnUi(project,()->{
            chatWebView.resetAiStream();
            if (!toSend.isEmpty()) {
                addUserMessageWithImages(text, toSend, userMsgId);
            } else {
                addUserMessage(sendText, userMsgId);
            }
        });

        // 用户发送首条消息：临时会话此刻才真正入库（未发消息不落库）
        chatSessionManager.ensurePersistedSession();
        // 新会话入库后刷新历史面板，让新会话出现在历史列表中
        refreshHistoryList();
        chatSessionManager.incrementQaRound(); // 新一问开始
        ChatMessage userMsg = new ChatMessage("user", sendText);
        userMsg.setQaRound(chatSessionManager.getCurrentQaRound());
        if (!toSend.isEmpty()) {
            userMsg.setAttachments(toSend);
            userMsg.setInlineVision(inlineVision);
        }
        conversationManager.add(userMsg);
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(userMsgId);
        message.setSessionId(chatSessionManager.getCurrentSessionId());
        message.setRole("user");
        message.setQaRound(chatSessionManager.getCurrentQaRound());
        message.setCreatedAt(System.currentTimeMillis());
        message.setUpdatedAt(System.currentTimeMillis());
        com.codepal.model.MessagePartEntity userTextPart = com.codepal.model.MessagePartEntity.text(
                message.getId(), chatSessionManager.getCurrentSessionId(), text, 0);
        chatSessionManager.persistMessageWithParts(message, java.util.Collections.singletonList(userTextPart));
        // 首条用户消息后自动命名会话（仅当仍是默认「新会话」时生效）
        String titleSource = (text != null && !text.isBlank()) ? text : sendText;
        chatSessionManager.autoTitleFromUserMessage(titleSource);
        // 新消息开始时：不清除未处理的 pending 文件，仅重置收集标志让新一轮 edit_file 重新触发累积
        planCollecting = false;
        planFilesWithCards.removeIf(fp -> !planFileStates.containsKey(fp));
        autoFixAttempts = 0;
        iterationGuard.resetRoundCounters();
        sendToApi();
    }

    private String extractFileReferences(String text) {
        StringBuilder ctx = new StringBuilder();
        Matcher m = Pattern.compile("@([\\w.\\-/]+)").matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String content = FileReaderUtil.findAndReadFile(name);
            if (content != null)
                ctx.append("--- 文件: ").append(name).append(" ---\n").append(content).append("\n\n");
        }
        return ctx.toString();
    }


    /**
     * 清理被用户中断的工具调用轮次：从末尾向上扫描，跳过系统消息，
     * 找到最后两个用户消息之间的所有工具调用链并移除。
     *
     * <p>为什么这样做：模型看到对话以 [assistant: tool_calls] + [tool_result]
     * 结尾时，会认为任务未完成并"继续"执行。清理这些未完成轮次后，对话以干净的
     * [user: 旧请求] + [user: 新请求] 结束，模型自然响应最新请求。
     */
    private void trimInterruptedTail(List<ChatMessage> history) {
        if (history.size() < 3) return;

        // 1. 从末尾找最后一个 user 消息（刚添加的新消息）
        int lastUserIdx = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) return; // 第一个消息就是 user，无需清理

        // 2. 继续向前找上一个 user 消息（发起被中断任务的用户请求）
        int prevUserIdx = -1;
        for (int i = lastUserIdx - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                prevUserIdx = i;
                break;
            }
        }
        if (prevUserIdx < 0) return; // 没有前一个 user 消息，无法判定边界

        // 3. 验证中间确实有工具调用链（至少有 1 条非 user/system 消息）
        if (lastUserIdx - prevUserIdx <= 1) return; // 连续的 user 消息，无需清理

        // 4. 清理：移除 [prevUserIdx+1, lastUserIdx) 范围的所有消息
        history.subList(prevUserIdx + 1, lastUserIdx).clear();
    }

    //业务层
    private void sendToApi() {
        // 防守：如果被后台线程（如 onToolCalls）调用，自动切回 EDT
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::sendToApi);
            return;
        }
        // ★ 关键修复：切换会话后历史还在异步加载中，若此时直接发送，
        // conversationManager 里只有 system，模型会彻底失忆。
        // 等待历史加载完成后再真正发送。
        if (chatSessionManager.isLoadingHistory()) {
            // 用数组 holder 引用 Timer 自身（lambda 捕获时 t[0] 已完成赋值，避免"变量可能尚未初始化"）
            javax.swing.Timer[] t = new javax.swing.Timer[1];
            t[0] = new javax.swing.Timer(100, e -> {
                t[0].stop();
                sendToApi();
            });
            t[0].setRepeats(false);
            t[0].start();
            return;
        }
        streamRenderController.setResponseHadToolCalls(false);
        // 重置流式写入可视化状态（每轮请求都是新的工具调用流）
        writeStreamCardActive = false;
        streamWriteCardIndex = -1;
        streamWriteRawArgsByIndex.clear();
        streamWriteShownLen = 0;
        streamWriteTitle = "";
        streamWriteRemovedLines = 0;
        streamWriteIsEdit = false;
        streamWriteOriginal = "";
        // ── DeepSeek 路径 ──
        // ★ 立即在 EDT 更新 UI 状态，避免用户感知延迟
        streamRenderController.setReceiving(true);
        final int myGen = streamRenderController.incrementGeneration();  // 递增代数，回调中用于校验
        statusLabel.setForeground(new Color(0x4CAF50));
        if(!stopIconGhost.equals(sendBtn.getIcon())){
            sendBtn.setIcon(stopIconGhost);
            sendBtn.setToolTipText("停止生成");
        }
        // 合并内置工具 + MCP 工具（在 EDT 快照，线程安全）
        List<ChatRequest.ToolDefinition> allTools = new ArrayList<>(
                com.codepal.tools.ToolDefinitions.getAllTools());
        List<Map<String, Object>> mcpToolDefs = McpService.getToolDefinitions();
        final List<ChatRequest.ToolDefinition> mcpToolDefList = new ArrayList<>();
        for (Map<String, Object> mcpTool : mcpToolDefs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> func = (Map<String, Object>) mcpTool.get("function");
            if (func != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) func.get("parameters");
                ChatRequest.ToolDefinition mcpDef = new ChatRequest.ToolDefinition(
                        new ChatRequest.ToolDefinition.FunctionDef(
                                (String) func.get("name"),
                                (String) func.get("description"),
                                params != null ? params : Map.of()
                        )
                );
                allTools.add(mcpDef);
                mcpToolDefList.add(mcpDef);
            }
        }
        if (!mcpToolDefs.isEmpty()) {
            System.out.println("[MCP] Injected " + mcpToolDefs.size()
                    + " MCP tools into API request");
        }

        // 所以保留中断轮次的工具结果作为下一轮上下文，既不 400 也不丢信息。
        taskWasInterrupted = false;

        conversationManager.updateSystemPrompt(
                CPSettings.getInstance().getSystemPrompt(project, craftMode));

        List<ChatMessage> debugMsgs = conversationManager.snapshot();
        System.out.println("[Debug-SendToApi] 对话共 " + debugMsgs.size() + " 条，逐条检查 content：");
        java.util.List<String> nullContentReport = new java.util.ArrayList<>();
        for (int i = 0; i < debugMsgs.size(); i++) {
            ChatMessage m = debugMsgs.get(i);
            String role = m.getRole();
            boolean hasTc = m.getTool_calls() != null && !m.getTool_calls().isEmpty();
            String tcInfo = hasTc ? " tool_calls=" + m.getTool_calls().size() : "";
            if (m.getTool_call_id() != null) {
                tcInfo = " tool_call_id=" + m.getTool_call_id() + " name=" + m.getName();
            }
            String contentPreview = m.getContent() != null
                    ? m.getContent().replace("\n", "\\n").substring(0, Math.min(50, m.getContent().length()))
                    : "⚠️NULL";
            String line = "  [" + i + "] role=" + role + " qaRound=" + m.getQaRound() + tcInfo + " content=" + contentPreview;
            System.out.println(line);
            // 记录非法消息：content 为 null 且不是「带 tool_calls 的 assistant」（后者合法）
            if (m.getContent() == null && !(hasTc)) {
                nullContentReport.add("[" + i + "] role=" + role + " qaRound=" + m.getQaRound()
                        + " (content=null 且无 tool_calls → DeepSeek 会拒绝)");
            }
        }
        if (!nullContentReport.isEmpty()) {
            System.err.println("[Debug-SendToApi] ⚠️ 发现 " + nullContentReport.size()
                    + " 条 content 为 null 的非法消息（根因）：");
            for (String r : nullContentReport) System.err.println("    " + r);
        }
        List<ChatMessage> historySnapshot = sanitizeMessages(conversationManager.snapshot());
        final List<ChatRequest.ToolDefinition> finalAllTools = allTools;

        // ★ 多标签隔离：捕获发起时的上下文（sessionId/WebView/SRC/ConvMgr），
        //   确保切换标签后背景标签的流仍写入自己的 WebView、持久化到正确的会话
        final String sendSessionId = chatSessionManager.getCurrentSessionId();
        final ChatWebView sendWebView = chatWebView;
        final StreamRenderController sendSrc = streamRenderController;
        final ConversationManager sendConvMgr = conversationManager;

        // ★ 后台线程：streamChat 全部异步（streamChat 内部 OkHttp enqueue）
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) return;
            // 迭代超限：不再发起新请求
            if (iterationGuard.isStopMessageInserted()) return;
            // 防止后台准备期间用户取消了请求
            if (!sendSrc.isCurrentGeneration(myGen)) return;

            com.codepal.agent.AgentBackend backend = agentBackendManager.getCurrentBackend();
            backend.streamChat(historySnapshot, finalAllTools,
                    createStreamCallback(myGen, sendSessionId, sendWebView, sendSrc, sendConvMgr));
        });
        // ★ 立即显示 AI 加载气泡（根据上一轮工具类型选择轮播提示词）
        sendSrc.startAiStreamLoading(pendingLoadingMsgKey);
        pendingLoadingMsgKey = "ai_loading"; // 用完重置，下一轮无工具时回默认
    }

    /**
     * 发送前清洗消息列表，避免 DeepSeek 返回 400（missing field content）。
     *
     * <p>根因：历史恢复 / 工具调用等场景下，可能混入 content 为 null 的消息
     * （尤其 user 消息恢复时若缺少 text part）。DeepSeek 要求每条消息都有 content 字段。
     *
     * <ul>
     *   <li>user / tool / system：content 为 null → 兜底为空串（避免 missing field）</li>
     *   <li>assistant：content 为 null 且不含 tool_calls → 整条移除（API 不允许空 assistant）</li>
     *   <li>assistant：content 为 null 但含 tool_calls → 合法，保留</li>
     *   <li>null 元素 → 直接跳过</li>
     * </ul>
     */
    private List<ChatMessage> sanitizeMessages(List<ChatMessage> msgs) {
        // 三遍管道修复：去重 → 删孤儿 → 补缺口（避免 API 400 错误）
        int repaired = IterationGuard.ensureConversationIntegrity(msgs);
        if (repaired > 0) {
            System.out.println("[sanitizeMessages] 对话完整性修复: " + repaired + " 条消息");
        }
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : msgs) {
            if (m == null) continue;
            String role = m.getRole();
            boolean hasToolCalls = m.getTool_calls() != null && !m.getTool_calls().isEmpty();
            if ("assistant".equals(role)) {
                if (m.getContent() == null && !hasToolCalls) {
                    continue; // 空 assistant 消息，移除
                }
                out.add(m); // 含 tool_calls 的 assistant(content=null) 合法
            } else {
                if (m.getContent() == null) {
                    m.setContent(""); // user/tool/system 不允许 content 缺失
                }
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 创建流式回调（与 sendToApi 解耦，支持后台异步执行）。
     * ★ 多标签隔离：传入捕获的 sessionId/WebView/SRC/ConvMgr，回调内使用捕获引用而非实例字段，
     *   确保切换标签后背景标签的流仍写入自己的 WebView、持久化到正确的会话。
     */
    private com.codepal.agent.AgentBackend.StreamCallback createStreamCallback(
            final int myGen,
            final String sendSessionId,
            final ChatWebView sendWebView,
            final StreamRenderController sendSrc,
            final ConversationManager sendConvMgr) {
        sendSrc.startWatchdog(); // ★ 回合开始：启动看门狗（检测模型/工具无进展）
        sendSrc.getCurrentReasoning().setLength(0); // 每个 stream 重置思考累积（原每调用 new StringBuilder()）
        return new com.codepal.agent.AgentBackend.StreamCallback() {
                    @Override
                    public void onMessage(String chunk) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) return; // 旧 stream，跳过
                            statusLabel.setText("回复中...");
                            statusLabel.setForeground(new Color(0x4CAF50));
                            sendSrc.appendStreamChunk(chunk);
                        });
                    }

                    //如果存在思考，则首先被执行
                    @Override
                    public void onReasoning(String reasoning) {
                        sendSrc.getCurrentReasoning().append(reasoning);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) return; // 旧 stream，跳过
                            statusLabel.setText("思考中...");
                            statusLabel.setForeground(new Color(0xCE93D8));
                            sendSrc.appendReasoningChunk(reasoning);
                        });
                    }

                    @Override
                    public void onToolCallPreparing() {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) return;
                            statusLabel.setText("准备工具调用...");
                            statusLabel.setForeground(new Color(0xFF9800));
                            // ★ 显示工具调用准备中的轮播提示（替代停住的 reasoning/content）
                            sendSrc.startAiStreamLoading("tool_executing");
                        });
                    }

                    @Override
                    public void onToolArgsDelta(int index, String deltaArgs) {
                        // 真实流式写入可视化：边收边把已生成的 file_content 铺进工具卡片。
                        // 此回调来自 OkHttp 网络线程，所有 Swing 操作必须切 EDT。
                        if (deltaArgs == null || deltaArgs.isEmpty()) return;
                        final int idx = index;
                        final String delta = deltaArgs;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) return; // 旧 stream，跳过
                            // ★ 按工具 index 隔离参数缓冲区，避免多个工具的参数片段互相污染
                            //   （如 todo 的 "content" + write_file 的 "file_path" 混在同一 buffer 导致误判）
                            StringBuilder buf = streamWriteRawArgsByIndex.computeIfAbsent(idx, k -> new StringBuilder());
                            buf.append(delta);
                            String raw = buf.toString();
                            // 仅对写入类 / 编辑类工具做流式展示（write_file / create_new_file / edit_file）
                            if (!writeStreamCardActive) {
                                // 检测写入类（含 file_path + file_content/content 大字段）
                                // ★ 必须同时包含 file_path，否则 todo 工具的 "content" 字段会误判为写入类
                                boolean hasFilePath = raw.contains("\"file_path\"");
                                boolean isWrite = hasFilePath && (raw.contains("\"file_content\"") || raw.contains("\"content\""));
                                // 检测编辑类（file_path + search/replace 单对，或 edits 批量数组）
                                boolean isEdit = hasFilePath && !isWrite && (raw.contains("\"search\"") || raw.contains("\"edits\""));
                                if (!isWrite && !isEdit) {
                                    return; // 其它工具（search/run/code_review/todo 等）不流式展示
                                }
                                writeStreamCardActive = true;
                                streamWriteCardIndex = idx;
                                streamWriteShownLen = 0;
                                String fullPath = extractStreamFileName(raw);
                                String shortName = fullPath;
                                int sl = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'));
                                if (sl >= 0) shortName = fullPath.substring(sl + 1);
                                if (isEdit) {
                                    streamWriteIsEdit = true;
                                    // 读取被编辑文件的原始内容（一次性，用于潜在的预览/校验）
                                    streamWriteOriginal = readFileContentSafe(fullPath);
                                } else {
                                    streamWriteIsEdit = false;
                                    // 覆盖写：统计被替换文件的原有行数作为 removed（create_new_file 文件尚不存在→0）
                                    streamWriteRemovedLines = countFileLines(fullPath);
                                }
                                String title = shortName.isEmpty()
                                        ? (isEdit ? "编辑文件…" : "写入文件…")
                                        : (isEdit ? "编辑 " + shortName : "写入 " + shortName);
                                streamWriteTitle = title;
                                sendWebView.appendToolCard(title, "pending");
                                sendWebView.updateWriteCardStats(title, 0, streamWriteIsEdit ? 0 : streamWriteRemovedLines);
                                sendSrc.startAiStreamLoading(isEdit ? "file_editing" : "file_writing");
                            }
                            if (idx != streamWriteCardIndex) return; // 并发其它工具，忽略

                            if (streamWriteIsEdit) {
                                // ── edit_file 流式：抽取 search→replace 对，整块展示 diff 风格预览 ──
                                java.util.List<String[]> pairs = extractStreamEditPairs(raw);
                                if (pairs.isEmpty()) return;
                                String txt = buildEditPreviewText(pairs);
                                if (txt.length() <= streamWriteShownLen) return; // 无新增
                                streamWriteShownLen = txt.length();
                                int added = 0, removed = 0;
                                for (String[] p : pairs) {
                                    removed += p[0].split("\n", -1).length;
                                    added += p[1].split("\n", -1).length;
                                }
                                sendWebView.updateWriteCardStats(streamWriteTitle, added, removed);
                                sendWebView.streamPendingToolCardText(txt);
                            } else {
                                // ── write_file 流式：把「整段内容」放进单个 <pre> 文本节点 ──
                                //   （参考 yours_agent 整块预览思路，不把每个 chunk 的 delta 包成 <div> 追加，
                                //    后者会导致每字符/每两字一行，见 user@image.a2d13e703a）
                                String content = extractStreamFileContent(raw);
                                if (content == null) return;
                                if (content.length() <= streamWriteShownLen) return; // 无新增
                                streamWriteShownLen = content.length();
                                final String full = content;
                                // 头部 +/− 行数统计（auto-dev / CodeBuddy 风格）：added=当前已生成内容行数
                                final int addedLines = full.split("\n", -1).length;
                                sendWebView.updateWriteCardStats(streamWriteTitle, addedLines, streamWriteRemovedLines);
                                sendWebView.streamPendingToolCardText(full);
                            }
                        });
                    }

                    @Override
                    public void onToolCalls(java.util.List<com.codepal.model.ChatMessage.ToolCall> toolCalls) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // ★ 工具即将执行：暂停看门狗（已知在忙，不应判为卡死）
                            sendSrc.pauseWatchdog();
                            // 代数校验：只处理当前 stream 的回调，旧 stream 直接丢弃
                            if (!sendSrc.isCurrentGeneration(myGen)) {
                                System.out.println("onToolCalls 代数不匹配（当前=" + sendSrc.getStreamGeneration() + " 回调=" + myGen + "），丢弃");
                                return;
                            }
                            // 防御：理论上 DeepSeekClient 已保证不会回调空列表，这里双保险。
                            // 若仍收到空列表，按 onComplete 收尾，绝不创建 content=null 的非法 assistant 消息。
                            if (toolCalls == null || toolCalls.isEmpty()) {
                                System.err.println("[ChatPanel] onToolCalls 收到空列表（理论上不应发生），按 onComplete 收尾，避免产出非法消息");
                                String emptyRsn = sendSrc.getCurrentReasoningText();
                                String emptyReply = sendSrc.getCurrentAiRawText();
                                if (!emptyReply.isEmpty()) {
                                    sendWebView.finalizeAiMessage(
                                            MarkdownUtil.toHtmlFragment(emptyReply), emptyReply, false);
                                    sendSrc.finalizeReasoningIfOpen();
                                } else if (!emptyRsn.isEmpty()) {
                                    // 模型把正文放进 reasoning_content：提升为正文（JS会remove rsn块并清理lcCurrentRsnId）
                                    promoteReasoningAsAnswer(emptyRsn, sendWebView, sendConvMgr, sendSessionId);
                                    sendSrc.finalizeReasoningIfOpen();
                                } else {
                                    sendSrc.finalizeReasoningIfOpen();
                                    if (sendSrc.isAiStreamStarted()) {
                                        sendSrc.cancelPendingRenderAndBump();
                                        sendWebView.sealStream(false);
                                    }
                                }
                                sendSrc.setReceiving(false);
                                restoreSendButton();
                                statusLabel.setText("就绪");
                                statusLabel.setForeground(JBColor.GRAY);
                                clearStreamRefs();
                                return;
                            }
                            sendSrc.setResponseHadToolCalls(true);
                            System.out.println("onToolCalls------------------------");
                            // ── 关闭当前轮次气泡（对齐 ACP 的 finalizeAcpRound）──
                            sendSrc.finalizeReasoningIfOpen();
                            if (!sendSrc.getCurrentAiRawText().isEmpty()) {
                                String bodyHtml = MarkdownUtil.toHtmlFragment(sendSrc.getCurrentAiRawText());
                                sendWebView.finalizeAiMessage(bodyHtml, sendSrc.getCurrentAiRawText(), false);
                            } else if (sendSrc.isAiStreamStarted()) {
                                sendSrc.cancelPendingRenderAndBump();
                                sendWebView.sealStream(false);
                            }

                            // 不在此创建气泡——工具卡片是独立元素，后续推理/正文到达时自动创建

                            // ── 保存本轮 assistant 消息到 conversationHistory ──
                            ChatMessage assistantMsg = com.codepal.model.ChatMessage.assistantWithToolCalls(toolCalls);
                            String reasoningSnapshot = sendSrc.getCurrentReasoning().length() > 0 ? sendSrc.getCurrentReasoning().toString() : "";
                            if (reasoningSnapshot.length() > 0) {
                                assistantMsg.setReasoning_content(reasoningSnapshot);
                            }
                            sendConvMgr.add(assistantMsg);

                            // ── 持久化：消息头 + parts（thinking + tool[pending]） ──
                            String currentAssistantMsgId = DBChatHistoryRepository.getUUID();
                            ChatMessageEntity assistantEntity = new ChatMessageEntity();
                            assistantEntity.setId(currentAssistantMsgId);
                            assistantEntity.setSessionId(sendSessionId);
                            assistantEntity.setRole(Constant.ROLE_assistant);
                            assistantEntity.setQaRound(chatSessionManager.getCurrentQaRound());
                            assistantEntity.setCreatedAt(System.currentTimeMillis());
                            assistantEntity.setUpdatedAt(System.currentTimeMillis());

                            java.util.List<com.codepal.model.MessagePartEntity> assistantParts = new java.util.ArrayList<>();
                            int partSeq = 0;

                            // thinking part
                            if (reasoningSnapshot.length() > 0) {
                                assistantParts.add(com.codepal.model.MessagePartEntity.thinking(
                                        currentAssistantMsgId, sendSessionId,
                                        reasoningSnapshot, partSeq++));
                            }

                            // text part —— ★ 修复：工具调用边界前的正文必须持久化，
                            // 否则历史回显丢失「穿插的正文」（只有深度思考+工具卡片显示）。
                            // 顺序保持模型自然输出序：thinking → text → tool。
                            String boundaryText = sendSrc.getCurrentAiRawText();
                            if (boundaryText != null && !boundaryText.isEmpty()) {
                                assistantParts.add(com.codepal.model.MessagePartEntity.text(
                                        currentAssistantMsgId, sendSessionId,
                                        boundaryText, partSeq++));
                            }

                            // tool parts (pending) — 收集ID用于后续更新
                            java.util.List<String> pendingToolPartIds = new java.util.ArrayList<>();
                            for (com.codepal.model.ChatMessage.ToolCall tc : toolCalls) {
                                String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                                String toolInput = tc.getFunction() != null && tc.getFunction().getArguments() != null
                                        ? tc.getFunction().getArguments() : "{}";
                                com.codepal.model.MessagePartEntity pendingPart = com.codepal.model.MessagePartEntity.tool(
                                        currentAssistantMsgId, sendSessionId,
                                        toolName, toolInput, null, "pending", partSeq++);
                                assistantParts.add(pendingPart);
                                pendingToolPartIds.add(pendingPart.getId());
                            }

                            chatSessionManager.persistMessageWithParts(assistantEntity, assistantParts);

                            // ── 根据工具类型预设下一轮轮播提示词 ──
                            pendingLoadingMsgKey = "tool_executing";
                            for (com.codepal.model.ChatMessage.ToolCall tct : toolCalls) {
                                String tn = tct.getFunction() != null ? tct.getFunction().getName() : "";
                                if ("write_file".equals(tn) || "create_new_file".equals(tn)) {
                                    String rawArgs = tct.getFunction() != null ? tct.getFunction().getArguments() : "{}";
                                    String fp = extractJsonString(rawArgs, "file_path");
                                    if (!fp.isBlank()) {
                                        String shortName = fp.contains("/") || fp.contains("\\")
                                                ? fp.substring(Math.max(fp.lastIndexOf('/'), fp.lastIndexOf('\\')) + 1) : fp;
                                        StatusMessageManager.registerFileWriting("file_writing", shortName);
                                    }
                                    pendingLoadingMsgKey = "file_writing";
                                    break;
                                }
                                if ("edit_file".equals(tn)) {
                                    String rawArgs = tct.getFunction() != null ? tct.getFunction().getArguments() : "{}";
                                    String fp = extractJsonString(rawArgs, "file_path");
                                    if (!fp.isBlank()) {
                                        String shortName = fp.contains("/") || fp.contains("\\")
                                                ? fp.substring(Math.max(fp.lastIndexOf('/'), fp.lastIndexOf('\\')) + 1) : fp;
                                        StatusMessageManager.registerFileEditing("file_editing", shortName);
                                    }
                                    pendingLoadingMsgKey = "file_editing";
                                    break;
                                }
                                if ("code_review".equals(tn)) { pendingLoadingMsgKey = "code_review"; break; }
                                if ("search_agent".equals(tn) || "search_tool".equals(tn)) { pendingLoadingMsgKey = "searching"; break; }
                                if ("view_image".equals(tn)) { pendingLoadingMsgKey = "viewing_image"; break; }
                            }

                            // ── 工具调用处理（移到后台线程，避免阻塞EDT） ──
                            final List<com.codepal.model.ChatMessage.ToolCall> finalToolCalls = toolCalls;
                            // ★ 根因修复（view_image 卡死）：上方 cancelPendingRenderAndBump()（1921行）在
                            // "content 为空但流已启动"（如 deepseek-reasoner 回 content:""）时会递增代数以密封气泡，
                            // 若用进入回调时的 myGen 做后台校验，工具 lambda 会在下方被误判为旧轮次静默返回，
                            // 工具永不执行 → UI 卡在"工具调用中"。工具执行应绑定当前（处理完 bump 后）的代数，
                            // 后续 sendToApi/停止仍会递增代数使本轮回调正确失效。
                            final int toolGen = sendSrc.getStreamGeneration();
                            final String currentAssistantMsgIdForTools = currentAssistantMsgId;
                            final java.util.List<String> finalPendingToolPartIds = pendingToolPartIds;
                            // ★ 多标签隔离：工具执行也绑定发起时的 sessionId/WebView/SRC/ConvMgr
                            final String toolSessionId = sendSessionId;
                            final ChatWebView toolWebView = sendWebView;
                            final StreamRenderController toolSrc = sendSrc;
                            final ConversationManager toolConvMgr = sendConvMgr;
                            ThreadHelper.executeAsync(project, () -> {
                                if (!toolSrc.isCurrentGeneration(toolGen)) return;

                                // ★ 多标签隔离：使用捕获的 sessionId，而非 chatSessionManager.getCurrentSessionId()
                                final String sessionId = toolSessionId;

                                // 第一遍：非写类工具立即执行；edit_file/write_file 按 filePath 全局收集
                                LinkedHashMap<String, List<Integer>> editFileGroups = new LinkedHashMap<>();
                                boolean hasEditFile = false;

                                for (int i = 0; i < finalToolCalls.size(); i++) {
                                    // 用户已停止/新消息已发出 → 当前工具轮次失效，立即中止后续工具执行
                                    if (!toolSrc.isCurrentGeneration(toolGen)) {
                                        System.out.println("[ChatPanel] 工具轮次已失效（被停止/被新消息打断），中止剩余工具执行");
                                        break;
                                    }
                                    com.codepal.model.ChatMessage.ToolCall tc = finalToolCalls.get(i);
                                    String rawName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                                    System.out.println("工具调用：" + rawName + "\ntc:"+tc);
                                    // ── 软提醒（不停止）：连续/总量接近阈值时以 user 消息点醒模型 ──
                                    if (iterationGuard.shouldNudge()) {
                                        System.out.println("[IterationGuard] 注入软提醒：" + iterationGuard.getConsecutiveSameToolCount()
                                                + " 次相同工具 / 本轮 " + iterationGuard.getCurrentRoundToolCallCount() + " 次");
                                        iterationGuard.insertNudgeMessage();
                                    }

                                    // ── 迭代限制检查 ──
                                    if (iterationGuard.recordToolCallAndCheck(rawName)) {
                                        System.out.println("[IterationGuard] 触发迭代限制：" + iterationGuard.getLimitReason());
                                        iterationGuard.insertIterationLimitMessage();
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            toolSrc.setReceiving(false);
                                            restoreSendButton();
                                            statusLabel.setText("迭代超限已停止");
                                            statusLabel.setForeground(JBColor.ORANGE);
                                            clearStreamRefs();
                                        });
                                        break;
                                    }


                                    if ("edit_file".equals(rawName) || "write_file".equals(rawName) || "create_new_file".equals(rawName)) {
                                        String rawArgs = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                        String filePath = extractJsonString(rawArgs, "file_path");
                                        if (filePath.isBlank()) {
                                            String errMsg = "错误：缺少必需参数 file_path";
                                            System.err.println(errMsg);
                                            toolConvMgr.add(ChatMessage.toolResult(tc.getId(), rawName, errMsg));
                                            final String blankInput = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                            final String pendingPartId = finalPendingToolPartIds.get(i);
                                            ApplicationManager.getApplication().invokeLater(() -> {
                                                toolWebView.appendToolCard("参数错误", "completed", errMsg);
                                                updateToolPartResult(pendingPartId, rawName, blankInput, errMsg);
                                            });
                                            continue;
                                        }
                                        String normalizedPath = normalizeFilePath(filePath);
                                        editFileGroups.computeIfAbsent(normalizedPath, k -> new java.util.ArrayList<>()).add(i);
                                        hasEditFile = true;
                                    } else if ("todo".equals(rawName)) {
                                        final com.codepal.model.ChatMessage.ToolCall todoTc = tc;
                                        final String todoRawName = rawName;
                                        final String todoPendingPartId = finalPendingToolPartIds.get(i);
                                        String todoResult;
                                        try {
                                            todoResult = todoManager.handleTodoTool(todoTc);
                                        } catch (Throwable ex) {
                                            todoResult = "[工具执行异常] " + todoRawName + "：" + ex.getMessage();
                                            System.err.println("[ChatPanel] todo工具异常 " + ex.getMessage());
                                        }
                                        toolConvMgr.add(com.codepal.model.ChatMessage.toolResult(todoTc.getId(), todoRawName, todoResult));
                                        final String todoInput = todoTc.getFunction() != null ? todoTc.getFunction().getArguments() : "{}";
                                        final String finalTodoResult = todoResult;
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            updateToolPartResult(todoPendingPartId, todoRawName, todoInput, finalTodoResult);
                                        });
                                    } else if ("search_agent".equals(rawName)) {
                                        final com.codepal.model.ChatMessage.ToolCall searchTc = tc;
                                        final String searchRawName = rawName;
                                        final String searchPendingPartId = finalPendingToolPartIds.get(i);
                                        String finalSearchResult;
                                        String finalSearchHtml;
                                        String finalSearchToolTitle;
                                        try {
                                            String rawArgs = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                            String searchQuery = extractJsonString(rawArgs, "query");
                                            if (searchQuery.isEmpty()) {
                                                JsonObject searchArgs = parseArgs(tc);
                                                searchQuery = getArgStr(searchArgs, "query", "");
                                            }
                                            String searchToolTitle = "搜索代码: " + (searchQuery.length() > 50 ? searchQuery.substring(0, 50) + "..." : searchQuery);
                                            finalSearchToolTitle = searchToolTitle;
                                            final String toolTitleForLambda = searchToolTitle;

                                            ApplicationManager.getApplication().invokeLater(() -> {
                                                toolWebView.appendToolCard(toolTitleForLambda, "pending", "");
                                                toolWebView.appendHtmlToPendingToolCard(
                                                    "<div style='padding:4px 0;font-size:11px;opacity:0.6;'>搜索子智能体启动中...</div>");
                                            });

                                            // ── search_agent 执行（恢复原位）──
                                            com.codepal.tools.SearchAgent.SearchResult searchResult =
                                                    com.codepal.tools.SearchAgent.search(searchQuery, project,
                                                            new com.codepal.tools.SearchAgent.SearchProgressCallback() {
                                                                private int toolCallCount = 0;

                                                                @Override
                                                                public void onReasoning(String delta) {
                                                                }

                                                                @Override
                                                                public void onToolCall(String toolName, String toolArgs) {
                                                                    toolCallCount++;
                                                                    String html = getSearchToolDisplayHtml(toolName, toolArgs);
                                                                    ApplicationManager.getApplication().invokeLater(() -> {
                                                                        toolWebView.appendHtmlToPendingToolCard(html);
                                                                    });
                                                                }

                                                                @Override
                                                                public void onToolResult(String toolName, String result) {
                                                                }

                                                                @Override
                                                                public void onComplete(com.codepal.tools.SearchAgent.SearchResult result) {
                                                                }
                                                            });

                                            finalSearchResult = searchResult.toString();
                                            finalSearchHtml = searchResult.toHtml();
                                        } catch (Throwable ex) {
                                            finalSearchResult = "[工具执行异常] " + searchRawName + "：" + ex.getMessage();
                                            finalSearchHtml = finalSearchResult;
                                            finalSearchToolTitle = "搜索代码(异常)";
                                            System.err.println("[ChatPanel] search_agent工具异常 " + ex.getMessage());
                                        }

                                        // 强保证：工具结果同步提交到对话记忆（在重发请求前已进入上下文，
                                        // 不依赖 EDT 队列排序 —— 对齐 Koog「完整的回合才提交记忆」设计）
                                        toolConvMgr.add(com.codepal.model.ChatMessage.toolResult(searchTc.getId(), searchRawName, finalSearchResult));
                                        final String searchInput = searchTc.getFunction() != null ? searchTc.getFunction().getArguments() : "{}";
                                        final String finalSearchHtmlFinal = finalSearchHtml;
                                        final String finalSearchResultFinal = finalSearchResult;
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            toolWebView.finalizePendingToolCardHtml(finalSearchHtmlFinal);
                                            updateToolPartResult(searchPendingPartId, searchRawName, searchInput, finalSearchResultFinal);
                                        });
                                    } else if ("view_image".equals(rawName)) {
                                        final com.codepal.model.ChatMessage.ToolCall viewTc = tc;
                                        final String viewRawName = rawName;
                                        final String viewPendingPartId = finalPendingToolPartIds.get(i);
                                        String viewResult;
                                        String viewHtml;
                                        try {
                                            String rawArgs = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                            JsonObject viewArgs = parseArgs(tc);
                                            String imagePath = getArgStr(viewArgs, "image_path", "");
                                            if (imagePath.isEmpty()) {
                                                imagePath = extractJsonString(rawArgs, "image_path");
                                            }
                                            String question = getArgStr(viewArgs, "question", "");
                                            String viewTitle = "查看图片: " + (imagePath.length() > 50 ? imagePath.substring(0, 50) + "..." : imagePath);
                                            final String finalViewTitle = viewTitle;
                                            final String finalImagePath = imagePath;

                                            ApplicationManager.getApplication().invokeLater(() -> {
                                                toolWebView.appendToolCard(finalViewTitle, "pending", "");
                                                // ★ 立即切换当前轮播短句为 view_image 专属（覆盖 onToolCallPreparing 的 tool_executing）
                                                toolSrc.startAiStreamLoading("viewing_image");
                                            });

                                            // 走子智能体链路（VisionSubAgent → VisionClient）
                                            viewResult = toolOrchestrator.execute(viewTc, craftMode).toString();
                                            // 工具结果摘要：图片路径 + 问题（若模型问了）
                                            StringBuilder sb = new StringBuilder();
                                            sb.append("<div style='padding:4px 0;font-size:11px;opacity:0.7;'>")
                                              .append("<span style='opacity:0.6;'>图片:</span> ")
                                              .append("<span style='font-size:11px;color:#9CDCFE;font-family:Consolas,monospace;'>")
                                              .append(escapeHtml(finalImagePath)).append("</span></div>");
                                            if (!question.isEmpty()) {
                                                sb.append("<div style='padding:2px 0 4px 0;font-size:11px;opacity:0.7;'>")
                                                  .append("<span style='opacity:0.6;'>问题:</span> ")
                                                  .append(escapeHtml(question)).append("</div>");
                                            }
                                            viewHtml = sb.toString();
                                        } catch (Throwable ex) {
                                            viewResult = "[工具执行异常] " + viewRawName + "：" + ex.getMessage();
                                            viewHtml = "<div style='padding:4px 0;font-size:11px;color:#F48771;'>视觉子智能体执行异常：" + escapeHtml(ex.getMessage()) + "</div>";
                                            System.err.println("[ChatPanel] view_image 工具异常 " + ex.getMessage());
                                        }

                                        // 强保证：工具结果同步提交到对话记忆
                                        toolConvMgr.add(com.codepal.model.ChatMessage.toolResult(viewTc.getId(), viewRawName, viewResult));
                                        final String viewInput = viewTc.getFunction() != null ? viewTc.getFunction().getArguments() : "{}";
                                        final String finalViewResult = viewResult;
                                        final String finalViewHtml = viewHtml;
                                        final String finalViewPendingPartId = viewPendingPartId;
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            // ★ view_image 完成后整 body 替换（清掉"视觉子智能体启动中..."占位），区别于 search_agent 的追加
                                            toolWebView.replacePendingToolCardBody(finalViewHtml);
                                            updateToolPartResult(finalViewPendingPartId, viewRawName, viewInput, finalViewResult);
                                        });
                                    } else {
                                        // 非 edit_file/write_file/todo 工具：在后台线程执行
                                        String toolTitle = formatToolTitle(tc);
                                        final String normalToolTitle = toolTitle;
                                        final String normalPendingPartId = finalPendingToolPartIds.get(i);
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            toolWebView.appendToolCard(normalToolTitle, "pending");
                                        });
                                        // MCP 工具路由：检查是否为 MCP Server 注册的工具
                                        String result;
                                        try {
                                            if (McpService.isMcpTool(rawName)) {
                                                Map<String, Object> mcpArgs = new java.util.HashMap<>();
                                                String argsJson = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                                if (argsJson != null && !argsJson.isEmpty()) {
                                                    try {
                                                        @SuppressWarnings("unchecked")
                                                        Map<String, Object> parsed = GSON.fromJson(argsJson, Map.class);
                                                        if (parsed != null) mcpArgs = parsed;
                                                    } catch (Exception e) {
                                                        System.err.println("[MCP] Failed to parse args: " + e.getMessage());
                                                    }
                                                }
                                                result = McpService.executeTool(rawName, mcpArgs);
                                                System.out.println("[MCP] Executed " + rawName + " → " + result.length() + " chars");
                                            } else {
                                                result = toolOrchestrator.execute(tc, craftMode).toString();
                                            }
                                        } catch (Throwable ex) {
                                            // 工具执行异常也要提交结果，避免缺结果触发"工具结果缺失"占位符
                                            result = "[工具执行异常] " + rawName + "：" + ex.getMessage();
                                            System.err.println("[ChatPanel] 工具执行异常 " + rawName + "：" + ex.getMessage());
                                        }

                                        // ── Error-Agent：run_command 失败计数保护 ──
                                        if ("run_command".equals(rawName)) {
                                            if (isCommandFailed(result)) {
                                                autoFixAttempts++;
                                                if (autoFixAttempts >= MAX_AUTO_FIX_ATTEMPTS) {
                                                    result += "\n\n⚠️ 你已经连续尝试修复 " + autoFixAttempts + " 次但编译仍未通过。"
                                                            + "请停止自动修复，向用户报告你遇到的问题和已尝试的修改，让用户决定下一步。";
                                                }
                                            } else {
                                                autoFixAttempts = 0;
                                            }
                                        }

                                        final String finalResult = result;
                                        final com.codepal.model.ChatMessage.ToolCall finalTc = tc;
                                        final String finalRawName = rawName;
                                        final String toolDetail = formatToolDetail(tc, result);
                                        // 强保证：工具结果同步提交到对话记忆（在重发请求前已进入上下文）
                                        toolConvMgr.add(com.codepal.model.ChatMessage.toolResult(finalTc.getId(), finalRawName, finalResult));
                                        final String normalInput = finalTc.getFunction() != null ? finalTc.getFunction().getArguments() : "{}";
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            toolWebView.appendToolCard(normalToolTitle, "completed", toolDetail);
                                            updateToolPartResult(normalPendingPartId, finalRawName, normalInput, finalResult);
                                        });
                                    }
                                }

                                // 第二遍：对每个唯一文件，处理 edit_file / write_file
                                System.out.println("[DiffDebug] 第二遍处理前: hasEditFile=" + hasEditFile + " craftMode=" + craftMode + " editFileGroups=" + editFileGroups.keySet());
                                if (hasEditFile && !craftMode) {
                                    // ══ Plan 模式（只读）：拒绝所有 edit_file/write_file 调用 ══
                                    for (Map.Entry<String, List<Integer>> entry : editFileGroups.entrySet()) {
                                        String filePath = entry.getKey();
                                        List<Integer> indices = entry.getValue();
                                        String shortPath = filePath.contains("\\") ? filePath.substring(filePath.lastIndexOf('\\') + 1)
                                                : filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;

                                        String errMsg = "Plan 模式下不允许修改文件。请以 Markdown 代码块形式提供代码建议，或提示用户切换到 Craft 模式。";
                                        final String planErrMsg = errMsg;
                                        final String planShortPath = shortPath;
                                        ApplicationManager.getApplication().invokeLater(() -> {
                                            toolWebView.appendToolCard("编辑 " + planShortPath, "completed");
                                        });
                                        for (int tcIdx : indices) {
                                            ChatMessage.ToolCall oc = finalToolCalls.get(tcIdx);
                                            String toolName = oc.getFunction() != null ? oc.getFunction().getName() : "edit_file";
                                            toolConvMgr.add(ChatMessage.toolResult(oc.getId(), toolName, errMsg));
                                            String ocInput = oc.getFunction() != null ? oc.getFunction().getArguments() : "{}";
                                            String ocPartId = finalPendingToolPartIds.get(tcIdx);
                                            updateToolPartResult(ocPartId, toolName, ocInput, errMsg);
                                        }
                                    }
                                // Plan 模式：拒绝后继续发送给 agent（让 agent 知道不能改文件，转而文字回复）
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!toolSrc.isCurrentGeneration(toolGen)) {
                                        System.out.println("[ChatPanel] 工具轮次已失效（被停止/被新消息打断），放弃 Plan 模式重发");
                                        return;
                                    }
                                    sendToApi();
                                });
                            } else if (hasEditFile && craftMode) {
                                    System.out.println("[DiffDebug] ========================================");
                                    System.out.println("[DiffDebug] 进入 Craft 模式文件处理，editFileGroups.size=" + editFileGroups.size());
                                    // ══ Craft 模式：全部走 ToolExecutor，ChatPanel 不再解析 file_content/edits ══
                                    planCollecting = true;
                                    try {
                                        for (Map.Entry<String, List<Integer>> entry : editFileGroups.entrySet()) {
                                            String filePath = entry.getKey();
                                            List<Integer> indices = entry.getValue();
                                            System.out.println("[DiffDebug] 处理文件: " + filePath + " 工具调用数:" + indices.size());
                                            String shortPath = filePath.contains("\\") ? filePath.substring(filePath.lastIndexOf('\\') + 1)
                                                    : filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;

                                            // ── 确定文件路径与原始内容（Diff 用） ──
                                            java.io.File ioFile = new java.io.File(filePath);
                                            if (!ioFile.isAbsolute() && project.getBasePath() != null) {
                                                ioFile = new java.io.File(project.getBasePath(), filePath);
                                            }
                                            boolean fileExistedBefore = ioFile.exists();
                                            System.out.println("[DiffDebug] 文件路径(绝对): " + ioFile.getAbsolutePath() + " 执行前存在:" + fileExistedBefore);

                                            PlanFileState state = planFileStates.get(filePath);
                                            boolean firstTime = (state == null);
                                            if (state == null) {
                                                state = new PlanFileState();
                                                state.filePath = filePath;
                                                state.editCount = 0;
                                                state.isNewFile = !fileExistedBefore;
                                                // VFS读取必须在ReadAction中执行（后台线程需要显式获取读锁）
                                                final java.io.File finalIoFile = ioFile;
                                                final String finalFilePath = filePath;
                                                state.originalContent = ReadAction.compute(() -> {
                                                    try {
                                                        VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(finalIoFile);
                                                        if (vf == null && project.getBaseDir() != null) {
                                                            vf = project.getBaseDir().findFileByRelativePath(finalFilePath);
                                                        }
                                                        return (vf != null && !vf.isDirectory())
                                                                ? FileReaderUtil.readFileContent(vf) : "";
                                                    } catch (Exception e) {
                                                        System.err.println("[DiffDebug] 读取originalContent异常: " + e.getMessage());
                                                        return "";
                                                    }
                                                });
                                                System.out.println("[DiffDebug] 新文件，originalContent长度=" + state.originalContent.length());
                                            }

                                            // ── 逐个工具通过 ToolExecutor 执行（内部处理 JSON 解析 + 文件操作） ──
                                            boolean anyError = false;
                                            String firstTcName = null;
                                            for (int tcIdx : indices) {
                                                ChatMessage.ToolCall tc = finalToolCalls.get(tcIdx);
                                                String tcName = tc.getFunction() != null ? tc.getFunction().getName() : "edit_file";
                                                if (firstTcName == null) firstTcName = tcName;
                                                System.out.println("[DiffDebug] 执行工具: " + tcName);

                                                // create_new_file 文件已存在时拒绝（与旧逻辑一致）
                                                if ("create_new_file".equals(tcName) && fileExistedBefore && firstTime) {
                                                    String errMsg = "错误：文件已存在 — " + filePath + "。如需覆盖请使用 write_file。";
                                                    ApplicationManager.getApplication().invokeLater(() ->
                                                        chatWebView.appendToolCard("新建 " + shortPath, "completed", errMsg));
                                                    addMsgToConversation(ChatMessage.toolResult(tc.getId(), tcName, errMsg));
                                                    String errPartId = finalPendingToolPartIds.get(tcIdx);
                                                    updateToolPartResult(errPartId, tcName,
                                                            tc.getFunction() != null ? tc.getFunction().getArguments() : "{}", errMsg);
                                                    anyError = true;
                                                    continue;
                                                }

                                                // 调用 ToolExecutor（内部有 JSON sanitize，不会崩）
                                                String execResult;
                                                try {
                                                    execResult = com.codepal.tools.ToolExecutor.execute(tc, project, craftMode, toolConfirmManager);
                                                    System.out.println("[DiffDebug] ToolExecutor返回: " + (execResult != null ? execResult.substring(0, Math.min(100, execResult.length())) : "null"));
                                                } catch (Exception toolEx) {
                                                    System.err.println("[DiffDebug] ToolExecutor执行异常: " + toolEx.getMessage());
                                                    toolEx.printStackTrace();
                                                    execResult = "❌ 工具执行异常: " + toolEx.getMessage();
                                                }

                                                // 工具卡片标题
                                                String cardTitle;
                                                if ("write_file".equals(tcName) || "create_new_file".equals(tcName)) {
                                                    cardTitle = (fileExistedBefore ? "写入 " : "新建文件 ") + shortPath;
                                                } else {
                                                    cardTitle = "编辑 " + shortPath;
                                                }
                                                final String fCardTitle = cardTitle;
                                                final String fExecResult = execResult;
                                                final String fTcName = tcName;
                                                final String fTcArgs = tc.getFunction() != null ? tc.getFunction().getArguments() : "{}";
                                                final Integer fTcIndex = tc.getIndex();

                                                // ── 真实流式写入：若 onToolArgsDelta 期间已为该工具建好 pending 卡片，
                                                //    直接 finalize 翻 completed；否则（极快完成未赶上流式）这里补建 completed 卡片。──
                                                if (fTcIndex != null && fTcIndex.equals(streamWriteCardIndex) && writeStreamCardActive) {
                                                    writeStreamCardActive = false;
                                                    ApplicationManager.getApplication().invokeLater(() ->
                                                            chatWebView.finalizePendingToolCardHtml(
                                                                    "<div style='margin-top:4px;opacity:0.8;font-size:11px;'>" + escapeHtml(fExecResult) + "</div>"));
                                                } else {
                                                    ApplicationManager.getApplication().invokeLater(() ->
                                                            chatWebView.appendToolCard(fCardTitle, "completed", fExecResult));
                                                }

                                                addMsgToConversation(ChatMessage.toolResult(tc.getId(), tcName, execResult));
                                                String execPartId = finalPendingToolPartIds.get(tcIdx);
                                                updateToolPartResult(execPartId, tcName, fTcArgs, execResult);

                                                if (execResult.startsWith("❌") || execResult.startsWith("错误")) {
                                                    anyError = true;
                                                }
                                                state.toolCallIds.add(tc.getId());
                                            }

                                            if (anyError) {
                                                System.out.println("[DiffDebug] 文件" + filePath + "有错误，跳过diff收集");
                                                continue;
                                            }

                                            // ── 执行后重新读盘 → Diff 预览 ──
                                            state.editCount += indices.size();
                                            boolean fileExistsAfter = ioFile.exists();
                                            System.out.println("[DiffDebug] 工具执行后文件存在:" + fileExistsAfter);

                                            // VFS刷新本身是异步的，不需要ReadAction；但刷新后的查找+读取需要
                                            final java.io.File finalIoFileAfter = ioFile;
                                            final String finalFilePathAfter = filePath;
                                            final boolean finalFileExistsAfter = fileExistsAfter;

                                            // 先在非ReadAction中执行同步VFS刷新（refreshIoFiles最后一个参数为true表示同步等待）
                                            if (fileExistsAfter) {
                                                VirtualFile vfCheck = ReadAction.compute(() -> {
                                                    VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(finalIoFileAfter);
                                                    if (vf == null && project.getBaseDir() != null) {
                                                        vf = project.getBaseDir().findFileByRelativePath(finalFilePathAfter);
                                                    }
                                                    return vf;
                                                });
                                                // 如果VFS找不到但磁盘存在，先同步刷新VFS
                                                if (vfCheck == null) {
                                                    System.out.println("[DiffDebug] VFS找不到文件但磁盘存在，同步刷新VFS: " + filePath);
                                                    // async=false: 同步等待刷新完成，确保后续ReadAction能找到文件
                                                    LocalFileSystem.getInstance().refreshIoFiles(
                                                        java.util.Collections.singletonList(finalIoFileAfter), false, true, null);
                                                }
                                            }

                                            // VFS读取和内容获取必须在ReadAction中
                                            String afterContent = ReadAction.compute(() -> {
                                                try {
                                                    if (!finalFileExistsAfter) return "";
                                                    VirtualFile vfAfter = LocalFileSystem.getInstance().findFileByIoFile(finalIoFileAfter);
                                                    if (vfAfter == null && project.getBaseDir() != null) {
                                                        vfAfter = project.getBaseDir().findFileByRelativePath(finalFilePathAfter);
                                                    }
                                                    if (vfAfter != null && !vfAfter.isDirectory()) {
                                                        String content = FileReaderUtil.readFileContent(vfAfter);
                                                        System.out.println("[DiffDebug] 通过VFS读取内容成功，长度=" + content.length());
                                                        return content;
                                                    }
                                                } catch (Exception vfsEx) {
                                                    System.err.println("[DiffDebug] VFS读取失败: " + vfsEx.getMessage());
                                                }
                                                return null; // 表示VFS读取失败，走磁盘兜底
                                            });

                                            // VFS读取失败时，磁盘兜底（java.nio.file是普通IO，不需要ReadAction）
                                            if (afterContent == null) {
                                                if (ioFile.exists() && ioFile.isFile()) {
                                                    try {
                                                        afterContent = java.nio.file.Files.readString(ioFile.toPath());
                                                        System.out.println("[DiffDebug] 使用磁盘兜底读取内容成功: " + filePath + " len=" + afterContent.length());
                                                    } catch (Exception readEx) {
                                                        System.err.println("[DiffDebug] 磁盘兜底读取失败: " + readEx.getMessage());
                                                        readEx.printStackTrace();
                                                        afterContent = "";
                                                    }
                                                } else {
                                                    System.out.println("[DiffDebug] WARNING: 文件既不在VFS也不在磁盘: " + filePath);
                                                    afterContent = "";
                                                }
                                            }
                                            state.proposedContent = afterContent;
                                            planFileStates.put(filePath, state);
                                            planFilesWithCards.add(filePath);
                                            System.out.println("[DiffDebug] 文件" + filePath + "已添加到planFileStates，当前总数=" + planFileStates.size());
                                        }
                                    } catch (Exception craftEx) {
                                        System.err.println("[DiffDebug] !!! Craft模式文件处理异常: " + craftEx.getMessage());
                                        craftEx.printStackTrace();
                                    }

                                    System.out.println("[DiffDebug] 准备调用showAccumulatedDiffPanel，planFileStates.size=" + planFileStates.size());
                                    // 每轮文件编辑完成后立即刷新 Diff 面板，不等模型完全结束
                                    showAccumulatedDiffPanel();

                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!toolSrc.isCurrentGeneration(toolGen)) {
                                        System.out.println("[ChatPanel] 工具轮次已失效（被停止/被新消息打断），放弃 Craft 模式重发");
                                        return;
                                    }
                                    sendToApi();
                                });
                            }

                            // 工具执行后无需全局刷新 VFS：FileOperationService 在每次写盘时已对
                            // 单个文件调用 refreshIoFiles 做精准同步，全局递归刷新整棵项目树会触发
                            // 大量 VFS 事件，导致整个 IDE 闪烁/卡顿，故移除。

                            // 重置（不建气泡，让后续 reasoning/content 自动建）
                            toolSrc.resetStream();

                            // Plan（拒写）/ Craft（已写盘）已在分支内调用 sendToApi()；仅非文件编辑工具在此继续
                            if (!hasEditFile) {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    if (!toolSrc.isCurrentGeneration(toolGen)) {
                                        System.out.println("[ChatPanel] 工具轮次已失效（被停止/被新消息打断），放弃重发");
                                        return;
                                    }
                                    sendToApi();
                                });
                            }
                            }, null);
                        });
                    }

                    //调用消息 思考 工具链路都完成时才被调用。
                    @Override
                    public void onComplete() {
                        System.out.println("onComplete------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // 代数校验：只处理当前 stream 的回调
                            if (!sendSrc.isCurrentGeneration(myGen)) {
                                System.out.println("onComplete 代数不匹配（当前=" + sendSrc.getStreamGeneration() + " 回调=" + myGen + "），丢弃");
                                return;
                            }
                            sendSrc.setReceiving(false);
                            statusLabel.setText("就绪");
                            restoreSendButton();
                            statusLabel.setForeground(JBColor.GRAY);
                            // ★ 不变量：onComplete 必清 JS 轮播状态——防止任何路径下空响应/reasoning-only
                            //   导致 JS 端 clearStatusTimer 不触发，进而下一轮 stream 复用脏状态
                            //   （lcStreamingMsgId 未清、lcStreamFinalized 未设 → 后续消息发出去没回复）
                            sendWebView.clearStatusTimer();
                            checkCompressionHint();
                            boolean hasContent = !sendSrc.getCurrentAiRawText().isEmpty();
                            boolean hasReasoning = sendSrc.getCurrentReasoning().length() > 0;
                            // ★ DeepSeek reasoner 已知行为：模型把正文放进 reasoning_content、content 为空。
                            // 提升为正文，避免：(1) 正文误显示到「深度思考」面板；(2) 下一轮 assistant 消息
                            // content 为空被 buildChatMessagesFromParts 跳过 → 上下文丢失（见上一轮 400 根因）。
                            if (!hasContent && hasReasoning) {
                                promoteReasoningAsAnswer(sendSrc.getCurrentReasoning().toString(), sendWebView, sendConvMgr, sendSessionId);
                                clearStreamRefs();
                                maybeDeferredSwitch();
                                return;
                            }
                            if (hasContent || hasReasoning) {
                                persistPartialAssistant(sendSrc, sendConvMgr, sendSessionId);
                            }
                            // 挂上「本轮回答 token 消耗」到助手消息底部（finalize 之前，此时 lcStreamingMsgId 仍有效）
                            sendWebView.attachTokenInfo(
                                    buildAnswerTokenJson(lastPromptTokens, lastCompletionTokens));
                            // ★ 根因修复：onComplete 此前漏调 finalizeAiMessage，导致 JS 气泡停留在最后一次
                            //   renderAiStream 的快照（库里完整 / 窗口截断 / 复制按钮不显示）。
                            //   必须放在 persistPartialAssistant 之后（已落库）、clearStreamRefs 之前
                            //   （finalizeAiMessage 还要读 currentAiRawText 来渲染最终 HTML）。
                            sendSrc.finalizeAiMessage(CARD_BG());
                            clearStreamRefs();

                            // 输出完毕兜底刷新圆环：
                            // 口径与 updateTokenStats 统一——优先用真实 usage 累计（>0 说明收到过 usage，精确），
                            // 仅当后端完全不回 usage（realUsed=0，如本地 sglang 网关）时才退化为会话内容估算。
                            if (contextCircle != null) {
                                long usedCtx = usedContextTokens();
                                contextCircle.setTokens(usedCtx);
                                String tip = String.format(
                                    "<html>当前上下文：%s / %s（%.0f%%）<br><br>" +
                                        "<b>会话累计：</b><br>" +
                                        "输入：%s（命中 %s / 未命中 %s）<br>" +
                                        "输出：%s<br>" +
                                        "预估费用：%s<br><br>" +
                                        "<small>点击压缩对话历史</small></html>",
                                    formatTokenCount(usedCtx),
                                    formatTokenCount(contextCircle.getMaxContextTokens()),
                                    usedCtx * 100.0 / Math.max(1, contextCircle.getMaxContextTokens()),
                                    formatTokenCount(sessionPromptTokens),
                                    formatTokenCount(sessionCacheHitTokens),
                                    formatTokenCount(sessionCacheMissTokens),
                                    formatTokenCount(sessionCompletionTokens),
                                    computeCostStr(sessionCacheHitTokens, sessionCacheMissTokens,
                                        sessionCompletionTokens, selectedModelName()));
                                contextCircle.setToolTipText(tip);
                            }

                            // Craft 模式：agent 已完成所有工作（无 tool_calls 的最终回复），展示累积的所有 diff
                            if (planCollecting && !planFileStates.isEmpty()) {
                                planCollecting = false;
                                showAccumulatedDiffPanel();
                            }
                            // 流结束：执行被延迟的会话切换（用户在本轮流进行中点了其它会话标签）
                            maybeDeferredSwitch();
                        });
                    }

                    @Override
                    public void onUsage(com.codepal.model.ChatResponse.Usage usage) {
                        System.out.println("onUsage------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) return;
                            updateTokenStats(usage);
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println("onError------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!sendSrc.isCurrentGeneration(myGen)) {
                                System.out.println("onError 代数不匹配，丢弃（旧 stream 错误）");
                                return;
                            }
                            sendSrc.setReceiving(false);
                            restoreSendButton();
                            statusLabel.setText("出错");
                            statusLabel.setForeground(JBColor.RED);
                            // ★ 不变量：onError 也必清 JS 轮播（与 onComplete 对齐，防止脏状态）
                            sendWebView.clearStatusTimer();
                            sendSrc.appendError(error.getMessage());
                            // ★ 持久化：与 onComplete 一致，避免「出错丢失消息」（错误信息已拼进 rawText 随 text part 落库）
                            persistPartialAssistant(sendSrc, sendConvMgr, sendSessionId);
                            sendSrc.finalizeAiMessage(CARD_BG());
                            clearStreamRefs();
                            // 流结束：执行被延迟的会话切换
                            maybeDeferredSwitch();
                            // 出错时不清除累积状态，已暂存的文件修改保留在面板中
                        });
                    }
        }; // return the StreamCallback
    }

    /**
     * DeepSeek reasoner 偶发把正文放进 reasoning_content、content 为空时的兜底：
     * 将 reasoning 内容提升为 assistant 的正式 content（既正确显示，又保住下一轮上下文）。
     * 同时把「深度思考」面板的内容移动到正文气泡。
     */
    private void promoteReasoningAsAnswer(String reasoningText,
                                         ChatWebView webView,
                                         ConversationManager convMgr,
                                         String sessionId) {
        ChatMessage assistant = new ChatMessage("assistant", reasoningText);
        convMgr.add(assistant);

        String msgId = DBChatHistoryRepository.getUUID();
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(msgId);
        entity.setSessionId(sessionId);
        entity.setRole(Constant.ROLE_assistant);
        entity.setQaRound(chatSessionManager.getCurrentQaRound());
        entity.setCreatedAt(System.currentTimeMillis());
        entity.setUpdatedAt(System.currentTimeMillis());

        java.util.List<com.codepal.model.MessagePartEntity> parts = new java.util.ArrayList<>();
        parts.add(com.codepal.model.MessagePartEntity.text(msgId, sessionId, reasoningText, 0));
        chatSessionManager.persistMessageWithParts(entity, parts);

        webView.promoteReasoningToAnswer(MarkdownUtil.toHtmlFragment(reasoningText), reasoningText);
    }

    /**
     * 生成工具卡片可读标题：编辑了 xxx / 读取了 xxx / 搜索了 xxx 等
     */
    private static String formatToolTitle(ChatMessage.ToolCall tc) {
        String name = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
        try {
            JsonObject args = tc.getFunction().getArguments() != null
                    ? JsonParser.parseString(tc.getFunction().getArguments()).getAsJsonObject()
                    : new JsonObject();
            String filePath = args.has("file_path") ? args.get("file_path").getAsString() : null;
            String symbol = args.has("symbol") ? args.get("symbol").getAsString() : null;
            String keyword = args.has("keyword") ? args.get("keyword").getAsString() : null;
            String command = args.has("command") ? args.get("command").getAsString() : null;
            String dirPath = args.has("dir_path") ? args.get("dir_path").getAsString() : null;
            String startLine = args.has("start_line") ? args.get("start_line").getAsString() : null;
            String endLine = args.has("end_line") ? args.get("end_line").getAsString() : null;

            if (filePath != null) {
                String shortPath = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
                shortPath = shortPath.contains("\\") ? shortPath.substring(shortPath.lastIndexOf('\\') + 1) : shortPath;
                String lineInfo = "";
                if ("read_file_range".equals(name) && (startLine != null || endLine != null)) {
                    String s = startLine != null ? startLine : "1";
                    String e = endLine != null ? endLine : "End";
                    lineInfo = " L" + s + "-" + e;
                }
                return formatToolTitleWithFile(name, shortPath) + lineInfo;
            }
            if (command != null) {
                String shortCmd = command.length() > 50 ? command.substring(0, 50) + "..." : command;
                shortCmd = shortCmd.replace("\n", " ");
                return "执行 " + shortCmd;
            }
            if (dirPath != null) {
                String shortDir = dirPath.length() > 40 ? "..." + dirPath.substring(dirPath.length() - 40) : dirPath;
                return "浏览 " + shortDir;
            }
            if (symbol != null) {
                String shortSym = symbol.length() > 40 ? symbol.substring(0, 40) + "..." : symbol;
                return "查找 " + shortSym;
            }
            if (keyword != null) {
                String shortKw = keyword.length() > 50 ? keyword.substring(0, 50) + "..." : keyword;
                return "搜索 " + shortKw;
            }
        } catch (Exception ignored) {}
        return formatToolTitle(name);
    }

    /**
     * 工具名 + 参数(JSON) → 富标题（含文件名/行号），复用实时卡片的 formatToolTitle(tc)，
     * 使历史回显与实时渲染完全一致：read_file_range → "读取 A.java L1502-1766"。
     */
    private static String formatToolTitleWithArgs(String toolName, String argsJson) {
        ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
        ChatMessage.ToolCall.Function fn = new ChatMessage.ToolCall.Function();
        fn.setName(toolName);
        fn.setArguments(argsJson != null ? argsJson : "{}");
        tc.setFunction(fn);
        return formatToolTitle(tc);
    }

    /** 仅工具名 → 中文动词 */
    private static String formatToolTitle(String toolName) {
        return switch (toolName) {
            case "run_command" -> "执行命令";
            case "edit_file" -> "编辑文件";
            case "write_file" -> "写入文件";
            case "create_new_file" -> "新建文件";
            case "delete_file" -> "删除文件";
            case "read_file_range" -> "读取";
            case "list_files" -> "浏览目录";
            case "view_file_outline" -> "查看大纲";
            case "locate_code_by_symbol" -> "查找符号";
            case "search_tool" -> "搜索代码";
            case "todo" -> "任务计划";
            case "ask_user_question" -> "向用户提问";
            case "load_skill" -> "加载技能";
            case "code_review" -> "代码审查";
            case "validate_code" -> "验证代码";
            case "search_agent" -> "搜索代码";
            case "create_directory" -> "创建目录";
            default -> toolName;
        };
    }

    /** 工具名 + 文件名 → 中文动词 + 文件名 */
    private static String formatToolTitleWithFile(String toolName, String shortPath) {
        return switch (toolName) {
            case "edit_file" -> "编辑 " + shortPath;
            case "write_file" -> "写入 " + shortPath;
            case "create_new_file" -> "新建 " + shortPath;
            case "delete_file" -> "删除 " + shortPath;
            case "read_file_range" -> "读取 " + shortPath;
            case "view_file_outline" -> "查看大纲 " + shortPath;
            default -> toolName + " " + shortPath;
        };
    }

    /** 生成工具卡片详情文本（参数 + 结果摘要） */
    private static String formatToolDetail(ChatMessage.ToolCall tc, String result) {
        StringBuilder sb = new StringBuilder();
        String name = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
        JsonObject args = parseArgs(tc);

        switch (name) {
            case "run_command":
                sb.append("命令：").append(getArgStr(args, "command", "")).append("\n\n");
                sb.append("输出：\n").append(result != null ? result : "");
                break;
            case "list_files":
                sb.append("目录：").append(getArgStr(args, "path", ".")).append("\n\n");
                sb.append("结果：\n").append(result != null ? result : "");
                break;
            case "search_tool":
                sb.append("关键词：").append(getArgStr(args, "keyword", "")).append("\n\n");
                sb.append("结果：\n").append(result != null ? result : "");
                break;
            case "read_file_range":
                sb.append("文件：").append(getArgStr(args, "file_path", "")).append("\n");
                String start = getArgStr(args, "start_line", "");
                String end = getArgStr(args, "end_line", "");
                if (!start.isEmpty() || !end.isEmpty()) {
                    sb.append("行号：").append(start).append("-").append(end).append("\n");
                }
                sb.append("\n内容：\n").append(result != null ? result : "");
                break;
            case "view_file_outline":
                sb.append("文件：").append(getArgStr(args, "file_path", "")).append("\n\n");
                sb.append("大纲：\n").append(result != null ? result : "");
                break;
            case "locate_code_by_symbol":
                sb.append("符号：").append(getArgStr(args, "symbol", "")).append("\n\n");
                sb.append("结果：\n").append(result != null ? result : "");
                break;
            case "edit_file":
            case "write_file":
            case "create_new_file":
            case "delete_file":
                sb.append("文件：").append(getArgStr(args, "file_path", "")).append("\n\n");
                sb.append("结果：").append(result != null ? result : "");
                break;
            default:
                sb.append("参数：").append(args.toString()).append("\n\n");
                sb.append("结果：").append(result != null ? result : "");
                break;
        }
        String detail = sb.toString();
        if (detail.length() > 10000) {
            detail = detail.substring(0, 10000) + "\n\n... (内容过长，已截断)";
        }
        return detail;
    }

    /**
     * 搜索子智能体工具显示名（HTML格式）
     */
    private static String getSearchToolDisplayHtml(String toolName, String toolArgs) {
        try {
            JsonObject args = JsonParser.parseString(toolArgs).getAsJsonObject();
            String icon;
            String label;
            String value;
            switch (toolName) {
                case "search_tool":
                    icon = "🔍";
                    label = "搜索关键词";
                    value = getArgStr(args, "keyword", "");
                    break;
                case "locate_code_by_symbol":
                    icon = "📍";
                    label = "定位符号";
                    value = getArgStr(args, "symbol", "");
                    break;
                case "list_files":
                    icon = "📂";
                    label = "浏览目录";
                    value = getArgStr(args, "path", ".");
                    break;
                case "view_file_outline":
                    icon = "📋";
                    label = "查看大纲";
                    value = getArgStr(args, "file_path", "");
                    break;
                case "read_file_range": {
                    icon = "📖";
                    label = "读取文件";
                    String path = getArgStr(args, "file_path", "");
                    String start = getArgStr(args, "start_line", "");
                    String end = getArgStr(args, "end_line", "");
                    value = path + (start.isEmpty() ? "" : " (L" + start + "-L" + end + ")");
                    break;
                }
                default:
                    icon = "⚙️";
                    label = toolName;
                    value = "";
            }
            return "<div style='padding:6px 0 2px 0;'>"
                    + "<span style='margin-right:4px;'>" + icon + "</span>"
                    + "<span style='font-size:11px;opacity:0.6;'>" + escapeHtml(label) + ":</span> "
                    + "<span style='font-size:11px;color:#9CDCFE;font-family:Consolas,monospace;'>" + escapeHtml(value) + "</span>"
                    + "</div>";
        } catch (Exception e) {
            return "<div style='padding:6px 0;'>⚙️ " + escapeHtml(toolName) + "</div>";
        }
    }

    /**
     * 搜索子智能体工具结果摘要（HTML格式，简洁版）
     */
    private static String formatSearchToolResultHtml(String toolName, String result) {
        if (result == null || result.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='padding-left:20px;font-size:11px;opacity:0.7;'>");
        switch (toolName) {
            case "search_tool":
            case "locate_code_by_symbol": {
                String[] lines = result.split("\n");
                int count = 0;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("找到") || trimmed.startsWith("共找到") || trimmed.contains("匹配")) continue;
                    if (trimmed.contains(":") && !trimmed.startsWith(" ")) {
                        int colonIdx = trimmed.indexOf(':');
                        if (colonIdx > 0 && colonIdx < 200) {
                            String path = trimmed.substring(0, colonIdx).trim();
                            if (path.length() > 5 && (path.contains("/") || path.contains("\\"))) {
                                sb.append("<div style='font-family:Consolas,monospace;'>").append(escapeHtml(path)).append("</div>");
                                count++;
                                if (count >= 12) {
                                    sb.append("<div style='opacity:0.5;'>... 还有更多结果</div>");
                                    break;
                                }
                            }
                        }
                    }
                }
                if (count == 0) sb.append("<div>未找到匹配结果</div>");
                break;
            }
            case "list_files": {
                String[] lines = result.split("\n");
                int count = 0;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("目录") || trimmed.startsWith("共")) continue;
                    if (!trimmed.startsWith(" ")) {
                        sb.append("<div>").append(escapeHtml(trimmed)).append("</div>");
                        count++;
                        if (count >= 15) {
                            sb.append("<div style='opacity:0.5;'>... 还有更多</div>");
                            break;
                        }
                    }
                }
                break;
            }
            case "view_file_outline": {
                String[] lines = result.split("\n");
                int count = 0;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.startsWith("文件") || trimmed.startsWith("---")) continue;
                    sb.append("<div>").append(escapeHtml(trimmed)).append("</div>");
                    count++;
                    if (count >= 15) {
                        sb.append("<div style='opacity:0.5;'>...</div>");
                        break;
                    }
                }
                break;
            }
            case "read_file_range":
                int len = result.length();
                sb.append("<div>已读取 ").append(len).append(" 字符</div>");
                break;
            default:
                break;
        }
        sb.append("</div>");
        return sb.toString();
    }

    // ── 流式写入容错提取：参数 JSON 在传输中不完整，需安全取「已闭合」的字段值 ──

    /** 从已收参数原始片段中，容错提取 file_path（用于卡片标题） */
    private static String extractStreamFileName(String raw) {
        String[] keys = {"file_path"};
        for (String key : keys) {
            int i = raw.indexOf("\"" + key + "\"");
            if (i < 0) continue;
            int q = raw.indexOf('"', i + key.length() + 2);
            if (q < 0) continue;
            StringBuilder sb = new StringBuilder();
            int j = q + 1;
            while (j < raw.length()) {
                char c = raw.charAt(j);
                if (c == '\\') {
                    if (j + 1 < raw.length()) { sb.append(raw.charAt(j + 1)); j += 2; } else break;
                } else if (c == '"') {
                    break;
                } else { sb.append(c); j++; }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 从已收参数原始片段中，容错提取已生成的 file_content / content 文本。
     * 即使 JSON 未闭合，也能返回「已扫描到的已闭合部分」，供流式铺卡。
     * 返回 null 表示尚未出现写入字段。
     */
    private static String extractStreamFileContent(String raw) {
        String[] keys = {"file_content", "content"};
        for (String key : keys) {
            int i = raw.indexOf("\"" + key + "\"");
            if (i < 0) continue;
            int q = raw.indexOf('"', i + key.length() + 2);
            if (q < 0) continue;
            StringBuilder sb = new StringBuilder();
            int j = q + 1;
            while (j < raw.length()) {
                char c = raw.charAt(j);
                if (c == '\\') {
                    if (j + 1 < raw.length()) {
                        char n = raw.charAt(j + 1);
                        if (n == 'n') sb.append('\n');
                        else if (n == 't') sb.append('\t');
                        else if (n == 'r') sb.append('\r');
                        else if (n == '"') sb.append('"');
                        else if (n == '\\') sb.append('\\');
                        else if (n == '/') sb.append('/');
                        else sb.append(n);
                        j += 2;
                    } else break; // 末尾转义不完整，停止
                } else if (c == '"') {
                    break; // 值已闭合
                } else {
                    sb.append(c);
                    j++;
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * edit_file 流式预览：从已收参数原始片段中容错抽取 search→replace 编辑对。
     * 即使 JSON 未闭合，也能返回已「完整闭合」的 search/replace 对（不完整字段先不展示，等待闭合）。
     * 单对模式 {"search":..,"replace":..} 与批量模式 {"edits":[{search,replace}..]} 均支持。
     */
    private static java.util.List<String[]> extractStreamEditPairs(String raw) {
        java.util.List<String[]> pairs = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < raw.length()) {
            int si = raw.indexOf("\"search\"", pos);
            if (si < 0) break;
            StreamVal s = readStreamString(raw, si + 8); // 8 = len("search")
            if (s == null || s.value == null) break;     // search 值尚未开始
            if (s.end < 0) break;                        // search 仍在流式（未闭合），等下一帧
            int ri = raw.indexOf("\"replace\"", s.end);
            if (ri < 0) break;                           // replace 尚未出现
            StreamVal r = readStreamString(raw, ri + 9); // 9 = len("replace")
            if (r == null || r.value == null) break;     // replace 值尚未开始
            pairs.add(new String[]{s.value, r.value});
            pos = (r.end > 0) ? r.end : ri + 9;
        }
        return pairs;
    }

    /** 把编辑对渲染成 diff 风格纯文本（- 删除 / + 新增），供 <pre> textContent 整块展示 */
    private static String buildEditPreviewText(java.util.List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) sb.append('\n');
            String[] p = pairs.get(i);
            for (String line : p[0].split("\n", -1)) sb.append("- ").append(line).append('\n');
            for (String line : p[1].split("\n", -1)) sb.append("+ ").append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从已收 JSON 片段的某个 key 之后，容错读取字符串值。
     * 返回 value=null 表示值尚未开始（key 后还没到 "）；end=-1 表示值未闭合（到片段末尾仍缺闭合引号）。
     */
    private static StreamVal readStreamString(String raw, int keyEnd) {
        int colon = raw.indexOf(':', keyEnd);
        if (colon < 0) return null;
        int q = raw.indexOf('"', colon + 1);
        if (q < 0) return null;
        StringBuilder sb = new StringBuilder();
        int j = q + 1;
        while (j < raw.length()) {
            char c = raw.charAt(j);
            if (c == '\\') {
                if (j + 1 < raw.length()) {
                    char n = raw.charAt(j + 1);
                    if (n == 'n') sb.append('\n');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'r') sb.append('\r');
                    else sb.append(n);
                    j += 2;
                } else break; // 末尾转义不完整，停止
            } else if (c == '"') {
                return new StreamVal(sb.toString(), j + 1); // 已闭合
            } else {
                sb.append(c);
                j++;
            }
        }
        return new StreamVal(sb.toString(), -1); // 到末尾未闭合，视为部分值
    }

    /** readStreamString 的返回值载体 */
    private static final class StreamVal {
        final String value; // 字符串值（可能为部分值）；null 表示值尚未开始
        final int end;      // 闭合引号后的位置；-1 表示未闭合
        StreamVal(String v, int e) { value = v; end = e; }
    }

    /** 安全读取文件原始内容（用于 edit_file 流式预览），异常/超大时返回空串 */
    private String readFileContentSafe(String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            java.io.File f = com.codepal.tools.FileOperationService.resolveFilePath(path, project);
            if (f == null || !f.exists() || !f.isFile()) return "";
            if (f.length() > 2_000_000) return "";
            return com.codepal.tools.FileOperationService.readFile(path, project);
        } catch (Exception e) {
            return "";
        }
    }

    /** 统计文件行数（用于写入卡片头部 −行数统计）；文件不存在/超大/异常时返回 0，避免阻塞 EDT */
    private int countFileLines(String path) {
        if (path == null || path.isEmpty()) return 0;
        try {
            java.io.File f = com.codepal.tools.FileOperationService.resolveFilePath(path, project);
            if (f == null || !f.exists() || !f.isFile()) return 0;
            if (f.length() > 2_000_000) return 0; // 跳过超大文件
            try (java.util.stream.Stream<String> s = java.nio.file.Files.lines(f.toPath())) {
                return (int) s.count();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ── 工具参数解析辅助 ──
    private static JsonObject parseArgs(ChatMessage.ToolCall tc) {
        String args = tc.getFunction() != null ? tc.getFunction().getArguments() : null;
        String name = tc.getFunction() != null ? tc.getFunction().getName() : "?";
        if (args == null || args.isEmpty()) return new JsonObject();
        try {
            return JsonParser.parseString(args).getAsJsonObject();
        } catch (Exception e) {
            // 严格解析失败时，尝试修复JSON中未转义的控制字符后重试
            System.err.println("[ChatPanel] parseArgs 严格解析失败 tool=" + name
                    + " error=" + e.getMessage() + "，尝试sanitize后重试");
            try {
                String sanitized = sanitizeJsonControlChars(args);
                return JsonParser.parseString(sanitized).getAsJsonObject();
            } catch (Exception e2) {
                System.err.println("[ChatPanel] parseArgs sanitize后仍失败 tool=" + name
                        + " args前200字符=" + (args.length() > 200 ? args.substring(0, 200) + "..." : args)
                        + " error2=" + e2.getMessage());
                return new JsonObject();
            }
        }
    }

    /**
     * 修复JSON字符串中未转义的控制字符（换行/回车/制表等）。
     * 遍历时跟踪是否在字符串值内部，将字面控制字符重新转义。
     */
    private static String sanitizeJsonControlChars(String json) {
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
    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) return "";
        String search = '"' + key + '"';
        int k = json.indexOf(search);
        if (k < 0) return "";
        int c = json.indexOf(':', k + search.length());
        if (c < 0) return "";
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        return q2 < 0 ? "" : json.substring(q1 + 1, q2);
    }

    private static String getArgStr(JsonObject a, String k, String d) {
        return (a.has(k) && !a.get(k).isJsonNull()) ? a.get(k).getAsString() : d;
    }

    private static int getArgInt(JsonObject a, String k, int d) {
        if (a.has(k) && !a.get(k).isJsonNull()) {
            try {
                return a.get(k).getAsInt();
            } catch (Exception e) {
                return d;
            }
        }
        return d;
    }

    /** 标准化文件路径：统一斜杠、解析为绝对路径（相对于项目根）、去除末尾斜杠 */
    private String normalizeFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return filePath;
        String p = filePath.replace('\\', '/').trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        java.io.File f = new java.io.File(p);
        if (!f.isAbsolute() && project != null && project.getBasePath() != null) {
            f = new java.io.File(project.getBasePath(), p);
        }
        try {
            return f.getCanonicalPath().replace('\\', '/');
        } catch (Exception e) {
            return f.getAbsolutePath().replace('\\', '/');
        }
    }

    /**
     * 判断 run_command 的输出结果是否表示命令执行失败（Exit code 非0）
     */
    private static boolean isCommandFailed(String result) {
        if (result == null) return false;
        if (result.contains("❌ 执行失败")) return true;
        // 匹配 "Exit code: N" 格式，N != 0 表示失败
        int idx = result.indexOf("Exit code:");
        if (idx >= 0) {
            int start = idx + "Exit code:".length();
            int end = start;
            while (end < result.length() && (Character.isDigit(result.charAt(end)) || result.charAt(end) == '-')) end++;
            if (end > start) {
                try {
                    int code = Integer.parseInt(result.substring(start, end).trim());
                    return code != 0;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 文件修改列表面板（Plan 模式）— 类似 CodeBuddy 的文件修改展示区
    // ═══════════════════════════════════════════════════════════════════════

    // ── Plan 模式跨轮次累积状态 ──
    /** Plan 模式正在累积修改中（agent 尚未完成所有工具调用） */
    private boolean planCollecting = false;
    /** Todo 完成清单是否已追加到消息末尾（避免重复追加） */
    private boolean todoCompletionSummaryAppended = false;

    /** 单个文件在 Plan 模式下的累积状态 */
    private static class PlanFileState {
        String filePath;
        String originalContent;
        String proposedContent;
        int editCount;
        boolean isNewFile;
        final List<String> toolCallIds = new ArrayList<>();
    }

    /** 按文件路径保存累积的 Plan 状态（有序，保持文件出现顺序） */
    private final LinkedHashMap<String, PlanFileState> planFileStates = new LinkedHashMap<>();

    /** 已在 UI 中添加了 pending 工具卡片的文件集合（避免重复卡片） */
    private final Set<String> planFilesWithCards = new HashSet<>();

    // ── Error-Agent 自动修复保护 ──
    /** 自动修复最大重试次数 */
    private static final int MAX_AUTO_FIX_ATTEMPTS = 3;
    /** 当前轮次连续命令失败次数（用于防止无限修复循环） */
    private int autoFixAttempts = 0;

    /**
     * 展示/更新 Plan 模式跨轮次累积的文件修改列表面板。
     * 追加新文件/更新已有文件而非重建。已保留/已撤销的文件记录保留在列表中；
     * 若同一文件被再次修改，会重新标记为待处理状态。
     */
    private void showAccumulatedDiffPanel() {
        System.out.println("[DiffDebug] showAccumulatedDiffPanel called, planFileStates.size=" + planFileStates.size());
        if (planFileStates.isEmpty()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            System.out.println("[DiffDebug] EDT: upserting " + planFileStates.size() + " changes");
            for (PlanFileState state : planFileStates.values()) {
                System.out.println("[DiffDebug] upsertChange: " + state.filePath + " originalLen=" + 
                    (state.originalContent != null ? state.originalContent.length() : -1) + 
                    " proposedLen=" + (state.proposedContent != null ? state.proposedContent.length() : -1) +
                    " isNewFile=" + state.isNewFile);
                taskDiffTabPanel.upsertChange(state.filePath, state.originalContent, state.proposedContent, state.isNewFile);
            }
            taskDiffTabPanel.expandAndFocusFirstPending();
            refreshCenterLayout();
            System.out.println("[DiffDebug] after refreshCenterLayout, taskDiffTabPanel visible=" + 
                taskDiffTabPanel.getComponent().isVisible() + " size=" + taskDiffTabPanel.getComponent().getSize());
        });
    }

    /** 刷新 centerLayer + inputPanel 布局，确保浮动面板正确定位 */
    private void refreshCenterLayout() {
        if (centerLayer != null) {
            // 第一次：标记整个组件树为无效并验证
            centerLayer.invalidate();
            centerLayer.validate();
            // 第二次：使用 invokeLater 确保所有待处理的 Swing 事件（setVisible、setPreferredSize 等）
            // 都处理完毕后，再手动调用一次 doLayout()，保证布局绝对正确
            SwingUtilities.invokeLater(() -> {
                if (centerLayer != null) {
                    centerLayer.invalidate();
                    centerLayer.doLayout();
                    centerLayer.repaint();
                }
            });
            System.out.println("[DiffDebug] refreshCenterLayout: centerLayer.size=" + centerLayer.getSize() +
                " taskDiffTabPanel.size=" + taskDiffTabPanel.getComponent().getSize() +
                " taskDiffTabPanel.preferredSize=" + taskDiffTabPanel.getComponent().getPreferredSize() +
                " taskDiffTabPanel.visible=" + taskDiffTabPanel.getComponent().isVisible());
        }
        if (inputPanel != null) {
            inputPanel.revalidate();
            inputPanel.repaint();
        }
    }

    /** 用户点击 × 关闭按钮时隐藏面板 */
    private void handlePanelClose() {
        taskDiffTabPanel.closeChangePanel();
    }

    /**
     * 单个文件被用户保留：文件已写入磁盘，保留记录在变更列表中（显示"已保留"状态）。
     * 不从planFileStates移除，以便用户始终能看到所有变更历史。
     */
    private void handleFileAccepted(String filePath, String newContent) {
        ThreadHelper.runOnUi(project, () -> {
            // 保留文件在planFileStates中（标记为已处理即可，不用移除）
            planFilesWithCards.remove(filePath);
        });
    }

    /**
     * 单个文件被用户撤销：恢复磁盘上的文件到原始状态。
     * - 如果是新建文件：删除它
     * - 如果是已有文件：恢复 originalContent
     * 保留记录在变更列表中（显示"已撤销"状态），不从planFileStates移除。
     */
    private void handleFileRejected(String filePath) {
        ThreadHelper.runOnUi(project, () -> {
            PlanFileState state = planFileStates.get(filePath);
            planFilesWithCards.remove(filePath);

            String shortPath = filePath.contains("\\") ? filePath.substring(filePath.lastIndexOf('\\') + 1)
                    : filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            chatWebView.appendToolCard("编辑 " + shortPath + " (已撤销)", "completed");

            if (state == null) return;

            try {
                if (state.isNewFile && (state.originalContent == null || state.originalContent.isEmpty())) {
                    FileOperationService.deleteFile(filePath, project);
                } else {
                    FileOperationService.writeFile(filePath, state.originalContent, project);
                }
            } catch (Exception ex) {
                System.err.println("[Plan] 撤销修改失败: " + filePath + " - " + ex.getMessage());
            }
        });
    }

    /**
     * 用户手动从变更列表中移除一个文件记录（点击X按钮）。
     * 这才真正从planFileStates中移除。
     */
    private void handleFileRemoved(String filePath) {
        ThreadHelper.runOnUi(project, () -> {
            planFileStates.remove(filePath);
            planFilesWithCards.remove(filePath);
        });
    }

    /**
     * 所有待处理文件都已处理完毕（全部保留或撤销）：刷新 VFS。
     * 注意：不清空 planFileStates，保留所有变更记录供用户查看。
     */
    private void handleAllChangesResolved() {
        ThreadHelper.runOnUi(project, () -> {
            com.intellij.openapi.vfs.VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                com.intellij.openapi.vfs.newvfs.RefreshQueue.getInstance()
                        .refresh(true, true, null, baseDir);
            }
            planFilesWithCards.clear();
            planCollecting = false;
        });
    }

    /** 强制清除 Plan 状态（用于切换会话、新建会话等场景） */
    private void resetPlanState() {
        planFileStates.clear();
        planFilesWithCards.clear();
        planCollecting = false;
        autoFixAttempts = 0;
        todoCompletionSummaryAppended = false;
        todoManager.clearUiOnly(); // 只清 UI，不落库（避免误删旧会话待办；新会话由 bindSession 回填）
        taskDiffTabPanel.resetChanges();
        taskDiffTabPanel.clearTodos();
        policyEngine.clearSessionTrusted();
        toolConfirmManager.clearAllPending();
    }

    /**
     * 取当前选中的模型名（用于费用单价判断）
     */
    private String selectedModelName() {
        Object selObj = modelCombo != null ? modelCombo.getSelectedItem() : null;
        if (selObj instanceof ModelComboItem) {
            return ((ModelComboItem) selObj).name;
        }
        return null;
    }

    /** 根据模型与四类 token 计算预估费用字符串（元） */
    private String computeCostStr(long hit, long miss, long out, String modelName) {
        double hitPrice, missPrice, outPrice;
        if ("deepseek-v4-pro".equals(modelName)) {
            hitPrice  = 0.1  / 1_000_000.0;
            missPrice = 12.0 / 1_000_000.0;
            outPrice  = 24.0 / 1_000_000.0;
        } else {
            hitPrice  = 0.02 / 1_000_000.0;
            missPrice = 1.0  / 1_000_000.0;
            outPrice  = 2.0  / 1_000_000.0;
        }
        double totalCost = hit * hitPrice + miss * missPrice + out * outPrice;
        return totalCost < 0.0001
                ? String.format("¥%.2e", totalCost)
                : String.format("¥%.4f", totalCost);
    }

    /**
     * 基于会话实际内容估算当前上下文使用量（token）。
     * 后端（尤其本地 sglang 网关）经常不回 usage / 不回 prompt_tokens，
     * 导致依赖 usage 的圆环统计不动。这里改用会话历史文本长度估算，
     * 作为圆环"使用量"的可靠口径：中英文混合按 ~1.6 字符/token 估算。
     */
    private long estimateSessionTokens() {
        if (conversationManager == null) return 0;
        long chars = 0;
        for (com.codepal.model.ChatMessage m : conversationManager.getMessages()) {
            String c = m.getContent();
            if (c != null) chars += c.length();
            String r = m.getReasoning_content();
            if (r != null) chars += r.length();
        }
        return Math.max(0, chars / 16 * 10); // ≈ chars / 1.6
    }

    /**
     * 启发式 token 估算（展示级精度，对齐 codepal-desktop TokenMeter）。
     * 中文/日文约 1.6 token/字符，西文与代码约 0.25 token/字符（≈4 字符/token）。
     */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        double other = 0;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            if (code >= 0x4e00 && code <= 0x9fff) cjk++;
            else if (code >= 0x3000 && code <= 0x30ff) cjk++;
            else if (code == '\n' || code == '\t') other += 0.25;
            else if (code == ' ') other += 0.1;
            else other++;
            i += Character.charCount(code);
        }
        return Math.max(0, Math.round((float) (cjk * 1.6 + other * 0.25)));
    }

    /** 判断工具是否为子智能体（独立 LLM 调用，非普通工具） */
    private static boolean isSubAgent(String toolName) {
        if (toolName == null) return false;
        return "search_agent".equals(toolName) || "code_review".equals(toolName);
    }

    /**
     * 当前上下文使用量（token）。
     * 优先用后端回传的精确 usage 累计（输入+输出）；后端不回 usage 时退化为基于会话内容的估算。
     * 与 updateTokenStats / ContextCircleProgress 同一口径。
     */
    private long usedContextTokens() {
        // 圆环口径 = 当前上下文窗口用量 = 最近一次请求的 prompt(整段对话) + completion(最近一轮回复)，
        // 不是跨轮累加值（每次请求都重发整段对话，累加会把同一份上下文数倍重复计数 → 虚假爆满）。
        long snap = lastPromptTokens + lastCompletionTokens;
        return snap > 0 ? snap : estimateSessionTokens();
    }

    private void updateTokenStats(com.codepal.model.ChatResponse.Usage usage) {
        if (usage == null) return;

        // 费用统计：跨轮累加（每次请求都重发整段对话，这就是真实消耗）
        sessionPromptTokens     += usage.getPromptTokens();
        sessionCompletionTokens += usage.getCompletionTokens();
        sessionCacheHitTokens   += usage.getPromptCacheHitTokens();
        sessionCacheMissTokens  += usage.getPromptCacheMissTokens();

        // 上下文快照：只取最近一次请求的值（用于圆环，不能累加）
        lastPromptTokens     = usage.getPromptTokens();
        lastCompletionTokens = usage.getCompletionTokens();
        // 缓存命中/未命中快照：也取最近一次请求的值（token 面板"本次回答"口径）
        lastCacheHitTokens   = usage.getPromptCacheHitTokens();
        lastCacheMissTokens  = usage.getPromptCacheMissTokens();

        // 根据当前选择的模型确定单价（元 / token）
        String model = null;
        Object selObj = modelCombo.getSelectedItem();
        if (selObj instanceof ModelComboItem) {
            model = ((ModelComboItem) selObj).name;
        }
        String costStr = computeCostStr(sessionCacheHitTokens, sessionCacheMissTokens,
                sessionCompletionTokens, model);

        // 缓存命中率
        int totalInput = sessionCacheHitTokens + sessionCacheMissTokens;
        String hitRateStr = totalInput > 0
                ? String.format("%.0f%%", sessionCacheHitTokens * 100.0 / totalInput)
                : "—";

        // 💡【核心修改 1】将原本的 %d 改为 %s，并调用单位转换函数缩写数字
        // 上下文窗口使用量口径：用最近一次请求的精确 usage 快照（prompt+completion），
        // 后端不回 usage 时（如本地 sglang 网关）退化为基于会话内容的估算，保证圆环仍更新。
        // 注意：不能用跨轮累加值 sessionPromptTokens+Completion 作圆环口径，否则会把整段对话数倍重复计数。
        long usedContext = usedContextTokens();

        String detailHtml = String.format(
                "<html>当前会话累计<br>"
                        + "输入 tokens：%s（缓存命中 %s + 未命中 %s）<br>"
                        + "输出 tokens：%s<br>"
                        + "上下文窗口：%s / %sK<br>"
                        + "缓存命中率：%s<br>"
                        + "预估费用：%s 元</html>",
                formatTokenCount(sessionPromptTokens),
                formatTokenCount(sessionCacheHitTokens),
                formatTokenCount(sessionCacheMissTokens),
                formatTokenCount(sessionCompletionTokens),
                formatTokenCount(usedContext),
                getContextWindowLimit() / 1000,
                hitRateStr,
                costStr
        );

        // 更新上下文进度圈：用累计上下文使用量（输入+输出），确保随对话增长而增长
        if (contextCircle != null) {
            contextCircle.setTokens(usedContext);
            // 详细统计信息放到悬浮提示里
            String circleTooltip = String.format(
                "<html>当前上下文：%s / %s（%.0f%%）<br><br>" +
                    "<b>会话累计：</b><br>" +
                    "输入：%s（命中 %s / 未命中 %s）<br>" +
                    "输出：%s<br>" +
                    "缓存命中率：%s<br>" +
                    "预估费用：%s<br><br>" +
                    "<small>点击压缩对话历史</small></html>",
                formatTokenCount(usedContext),
                formatTokenCount(contextCircle.getMaxContextTokens()),
                usedContext * 100.0 / contextCircle.getMaxContextTokens(),
                formatTokenCount(sessionPromptTokens),
                formatTokenCount(sessionCacheHitTokens),
                formatTokenCount(sessionCacheMissTokens),
                formatTokenCount(sessionCompletionTokens),
                hitRateStr,
                costStr
            );
            contextCircle.setToolTipText(circleTooltip);
            contextCircle.putClientProperty("detail_text", detailHtml);
        }

        // 同步到底部token统计（如果存在）
        if (tokenStatsLabel != null) {
            String tooltipText = String.format(
                    "↑%s ↓%s | %s | 命中率 %s",
                    formatTokenCount(sessionPromptTokens),
                    formatTokenCount(sessionCompletionTokens),
                    costStr,
                    hitRateStr
            );
            tokenStatsLabel.setToolTipText(tooltipText);
            tokenStatsLabel.putClientProperty("detail_text", detailHtml);
        }
    }

    /**
     * 恢复发送按钮为默认状态（发送图标）
     */
    private void restoreSendButton() {
        if (sendBtn != null) {
            sendBtn.setIcon(sendIconGhost);
            sendBtn.setToolTipText("发送 (Enter)");
        }
        // ★ 回合结束必停看门狗（防止任何路径下遗留 watchdog 误触发超时 / 残留 stuck 态）
        if (streamRenderController != null) streamRenderController.stopWatchdog();
    }

    /**
     * 持久化当前回合已生成的 assistant 消息（partial 或完整），保持与 onComplete 一致的字段结构：
     * ChatMessageEntity 头 + thinking/text parts（错误信息通过 appendError 已拼进 rawText，随 text part 落库）。
     * 仅当有正文或思考内容时才落库——纯断线无内容时不硬塞空消息，避免污染下一轮上下文。
     * onComplete / onError / handleTurnTimeout 共用，消除「出错丢消息」不一致。
     */
    private void persistPartialAssistant(StreamRenderController src, ConversationManager convMgr, String sessionId) {
        boolean hasContent = !src.getCurrentAiRawText().isEmpty();
        boolean hasReasoning = src.getCurrentReasoning().length() > 0;
        if (!hasContent && !hasReasoning) return;

        ChatMessage assistant = new ChatMessage("assistant", src.getCurrentAiRawText());
        if (hasReasoning) {
            assistant.setReasoning_content(src.getCurrentReasoning().toString());
        }
        convMgr.add(assistant);

        String finalMsgId = DBChatHistoryRepository.getUUID();
        ChatMessageEntity finalMsgEntity = new ChatMessageEntity();
        finalMsgEntity.setId(finalMsgId);
        finalMsgEntity.setSessionId(sessionId);
        finalMsgEntity.setRole(Constant.ROLE_assistant);
        finalMsgEntity.setQaRound(chatSessionManager.getCurrentQaRound());
        finalMsgEntity.setCreatedAt(System.currentTimeMillis());
        finalMsgEntity.setUpdatedAt(System.currentTimeMillis());

        java.util.List<com.codepal.model.MessagePartEntity> finalParts = new java.util.ArrayList<>();
        int finalPartSeq = 0;
        if (hasReasoning) {
            finalParts.add(com.codepal.model.MessagePartEntity.thinking(
                    finalMsgId, sessionId,
                    src.getCurrentReasoning().toString(), finalPartSeq++));
        }
        if (hasContent) {
            finalParts.add(com.codepal.model.MessagePartEntity.text(
                    finalMsgId, sessionId,
                    src.getCurrentAiRawText(), finalPartSeq++));
        }
        chatSessionManager.persistMessageWithParts(finalMsgEntity, finalParts);
    }

    /**
     * 看门狗硬超时收尾：等价 onError，但提示语指向「连接可能已断开」。
     * 由 TurnWatchdog 在「连续 hardMs 无真实进展」时回调（模型流式断线 / 回合悬空未回调 onComplete/onError）。
     */
    private void handleTurnTimeout() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (streamRenderController == null || !streamRenderController.isReceiving()) return; // 已收尾，避免重复
            streamRenderController.setReceiving(false);
            statusLabel.setText("超时");
            statusLabel.setForeground(JBColor.RED);
            chatWebView.clearStatusTimer(); // 停轮播（含 stuck 态）
            streamRenderController.appendError(
                    "等待模型 / 工具响应超时（连接可能没有进展）。若长时间无响应可能是网络中断或模型服务异常，"
                            + "可点击停止后重试，或检查网络与 API 配置。");
            // ★ 持久化：与 onError / onComplete 一致，超时丢失的消息同样落库（超时提示已拼进 rawText 随 text part 落库）
            persistPartialAssistant(streamRenderController, conversationManager, chatSessionManager.getCurrentSessionId());
            streamRenderController.finalizeAiMessage(CARD_BG());
            clearStreamRefs();
        });
    }

    /**
     * 恢复输入框为可编辑状态（压缩结束后调用）
     */
    private void restoreInputField() {
        if (inputField != null) {
            inputField.setEditable(true);
            inputField.setBackground(inputFieldBg());
        }
    }

    /** 当前模型的上下文窗口上限（tokens），DeepSeek V3/V4 均为 1M */
    private long getContextWindowLimit() {
        return CPSettings.getInstance().getChatMaxTokens();
    }

    private String formatTokenCount(long count) {
        if (count < 1000) {
            return String.valueOf(count);
        }
        // 使用 DecimalFormat 自动格式化，最多保留一位小数，且自动去除尾随的 .0
        java.text.DecimalFormat df = new java.text.DecimalFormat("#.#");
        if (count < 1_000_000) {
            return df.format(count / 1000.0) + "K";
        }
        return df.format(count / 1_000_000.0) + "M";
    }

    /**
     * 清理本轮流式输出的状态
     */
    private void clearStreamRefs() {
        if (chatWebView != null) chatWebView.resetAiStream();
        if (streamRenderController != null) streamRenderController.resetStream();
        // ★ 清理流式写入参数缓冲区，避免多轮对话残留数据导致参数错乱
        streamWriteRawArgsByIndex.clear();
    }

    /**
     * 追加流式 AI 文本块。
     *
     * <p>策略：
     * <ul>
     *   <li>每个 chunk：createTextNode 追加纯文本（无闪烁，O(1)）</li>
     *   <li>每 500ms：完整 Markdown→HTML 渲染，保持代码高亮/粗体等格式实时可见</li>
     *   <li>流结束时：finalizeAiMessage 保证最终渲染完美</li>
     * </ul>
     */
    private void appendStreamChunk(String chunk) {
        if (streamRenderController != null) streamRenderController.appendStreamChunk(chunk);
    }

    /**
     * 流式过程中实时渲染 Markdown，替换气泡 innerHTML。
     * 保留此方法供其他调用点使用（如 onError 分支直接追加 markdown 格式错误文本）。
     */
    private void updateStreamRender() {
        if (streamRenderController != null) streamRenderController.updateStreamRender();
    }

    /**
     * 追加思考过程流式块
     */
    private void appendReasoningChunk(String chunk) {
        if (streamRenderController != null) streamRenderController.appendReasoningChunk(chunk);
    }

    /**
    /**
     * 安全网：移除 conversationHistory 中所有没有前置 assistant(tool_calls) 的
     * 孤儿 tool 消息，防止向 API 发送格式错误的请求。
     * <p>
     * 回溯逻辑：对每条 tool 消息向前扫描，跳过连续的 tool 消息，
     * 直到找到最近的 non-tool 消息。若该消息不是带 tool_calls 的 assistant，
     * 则标记为孤儿并移除。
     */

    /**
     * 流式完成：将纯文本替换为 Markdown 渲染后的 HTML
     */
    private void finalizeAiMessage() {
        streamRenderController.finalizeAiMessage(CARD_BG());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 消息添加（通过 ChatWebView）
    // ─────────────────────────────────────────────────────────────────────────

    private void addUserMessage(String content, String msgId) {
        // 用户消息：纯文本，不需要 Markdown 渲染
        String escaped = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        chatWebView.addUserMessage(escaped, msgId);
    }

    /** 仅用于聊天区内提示性文本（如剪贴板错误提示），非真实请求，不挂载 payload 图标 */
    private void addUserMessage(String content) {
        addUserMessage(content, null);
    }


    /**
     * 渲染带内联图片的用户消息：文字安全转义，图片以 base64 data URL 直接显示
     * （聊天页经 loadHTML 加载，origin 为 opaque/about，Chromium 同源策略会拦截 file:// 图片，
     * 故用 data URL 内联，确保图片一定能显示）。图片仅用于界面预览，不影响发送给模型的内容。
     */
    private void addUserMessageWithImages(String text, List<ChatMessage.Attachment> attachments, String msgId) {
        StringBuilder html = new StringBuilder();
        if (text != null && !text.isEmpty()) {
            html.append(escapeHtml(text)).append("<br>");
        }
        for (ChatMessage.Attachment a : attachments) {
            String dataUrl = makeImageDataUrl(a);
            if (dataUrl != null) {
                html.append("<img class=\"user-img\" src=\"").append(dataUrl).append("\"")
                        .append(" alt=\"").append(escapeHtml(a.getFileName())).append("\"")
                        .append(" data-path=\"").append(escapeHtml(a.getPath())).append("\">");
            } else {
                html.append("<br>[图片: ").append(escapeHtml(a.getFileName())).append("]");
            }
        }
        chatWebView.addUserMessageHtml(html.toString(), text == null ? "" : text, msgId);
    }

    /** 把附件读成可用于 <img src> 的 base64 data URL（先压缩成预览缩略图，失败回退原图） */
    private static String makeImageDataUrl(ChatMessage.Attachment a) {
        File f = new File(a.getPath());
        if (!f.exists()) return null;
        try {
            byte[] bytes = ImageCompressor.compress(f); // BALANCED: 最长边 1024 / JPEG / ≤500KB
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ignore) {
            try {
                byte[] raw = Files.readAllBytes(f.toPath());
                String mime = a.getMimeType() != null ? a.getMimeType() : guessMime(a.getPath());
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(raw);
            } catch (IOException ex) {
                return null;
            }
        }
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 图片附件（视觉模型）
    // ─────────────────────────────────────────────────────────────────────────

    /** 生成图片占位提示，拼进发送给主模型的文本里。视觉引导与 system prompt 共用同一决策源 */
    private static String buildImageHint(List<ChatMessage.Attachment> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("（用户附了 ").append(list.size()).append(" 张图片）\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("[图片").append(i + 1).append(": ").append(list.get(i).getPath()).append("]\n");
        }
        if (CPSettings.getInstance().shouldGuideViewImage()) {
            sb.append("若需要理解上述图片内容，请调用 view_image 工具（image_path 填对应路径），" +
                    "不要假设图片内容。");
        } else {
            sb.append("（当前未配置生效的视觉模型，可能无法识别上述图片内容。）");
        }
        return sb.toString();
    }

    /** 文件选择对话框，可多选图片 —— 使用操作系统原生文件选择器（资源管理器），而非 IDEA 内置 */
    private void chooseImageToAttach() {
        Window parent = SwingUtilities.getWindowAncestor(this);
        FileDialog fd;
        if (parent instanceof Frame) {
            fd = new FileDialog((Frame) parent, "选择图片", FileDialog.LOAD);
        } else if (parent instanceof Dialog) {
            fd = new FileDialog((Dialog) parent, "选择图片", FileDialog.LOAD);
        } else {
            fd = new FileDialog((Frame) null, "选择图片", FileDialog.LOAD);
        }
        fd.setMultipleMode(true);
        String[] exts = {"png", "jpg", "jpeg", "gif", "webp", "bmp"};
        // Windows 原生 FileDialog 会忽略 setFilenameFilter，必须用 setFile("*.ext;...") 通配符
        // 才能在资源管理器里真正过滤掉非图片；其余平台仍由 FilenameFilter 兜底。
        fd.setFile("*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp");
        fd.setFilenameFilter((dir, name) -> {
            String n = name.toLowerCase();
            for (String e : exts) if (n.endsWith("." + e)) return true;
            return false;
        });
        fd.setVisible(true);
        File[] files = fd.getFiles();
        if (files != null) {
            boolean warned = false;
            for (File f : files) {
                String n = f.getName().toLowerCase();
                boolean ok = false;
                for (String e : exts) {
                    if (n.endsWith("." + e)) { ok = true; break; }
                }
                if (!ok) {
                    if (!warned) {
                        Messages.showWarningDialog(project,
                            "只能附加图片文件（png / jpg / jpeg / gif / webp / bmp）。",
                            "选择图片");
                        warned = true;
                    }
                    continue;
                }
                attachImageFile(f);
            }
        }
    }

    /** + 号弹出的附件面板：面板样式与模型下拉框一致（圆角 + 阴影 + 扁平项），向上弹出 */
    private void showAttachPopup(JButton anchor) {
        com.intellij.openapi.ui.popup.JBPopupFactory factory =
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance();

        AttachOption[] options = new AttachOption[]{
                new AttachOption("图片…",
                        IconLoader.getIcon("/icons/image.svg", ChatPanel.class),
                        this::chooseImageToAttach)
        };
        JList<AttachOption> list = new JList<>(options);
        ComboStyle.styleList(list);
        list.setFixedCellHeight(ComboStyle.rowHeight());
        list.setCellRenderer(new AttachListRenderer());
        list.putClientProperty("hover", -1);

        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx != (Integer) list.getClientProperty("hover")) {
                    list.putClientProperty("hover", idx);
                    list.repaint();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                list.putClientProperty("hover", -1);
                list.repaint();
            }
        });

        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new ComboStyle.PopupBorder(ComboStyle.popupSurfaceColor(), ComboStyle.popupRadius()));
        panel.add(list, BorderLayout.CENTER);

        // 选项 hover 时鼠标变手形
        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        list.setCursor(hand);
        panel.setCursor(hand);

        com.intellij.openapi.ui.popup.JBPopup popup = factory.createComponentPopupBuilder(panel, list)
                .setShowBorder(false)
                .setShowShadow(false)
                .setRequestFocus(true)
                .createPopup();

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                AttachOption opt = list.getModel().getElementAt(idx);
                popup.cancel();
                // 等弹窗完全关闭后再打开系统文件选择器，避免模态焦点竞态
                SwingUtilities.invokeLater(opt.action);
            }
        });

        popup.showInScreenCoordinates(anchor, computeAbovePoint(anchor, panel));
    }

    /** 计算在 anchor 上方弹出的屏幕坐标（空间不足时回退到下方） */
    private Point computeAbovePoint(Component anchor, JComponent panel) {
        Point loc = anchor.getLocationOnScreen();
        int h = panel.getPreferredSize().height;
        int x = loc.x;
        int y = loc.y - h - 6; // 上方留 6px 间隙
        // 屏幕顶部空间不足时，回退到 anchor 下方弹出
        if (y < 0) {
            y = loc.y + anchor.getHeight() + 6;
        }
        return new Point(x, y);
    }

    /** + 弹窗中的单选项 */
    private static class AttachOption {
        final String label;
        final Icon icon;
        final Runnable action;

        AttachOption(String label, Icon icon, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.action = action;
        }
    }

    /** + 弹窗列表渲染器：扁平风格，与 ModeComboRenderer 列表态一致（选中深灰 / hover 淡灰） */
    private static class AttachListRenderer extends JComponent implements ListCellRenderer<AttachOption> {
        private AttachOption value;
        private boolean selected;
        private boolean hovered;

        @Override
        public Component getListCellRendererComponent(JList<? extends AttachOption> list,
                                                      AttachOption val, int index, boolean sel, boolean hasFocus) {
            this.value = val;
            this.selected = sel;
            Integer h = (Integer) list.getClientProperty("hover");
            this.hovered = h != null && h == index;
            setOpaque(false);
            setBorder(null);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            // 基类由弹窗 PopupBorder 绘制（popupSurfaceColor）；这里只画选中/hover 灰
            if (selected) {
                g2.setColor(ComboStyle.selectionColor());
                g2.fillRect(0, 0, w, h);
            } else if (hovered) {
                g2.setColor(ComboStyle.hoverColor());
                g2.fillRect(0, 0, w, h);
            }

            int px = ComboStyle.itemPaddingX();
            int iconSize = ComboStyle.iconSize();
            int iconY = (h - iconSize) / 2;
            int iconX = px;
            if (value != null && value.icon != null) {
                int icX = iconX + (iconSize - value.icon.getIconWidth()) / 2;
                int icY = iconY + (iconSize - value.icon.getIconHeight()) / 2;
                value.icon.paintIcon(this, g2, icX, icY);
            }

            g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
            g2.setColor(selected ? Color.WHITE : ComboStyle.textPrimary());
            FontMetrics fm = g2.getFontMetrics();
            int textX = iconX + iconSize + 10;
            int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (value != null) g2.drawString(value.label, textX, textY);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
            int textW = fm.stringWidth(value == null ? "" : value.label);
            int px = ComboStyle.itemPaddingX();
            return new Dimension(px + ComboStyle.iconSize() + 10 + textW + px, ComboStyle.rowHeight());
        }
    }

    /** 把本地图片文件作为附件 */
    /** 最多可粘贴的图片数量（后台限制） */
    private static final int MAX_IMAGE_ATTACHMENTS = 5;

    private void attachImageFile(File file) {
        if (file == null || !file.exists()) return;
        if (pendingAttachments.size() >= MAX_IMAGE_ATTACHMENTS) {
            addUserMessage("最多支持粘贴 " + MAX_IMAGE_ATTACHMENTS + " 张图片。");
            return;
        }
        String name = file.getName();
        String mime = guessMime(name);
        ChatMessage.Attachment a = new ChatMessage.Attachment(name, file.getAbsolutePath(), mime);
        pendingAttachments.add(a);
        addAttachmentChip(a);
    }

    /**
     * 显式把 Ctrl/Cmd+V 绑定到 TransferHandler 的 importData。
     * 一些 L&F / 平台不会把 TransferHandler 自动注册成 "paste" Action，
     * 导致 Ctrl+V 看起来“无效”；这里直接兜底绑定到我们的处理器。
     */
    /** 右键菜单：仅「粘贴」一项 */
    private void installInputFieldContextMenu(JTextArea field) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        JMenuItem paste = new JMenuItem("粘贴");
        paste.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        paste.addActionListener(e -> doPaste(field));
        menu.add(paste);
        field.setComponentPopupMenu(menu);
    }

    /**
     * 在组件级注册 Ctrl/Cmd+V，覆盖 IDEA 全局 Paste 动作。
     * IDEA 的全局 Paste 只处理文本（不处理图片），会把 Ctrl+V 抢走、导致图片粘贴“没反应”。
     * 用 AnAction + registerCustomShortcutSet 让输入框聚焦时优先触发我们的粘贴（能识别图片）。
     */
    private void installInputFieldPasteShortcut(JTextArea field) {
        AnAction pasteAction = new AnAction() {
            @Override
            public void actionPerformed(AnActionEvent e) {
                doPaste(field);
            }
            @Override
            public void update(AnActionEvent e) {
                e.getPresentation().setEnabledAndVisible(true);
            }
        };
        pasteAction.registerCustomShortcutSet(
                new CustomShortcutSet(
                        KeyboardShortcut.fromString("ctrl V"),
                        KeyboardShortcut.fromString("meta V")),
                field);
    }

    /** 从系统剪贴板取数据并交给输入框的 TransferHandler（支持图片与文本） */
    private void doPaste(JTextArea field) {
        Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        if (t == null) return;
        // 交给自定义 TransferHandler：图片→附件，文本→显式插入。
        // 若返回 false（极少数平台对文本未命中），兜底走文本组件原生粘贴，保证文字一定粘得上。
        boolean handled = field.getTransferHandler().importData(field, t);
        if (!handled) {
            field.paste();
        }
    }

    /** 把剪贴板里的 BufferedImage 落盘为临时 png 再作为附件 */
    private void attachImageFromImage(BufferedImage img) {
        if (img == null) {
            addUserMessage("无法读取剪贴板中的图片（图片可能未正确加载）。");
            return;
        }
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "codepal");
            if (!dir.exists() && !dir.mkdirs()) {
                addUserMessage("无法创建临时目录以保存图片。");
                return;
            }
            File out = new File(dir, "paste_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", out);
            attachImageFile(out);
        } catch (Exception e) {
            addUserMessage("保存粘贴的图片失败：" + e.getMessage());
        }
    }

    /**
     * 附件数据已加入 pendingAttachments 后调用：重建附件条。
     * chip 一律由 refreshAttachStrip() 从数据源重建，此处不再手动 add，
     * 避免数据与 UI 两份容器不同步（旧图残留/累加）。
     */
    private void addAttachmentChip(ChatMessage.Attachment a) {
        if (attachStrip == null || attachChips == null) return;
        refreshAttachStrip();
    }

    /** 双击图片附件：在 IDEA 编辑区用内置图片查看器预览 */
    private void openAttachmentInEditor(String path) {
        if (project == null || path == null) return;
        File f = new File(path);
        if (!f.exists()) {
            addUserMessage("图片文件不存在：" + path);
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        if (vf == null) {
            addUserMessage("无法在 IDE 中打开：" + path);
            return;
        }
        FileEditorManager.getInstance(project).openFile(vf, true);
    }

    /** 单个附件 chip：默认只显示缩略图；hover 时向右微动效展开文件名 + × 按钮 */
    private class AttachmentChip extends JPanel {
        private static final int THUMB = 22;
        private static final int ARC = 8;
        private static final int EXPAND_MS = 130; // 微动效时长
        private static final int H_PAD = 4, V_PAD = 1;
        private final ChatMessage.Attachment a;
        private final JLabel thumbLabel;
        private final NameHolder nameHolder;
        private final JButton remove;
        private final int expandedW;
        private final int collapsedPrefW;
        private final int expandedPrefW;
        private int prefW;
        private int animW = 0;
        private final javax.swing.Timer animTimer;
        private boolean expanding = false;

        AttachmentChip(ChatMessage.Attachment attachment) {
            // 绝对布局：自行控制尺寸，避免 FlowLayout 在动画中反复 relayout 导致鼠标事件抖动
            super(null);
            this.a = attachment;
            setOpaque(false);

            thumbLabel = new JLabel() {
                @Override public Dimension getPreferredSize() { return new Dimension(THUMB, THUMB); }
                @Override public Dimension getMinimumSize() { return getPreferredSize(); }
                @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            };
            thumbLabel.setHorizontalAlignment(SwingConstants.CENTER);
            thumbLabel.setVerticalAlignment(SwingConstants.CENTER);
            thumbLabel.setOpaque(false);
            thumbLabel.setBounds(H_PAD, V_PAD, THUMB, THUMB);
            add(thumbLabel);

            JLabel nameLabel = new JLabel(clipFileName(attachment.getFileName(), 10));
            nameLabel.setFont(JBUI.Fonts.label(12));
            nameLabel.setForeground(UIUtil.getLabelForeground());

            remove = new JButton("✕");
            remove.setFont(JBUI.Fonts.label(11));
            remove.setFocusable(false);
            remove.setBorderPainted(false);
            remove.setContentAreaFilled(false);
            remove.setOpaque(false);
            remove.setVisible(false); // 收起态不可见，避免隐形 × 覆盖相邻缩略图拦截点击
            remove.addActionListener(e -> {
                pendingAttachments.remove(a);
                refreshAttachStrip();
            });

            nameHolder = new NameHolder(nameLabel, remove, THUMB);
            expandedW = nameHolder.getNaturalWidth();
            nameHolder.setBounds(H_PAD + THUMB + 4, V_PAD, expandedW, THUMB);
            add(nameHolder);

            collapsedPrefW = H_PAD + THUMB + H_PAD;
            expandedPrefW = collapsedPrefW + 4 + expandedW;
            prefW = collapsedPrefW;

            // hover 展开/收起：把同一监听器挂到 chip 及其所有子组件。
            // 关键：Swing 在“鼠标从容器进入子组件”时也会向容器发 mouseExited；
            // 而“从子组件移出到容器之外”时容器不会再次收到 mouseExited。
            // 若只监听容器本身，真实离开事件收不到 → 不会收起。
            // 解决：监听器也挂到子组件，mouseExited 把坐标换算到 chip 坐标系，
            // 仅当指针落在 chip 矩形之外才收起。
            MouseAdapter hover = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { setExpanding(true); }
                @Override public void mouseExited(MouseEvent e) {
                    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), AttachmentChip.this);
                    if (AttachmentChip.this.contains(p.x, p.y)) return;
                    setExpanding(false);
                }
            };
            addMouseListener(hover);
            thumbLabel.addMouseListener(hover);
            nameHolder.addMouseListener(hover);
            remove.addMouseListener(hover);

            // 双击图片 → 在 IDEA 编辑区预览（图片查看器）
            MouseAdapter dbl = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        openAttachmentInEditor(a.getPath());
                    }
                }
            };
            addMouseListener(dbl);
            thumbLabel.addMouseListener(dbl);

            animTimer = new javax.swing.Timer(16, e -> tick());
            animTimer.setCoalesce(true);

            loadThumbnail();
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(prefW, THUMB + V_PAD * 2); }
        @Override
        public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override
        public Dimension getMaximumSize() { return getPreferredSize(); }

        private void setExpanding(boolean on) {
            if (expanding == on) return;
            if (on) {
                // 展开开始：一次性把整卡宽度撑到“缩略图+名字+×”，此后 × 处于卡片命中区内，
                // 鼠标从缩略图滑向 × 不会离开卡片 → 不会误收起。仅 relayout 一次。
                prefW = expandedPrefW;
                revalidate();
            }
            expanding = on;
            if (!animTimer.isRunning()) animTimer.start();
        }

        private void tick() {
            int target = expanding ? expandedW : 0;
            int step = Math.max(1, (expandedW / (EXPAND_MS / 16)) + 1);
            if (animW < target) animW = Math.min(target, animW + step);
            else if (animW > target) animW = Math.max(target, animW - step);
            nameHolder.setRenderWidth(animW);
            // × 仅在完全展开时可见/可点
            remove.setVisible(animW == expandedW);
            // 仅 clip 动画，不再每帧 revalidate（避免 relayout 抖动与伪 mouseExited）
            repaint();
            if (animW == target) {
                animTimer.stop();
                if (!expanding) {
                    // 收起结束：把卡片宽度缩回仅缩略图，释放横向空间
                    prefW = collapsedPrefW;
                    revalidate();
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(255, 255, 255, 14)));
            g2.fillRoundRect(0, 0, w, h, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }

        /** 容纳「文件名 + ×」的容器：宽度可动画，绘制时按当前宽度裁剪（实现向右伸展动效） */
        private static class NameHolder extends JPanel {
            private int renderW;
            NameHolder(JLabel nameLabel, JButton remove, int height) {
                super(null);
                setOpaque(false);
                nameLabel.setSize(nameLabel.getPreferredSize());
                nameLabel.setLocation(0, (height - nameLabel.getHeight()) / 2);
                add(nameLabel);
                int nameW = nameLabel.getWidth();
                remove.setSize(remove.getPreferredSize());
                remove.setLocation(nameW + 6, (height - remove.getHeight()) / 2);
                add(remove);
            }
            int getNaturalWidth() {
                // 文件名宽度 + 间隙 + × 宽度
                int maxRight = 0;
                for (Component c : getComponents()) {
                    maxRight = Math.max(maxRight, c.getX() + c.getWidth());
                }
                return maxRight;
            }
            void setRenderWidth(int w) { this.renderW = w; }
            @Override public Dimension getPreferredSize() { return new Dimension(renderW, THUMB); }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            @Override public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setClip(0, 0, renderW, getHeight());
                super.paint(g2);
                g2.dispose();
            }
        }

        /** 异步加载图片并缩放到 THUMB×THUMB，避免大图阻塞 EDT */
        private void loadThumbnail() {
            String path = a.getPath();
            SwingWorker<ImageIcon, Void> worker = new SwingWorker<>() {
                @Override protected ImageIcon doInBackground() {
                    return makeThumb(path);
                }
                @Override protected void done() {
                    try {
                        ImageIcon icon = get();
                        if (icon != null) thumbLabel.setIcon(icon);
                        else thumbLabel.setText("📎");
                    } catch (Exception ignored) {
                        thumbLabel.setText("📎");
                    }
                }
            };
            worker.execute();
        }

        private ImageIcon makeThumb(String path) {
            try {
                File f = new File(path);
                if (!f.exists()) return null;
                BufferedImage src;
                // 用 ImageReader 快速解码（对 jpg/webp/gif/png 通用）；失败回退 ImageIO
                try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
                    java.util.Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(
                            guessExt(path).toUpperCase());
                    if (readers.hasNext()) {
                        ImageReader r = readers.next();
                        r.setInput(iis);
                        ImageReadParam param = r.getDefaultReadParam();
                        // 缩小到 THUMB×THUMB 大致尺寸，减少内存
                        int thumbMax = THUMB * 2;
                        BufferedImage probe = r.read(0);
                        int sw = probe.getWidth(), sh = probe.getHeight();
                        int scale = Math.max(1, Math.max(sw, sh) / thumbMax);
                        param.setSourceSubsampling(scale, scale, 0, 0);
                        src = r.read(0, param);
                        r.dispose();
                    } else {
                        src = ImageIO.read(f);
                    }
                }
                if (src == null) return null;
                BufferedImage out = new BufferedImage(THUMB, THUMB, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                // contain：保持比例居中
                double sx = (double) THUMB / src.getWidth();
                double sy = (double) THUMB / src.getHeight();
                double s = Math.min(sx, sy);
                int dw = (int) Math.round(src.getWidth() * s);
                int dh = (int) Math.round(src.getHeight() * s);
                int dx = (THUMB - dw) / 2;
                int dy = (THUMB - dh) / 2;
                g.drawImage(src, dx, dy, dw, dh, null);
                g.dispose();
                return new ImageIcon(out);
            } catch (Exception ignored) {
                return null;
            }
        }

        private String guessExt(String path) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".png")) return "png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg";
            if (lower.endsWith(".gif")) return "gif";
            if (lower.endsWith(".webp")) return "webp";
            if (lower.endsWith(".bmp")) return "bmp";
            return "png";
        }
    }

    /** 文件名截断到 max 个字符（超出则末尾加 …），用于 chip 紧凑展示 */
    private static String clipFileName(String name, int max) {
        if (name == null) return "";
        if (name.length() <= max) return name;
        return name.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 无附件时隐藏附件条 */
    /**
     * 按 pendingAttachments 重建整条附件条（唯一真源）。
     * 之前只切可见性、不清 chip，导致发送后旧 chip 残留在容器里，
     * 下一轮粘贴时旧图会重新显示并逐轮累加。
     */
    private void refreshAttachStrip() {
        if (attachStrip == null || attachChips == null) return;
        attachChips.removeAll();
        for (ChatMessage.Attachment a : pendingAttachments) {
            attachChips.add(new AttachmentChip(a));
        }
        attachStrip.setVisible(!pendingAttachments.isEmpty());
        attachStrip.revalidate();
        attachStrip.repaint();
    }

    /** 打开视觉模型配置对话框 */
    private void openVisionConfig() {
        // 复用统一的 AddModelDialog（MODE_VISION），不再另开独立面板，保证样式与流程一致
        Window parentWindow = getParentWindow();
        if (parentWindow == null) return;
        com.codepal.ui.AddModelDialog dialog =
                new com.codepal.ui.AddModelDialog(project, parentWindow, com.codepal.ui.AddModelDialog.MODE_VISION);
        dialog.setVisible(true);
    }

    private static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage) return (BufferedImage) img;
        // 等待异步图片（如系统剪贴板截图）完全加载后再取尺寸/绘制，
        // 否则 getWidth(null) 可能返回 -1，缓冲为空导致 ImageIO 写失败（异常被吞）。
        MediaTracker tracker = new MediaTracker(new java.awt.Component() {});
        tracker.addImage(img, 0);
        try {
            tracker.waitForAll();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        int w = img.getWidth(null);
        int h = img.getHeight(null);
        if (w <= 0 || h <= 0) return null;
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buf.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return buf;
    }

    /**
     * Icon 装饰器：把任意 Icon 强制缩放到指定像素绘制。
     * 用于固定 SVG 渲染尺寸（避免 viewBox=1024 的图标按按钮尺寸拉伸爆框）。
     */
    private static class SizedIcon implements Icon {
        private final Icon delegate;
        private final int w;
        private final int h;

        SizedIcon(Icon delegate, int w, int h) {
            this.delegate = delegate;
            this.w = w;
            this.h = h;
        }

        @Override public int getIconWidth() { return w; }
        @Override public int getIconHeight() { return h; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.translate(x, y);
            g2.scale((double) w / delegate.getIconWidth(), (double) h / delegate.getIconHeight());
            delegate.paintIcon(c, g2, 0, 0);
            g2.dispose();
        }
    }

    /** 输入框的 TransferHandler：粘贴图片（截图/文件）即作为附件，纯文本仍走默认粘贴 */
    private class ImageAwareTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(JComponent comp, DataFlavor[] flavors) {
            for (DataFlavor f : flavors) {
                if (f != null && (isImageFlavor(f) || f.equals(DataFlavor.javaFileListFlavor))) return true;
            }
            return super.canImport(comp, flavors);
        }

        @Override
        public boolean importData(JComponent comp, Transferable t) {
            // 1) 标准 imageFlavor
            if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                try {
                    Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
                    BufferedImage bi = toBufferedImage(img);
                    if (bi != null) { attachImageFromImage(bi); return true; }
                } catch (Exception ignored) { }
            }
            // 2) 任意 image/* flavor（Windows 截图常以 image/png 形式提供，未必是 imageFlavor）
            for (DataFlavor f : t.getTransferDataFlavors()) {
                if (isImageFlavor(f)) {
                    try {
                        BufferedImage bi = readImageData(t.getTransferData(f));
                        if (bi != null) { attachImageFromImage(bi); return true; }
                    } catch (Exception ignored) { }
                }
            }
            // 3) 复制的图片文件
            if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    boolean any = false;
                    for (File f : files) {
                        if (guessMime(f.getName()).startsWith("image/")) { attachImageFile(f); any = true; }
                    }
                    if (any) return true;
                } catch (Exception ignored) { }
            }
            // 4) 纯文本：显式插入，避免 super.importData 在个别 L&F / 平台下对文本返回 false，
            //    导致「粘贴文字没反应」。图片分支未命中即视为文本。
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    String s = (String) t.getTransferData(DataFlavor.stringFlavor);
                    if (s != null && !s.isEmpty() && comp instanceof JTextComponent) {
                        ((JTextComponent) comp).replaceSelection(s);
                        return true;
                    }
                } catch (Exception ignored) { }
            }
            return super.importData(comp, t);
        }

        private boolean isImageFlavor(DataFlavor f) {
            if (f == null) return false;
            if (f.equals(DataFlavor.imageFlavor)) return true;
            String mt = f.getMimeType();
            return mt != null && mt.startsWith("image/");
        }

        /** 从剪贴板数据读取图片：支持 BufferedImage / Image / byte[] / InputStream */
        private BufferedImage readImageData(Object data) {
            if (data == null) return null;
            try {
                if (data instanceof BufferedImage) return (BufferedImage) data;
                if (data instanceof Image) return toBufferedImage((Image) data);
                if (data instanceof byte[]) return ImageIO.read(new java.io.ByteArrayInputStream((byte[]) data));
                if (data instanceof java.io.InputStream) return ImageIO.read((java.io.InputStream) data);
            } catch (Exception ignored) { }
            return null;
        }
    }

    /** 历史消息专用：使用 DB 原始 qaRound，不自增 */
    private void addHistoryUserMessage(String content, int qaRound, String msgId) {
        String escaped = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
        chatWebView.addHistoryUserMessage(escaped, qaRound, msgId);
    }

    /**
     * 捕获「本轮发送给模型的完整上下文」并存入内存快照（key=用户消息 DB id）。
     * 在用户消息入 conversationManager 之前调用，故 history 不含本轮 user 消息（与桌面端一致：
     * system / 多轮历史 / 本轮输入 三段分离）。仅内存、不落库，重载会话后自然清空。
     */
    private void captureOutgoingPayload(String msgId, String userText) {
        try {
            com.codepal.model.ChatMessage sys = conversationManager.getSystemMessage();
            String system = sys != null && sys.getContent() != null ? sys.getContent() : "";
            JsonArray history = new JsonArray();
            java.util.Set<Integer> qaRounds = new java.util.HashSet<>();
            for (com.codepal.model.ChatMessage m : conversationManager.getMessages()) {
                if ("system".equals(m.getRole())) continue;
                qaRounds.add(m.getQaRound());
                String c = m.getContent();
                if (c == null) c = "";
                if (c.length() > 2000) c = c.substring(0, 2000) + " …(截断)";
                history.add("[" + m.getRole() + "] " + c);
            }
            JsonObject o = new JsonObject();
            o.addProperty("system", system);
            o.add("history", history);
            o.addProperty("userMessage", userText != null ? userText : "");
            o.addProperty("messageCount", qaRounds.size() + 1);
            outgoingPayloads.put(msgId, o.toString());
        } catch (Exception ignored) {
            // 不影响主对话流程
        }
    }

    /**
     * 构造「本轮回答 token 消耗」JSON，挂到助手消息底部悬浮查看；无 usage 时返回 null（不挂 chip）。
     * <p>对齐 codepal-desktop TokenRing 设计：
     * <ul>
     *   <li>四轴输入拆分：系统提示词 / 对话消息 / 工具调用 / 子智能体（启发式估算）</li>
     *   <li>每轴占比百分比</li>
     *   <li>工具调用二级明细：逐工具 token</li>
     *   <li>子智能体二级明细：逐子智能体 token</li>
     *   <li>缓存命中率（百分比 + 明细）</li>
     *   <li>本次费用（含后台调用标注）</li>
     * </ul>
     */
    private String buildAnswerTokenJson(long input, long output) {
        if (input <= 0 && output <= 0) return null; // 后端未回 usage（如本地网关），不显示空 chip
        // "本次回答"口径：缓存命中/未命中用最近一次请求的快照（与 input/output 同源），
        // 不能用 sessionCacheHitTokens 累计值，否则多轮后与 input/output 对不上。
        long ch = lastCacheHitTokens;
        long cm = lastCacheMissTokens;

        // ── 四轴启发式估算（对齐 codepal-desktop TokenMeter）──
        // 1. 系统提示词
        long systemTok = 0;
        com.codepal.model.ChatMessage sysMsg = conversationManager.getSystemMessage();
        if (sysMsg != null && sysMsg.getContent() != null) {
            systemTok = estimateTokens(sysMsg.getContent());
        }

        // 2. 对话消息（排除 system 消息和 tool 角色消息）
        long convTok = 0;
        for (com.codepal.model.ChatMessage m : conversationManager.getMessages()) {
            if ("system".equals(m.getRole())) continue;
            if ("tool".equals(m.getRole())) continue; // tool 结果归入工具轴
            String c = m.getContent();
            if (c != null) convTok += estimateTokens(c);
            String r = m.getReasoning_content();
            if (r != null) convTok += estimateTokens(r);
        }

        // 3 & 4. 工具调用 + 子智能体（遍历 conversationManager 中的 tool 角色消息）
        long toolTok = 0;
        long subAgentTok = 0;
        java.util.Map<String, Long> toolByName = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> subAgentByName = new java.util.LinkedHashMap<>();
        int toolCalls = 0;
        int subAgentCalls = 0;
        for (com.codepal.model.ChatMessage m : conversationManager.getMessages()) {
            if (!"tool".equals(m.getRole())) continue;
            String toolName = m.getName();
            if (toolName == null) toolName = "unknown";
            long tok = estimateTokens(m.getContent());
            if (isSubAgent(toolName)) {
                subAgentTok += tok;
                subAgentByName.merge(toolName, tok, Long::sum);
                subAgentCalls++;
            } else {
                toolTok += tok;
                toolByName.merge(toolName, tok, Long::sum);
                toolCalls++;
            }
        }

        // 启发式输入合计
        long heuristicTotal = systemTok + convTok + toolTok + subAgentTok;
        // 优先用真实 API 计费输入（displayInput），回退启发式估算
        long displayInput = input > 0 ? input : heuristicTotal;

        // 缓存命中率
        String cacheHitRate = null;
        if (ch > 0 && displayInput > 0) {
            cacheHitRate = String.format("%.1f", Math.min(ch, displayInput) * 100.0 / displayInput);
        }

        String cost = computeCostStr(sessionCacheHitTokens, sessionCacheMissTokens,
                sessionCompletionTokens, selectedModelName());

        JsonObject o = new JsonObject();
        o.addProperty("input", input);
        o.addProperty("output", output);
        o.addProperty("cacheHit", ch);
        o.addProperty("cacheMiss", cm);
        o.addProperty("total", input + output);
        o.addProperty("cost", cost != null ? cost : "");

        // 四轴拆分（对齐 codepal-desktop TokenUsage）
        o.addProperty("system", systemTok);
        o.addProperty("conversation", convTok);
        o.addProperty("toolsTotal", toolTok);
        o.addProperty("subagentsTotal", subAgentTok);
        o.addProperty("heuristicTotal", heuristicTotal);
        o.addProperty("displayInput", displayInput);
        o.addProperty("toolCalls", toolCalls);
        o.addProperty("subAgentCalls", subAgentCalls);
        o.addProperty("cacheHitRate", cacheHitRate);

        // 逐工具明细
        JsonObject toolsByName = new JsonObject();
        toolByName.forEach(toolsByName::addProperty);
        o.add("toolsByName", toolsByName);

        // 逐子智能体明细
        JsonObject subByName = new JsonObject();
        subAgentByName.forEach(subByName::addProperty);
        o.add("subagentsByName", subByName);

        return o.toString();

    }

    private void addAiMessage(String content) {
        // AI 消息：Markdown → HTML 片段（离线渲染，不显示流式光标）
        String bodyHtml = MarkdownUtil.toHtmlFragment(content);
        chatWebView.startAiStream();
        chatWebView.finalizeAiMessage(bodyHtml, content);
    }

    /**
     * 显示工具调用信息（通过 ChatWebView DOM 插入）
     */
    private void addToolCallMessage(String toolName, String result) {
        chatWebView.addToolCall(toolName, result);
    }






    /**
     * 显示历史会话面板
     */
    public void showHistoryPanel() {
        refreshHistoryList();
        cardLayout.show(cardPanel, HISTORY_CARD);
    }

    /**
     * 显示聊天面板（从历史面板返回）。
     * 兜底：若 conversationHistory 中没有用户/AI 消息且 chatSessionManager.getCurrentSessionId() 不为空，
     * 说明 chatWebView 的 DOM 可能已被清空或从未加载过当前会话的消息，此时自动从 DB 回显。
     */
    public void showChatPanel() {
        cardLayout.show(cardPanel, CHAT_CARD);
        // 防御性检查：当前会话仅有 system 消息时，从数据库重新加载
        if (chatSessionManager.getCurrentSessionId() != null && conversationManager.size() <= 1
                && (conversationManager.isEmpty() || "system".equals(conversationManager.getSystemMessage().getRole()))) {
            ThreadHelper.executeAsync(project,
                    () -> DBChatHistoryRepository.getSessionMessagesWithParts(chatSessionManager.getCurrentSessionId(), 10, 0),
                    (result) -> {
                        if (result == null || result.isEmpty()) return;
                        chatWebView.clearMessages();
                        // 重建 conversationHistory（保留 system 消息）
                        ChatMessage systemMsg = conversationManager.getSystemMessage();
                        conversationManager.getMessages().clear();
                        if (systemMsg != null) conversationManager.getMessages().add(systemMsg);
                        for (java.util.Map.Entry<ChatMessageEntity, java.util.List<com.codepal.model.MessagePartEntity>> entry : result.entrySet()) {
                            ChatMessageEntity rec = entry.getKey();
                            java.util.List<com.codepal.model.MessagePartEntity> parts = entry.getValue();

                            java.util.List<ChatMessage> builtMsgs = com.codepal.session.ChatSessionManager.buildChatMessagesFromParts(rec, parts);
                            conversationManager.getMessages().addAll(builtMsgs);

                            // 渲染 UI
                            renderMessageWithParts(rec, parts);
                        }
                        // 恢复 lcQaRound 为当前轮次（prepend history 不应影响新消息编号）
                        chatWebView.setQaRound(chatSessionManager.getCurrentQaRound());
                        // 兜底加载后也检测异常中断
                        iterationGuard.detectAndRecoverCrash();
                    });
        }
    }

    /**
     * 构建历史会话面板：顶部工具栏（返回+标题+删除所有）+ 会话列表
     */
    private JBPanel<?> buildHistoryPanel() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 0));
        panel.setBackground(UIUtil.getPanelBackground());

        // ── 顶部工具栏 ──
        JBPanel<?> toolbar = new JBPanel<>(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        toolbar.setBorder(JBUI.Borders.empty(10, 12));

        // 左侧：返回按钮
        JButton backBtn = new JButton("  返回", AllIcons.Actions.Back);
        backBtn.setFont(JBUI.Fonts.label(13));
        backBtn.setFocusable(false);
        backBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(dividerColor(), 1),
                JBUI.Borders.empty(4, 10)
        ));
        backBtn.setContentAreaFilled(false);
        backBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        backBtn.addActionListener(e -> showChatPanel());
        toolbar.add(backBtn, BorderLayout.WEST);

        // 中间：标题
        JBLabel titleLabel = new JBLabel("历史对话");
        titleLabel.setFont(JBUI.Fonts.label(15).deriveFont(Font.BOLD));
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        toolbar.add(titleLabel, BorderLayout.CENTER);

        // 右侧：删除所有按钮
        JButton deleteAllBtn = new JButton("  删除所有", AllIcons.Actions.GC);
        deleteAllBtn.setFont(JBUI.Fonts.label(12));
        deleteAllBtn.setFocusable(false);
        deleteAllBtn.setForeground(new Color(0xE53935));
        deleteAllBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE53935), 1),
                JBUI.Borders.empty(4, 10)
        ));
        deleteAllBtn.setContentAreaFilled(false);
        deleteAllBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        deleteAllBtn.addActionListener(e -> deleteAllSessions());
        toolbar.add(deleteAllBtn, BorderLayout.EAST);

        panel.add(toolbar, BorderLayout.NORTH);

        // ── 会话列表 ──
        historyListModel = new DefaultListModel<>();
        historyList = new JBList<>(historyListModel);
        historyList.setCellRenderer(new HistoryCellRenderer());
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setBorder(JBUI.Borders.empty(4, 8));
        historyList.setBackground(UIUtil.getPanelBackground());

        // 双击打开会话
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedHistorySession();
                }
            }
        });

        // 右键菜单
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showHistoryPopup(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showHistoryPopup(e);
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(historyList);
        scrollPane.setBorder(null);
        scrollPane.setBackground(UIUtil.getPanelBackground());
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // 顶部会话 Tab 条（参考 CodeBuddy）
    // ─────────────────────────────────────────────────────────────────────────

    /** 构建顶部 Tab 条容器：仅一条水平滚动的 Tab 行（不再放置右侧 + 新建按钮） */
    private JBPanel<?> buildSessionTabsBar() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(chatBgColor());
        panel.setBorder(JBUI.Borders.empty(0, 4, 0, 4));
        panel.setVisible(false); // 默认隐藏，openSession 后按需显示

        JBPanel<?> strip = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 4));
        strip.setOpaque(false);
        panel.add(strip, BorderLayout.CENTER);

        // 持引用以便 rebuild
        panel.putClientProperty("strip", strip);
        return panel;
    }

    /** 重新渲染 Tab 条内容（已废弃，多标签由 TabManager 管理） */
    @SuppressWarnings("unchecked")
    private void refreshSessionTabsBar() {
        // 空实现：多标签由 TabManager 管理，ChatPanel 内部不再维护标签栏
    }

    /** 构造单个 Tab 控件：无边框块，仅激活态底部画强调线（画在 Tab 自身边框上，3px 实心，不依赖子组件高度） */
    private JBPanel<?> buildSingleTab(String sessionId, String title, boolean active) {
        JBPanel<?> tab = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 6, 4));
        tab.setOpaque(true);
        tab.setBackground(chatBgColor());
        // 关键修复：下划线直接画在 Tab 自己的底部边框（MatteBorder 3px），
        // 不再用独立子组件/自绘，避免高度塌陷或被覆盖导致"看不到线"。
        if (active) {
            tab.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, activeUnderlineColor()));
        } else {
            tab.setBorder(JBUI.Borders.empty(0, 0, 3, 0));
        }
        // 选中态：正常前景色（主题自适应）+ 粗体；未选中：次级灰字
        Color fg = active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground();
        JBLabel label = new JBLabel(truncateForTab(title));
        label.setForeground(fg);
        label.setFont(JBUI.Fonts.label(active ? 13 : 12).deriveFont(active ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                activateTab(sessionId);
            }
        });
        tab.add(label);

        JLabel close = new JLabel("×");
        close.setFont(JBUI.Fonts.label(14));
        close.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                closeTab(sessionId);
            }
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                close.setForeground(UIUtil.getLabelForeground());
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                close.setForeground(active ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
            }
        });
        tab.add(close);
        return tab;
    }

    /** 激活 Tab 底部强调线颜色：深色主题白色，浅色主题用主题强调蓝，两种主题都清晰可见 */
    private static Color activeUnderlineColor() {
        return JBColor.isBright()
                ? JBColor.namedColor("Component.focusColor", new JBColor(0x1E88E5, 0x4C9BE8))
                : JBColor.WHITE;
    }

    /** 聊天窗背景色：与 ChatHtmlTemplate.generate 中的 body 背景一致（深色 #2B2B2B / 浅色 #F5F5F5） */
    private static Color chatBgColor() {
        return JBColor.isBright()
                ? new JBColor(0xF5F5F5, 0xF5F5F5)
                : new JBColor(0x2B2B2B, 0x2B2B2B);
    }

    /** Tab 标题过长时截断（参考编辑器 Tab 行为） */
    private static String truncateForTab(String t) {
        if (t == null) return "";
        final int max = 18;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    /** 添加/更新 Tab；若 id 已在表中则更新标题（已废弃，多标签由 TabManager 管理） */
    public void upsertTab(String sessionId, String title) {
        if (sessionId == null) return;
        String t = title != null ? title : "新会话";
        openTabs.put(sessionId, t);
        // 通知 TabManager 更新标题（仅更新标题，不创建标签）
        if (tabCallbacks != null) {
            tabCallbacks.onSessionTitleChanged(t);
        }
    }

    /** 移除 Tab（已废弃，多标签由 TabManager 管理） */
    public void removeTab(String sessionId) {
        if (sessionId == null) return;
        openTabs.remove(sessionId);
    }

    /** 点击 Tab 时调用（已废弃，多标签由 TabManager 管理） */
    public void activateTab(String sessionId) {
        // 空实现：多标签由 TabManager 管理
    }

    /** 点击 × 时调用（已废弃，多标签由 TabManager 管理） */
    public void closeTab(String sessionId) {
        // 空实现：多标签由 TabManager 管理
    }


    private void showHistoryPopup(MouseEvent e) {
        int idx = historyList.locationToIndex(e.getPoint());
        if (idx < 0) return;
        historyList.setSelectedIndex(idx);
        HistoryItem item = historyListModel.getElementAt(idx);
        if (item == null || item.getSession() == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("打开会话", AllIcons.Actions.ShowCode);
        openItem.addActionListener(ev -> {
            // ★ 多标签隔离：右键「打开会话」也通过 TabCallbacks 在新标签中打开
            String sid = item.getSession().getId();
            String title = item.getSession().getName();
            if (title == null || title.isEmpty()) title = "未命名会话";
            if (tabCallbacks != null) {
                tabCallbacks.onOpenHistorySession(sid, title);
            }
            showChatPanel();
        });
        menu.add(openItem);

        JMenuItem renameItem = new JMenuItem("编辑标题", AllIcons.Actions.Edit);
        renameItem.addActionListener(ev -> renameHistorySession(item.getSession()));
        menu.add(renameItem);

        JMenuItem exportItem = new JMenuItem("导出会话", AllIcons.Actions.Download);
        exportItem.addActionListener(ev -> exportSession(item.getSession()));
        menu.add(exportItem);

        JMenuItem deleteItem = new JMenuItem("删除会话", AllIcons.Actions.GC);
        deleteItem.addActionListener(ev -> deleteHistorySession(item.getSession()));
        menu  .add(deleteItem);

        menu.show(historyList, e.getX(), e.getY());
    }

    /** 历史面板右键「导出会话」：弹出文件选择器，后台读取并以 Markdown 形式导出对话数据 */
    private void exportSession(ChatSessionEntity session) {
        if (session == null) return;
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("导出会话为 Markdown");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        String baseName = sanitizeFileName(
                session.getName() != null && !session.getName().isEmpty() ? session.getName() : "会话");
        chooser.setSelectedFile(new java.io.File(baseName + ".md"));
        if (project != null && project.getBasePath() != null) {
            chooser.setCurrentDirectory(new java.io.File(project.getBasePath()));
        }
        int ret = chooser.showSaveDialog(SwingUtilities.getWindowAncestor(this));
        if (ret != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File selected = chooser.getSelectedFile();
        if (selected == null) return;
        java.io.File outFile = selected;
        if (!outFile.getName().toLowerCase().endsWith(".md")) {
            outFile = new java.io.File(outFile.getParentFile(), outFile.getName() + ".md");
        }
        final java.io.File finalOut = outFile;
        ThreadHelper.executeAsync(project,
                () -> {
                    java.util.Map<ChatMessageEntity, java.util.List<MessagePartEntity>> data =
                            DBChatHistoryRepository.getSessionMessagesWithParts(session.getId(), Integer.MAX_VALUE, 0);
                    return buildSessionMarkdown(session, data);
                },
                (md) -> {
                    if (md == null) {
                        Messages.showErrorDialog(this, "导出失败：无法读取会话数据。", "导出会话");
                        return;
                    }
                    try {
                        java.nio.file.Files.writeString(finalOut.toPath(),
                                md, java.nio.charset.StandardCharsets.UTF_8);
                        Messages.showInfoMessage(
                                "已将会话导出为 Markdown：\n" + finalOut.getAbsolutePath(), "导出会话");
                    } catch (java.io.IOException ex) {
                        Messages.showErrorDialog(this, "导出失败：" + ex.getMessage(), "导出会话");
                    }
                });
    }

    /** 把单个会话的全部消息渲染为 Markdown（含用户/助手正文、深度思考、工具调用） */
    private String buildSessionMarkdown(ChatSessionEntity session,
                                        java.util.Map<ChatMessageEntity, java.util.List<MessagePartEntity>> data) {
        StringBuilder sb = new StringBuilder();
        String title = session.getName() != null && !session.getName().isEmpty() ? session.getName() : "未命名会话";
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 会话ID：").append(session.getId()).append("\n");
        if (session.getCreatedAt() > 0) {
            sb.append("> 创建时间：")
              .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                      .format(new java.util.Date(session.getCreatedAt()))).append("\n");
        }
        sb.append("> 消息数：").append(data.size()).append("\n\n");
        sb.append("---\n\n");

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (java.util.Map.Entry<ChatMessageEntity, java.util.List<MessagePartEntity>> entry : data.entrySet()) {
            ChatMessageEntity rec = entry.getKey();
            java.util.List<MessagePartEntity> parts = entry.getValue();
            String roleLabel = Constant.ROLE_user.equals(rec.getRole()) ? "用户" : "助手";
            sb.append("### ").append(roleLabel).append("\n\n");
            if (rec.getCreatedAt() > 0) {
                sb.append("*").append(sdf.format(new java.util.Date(rec.getCreatedAt()))).append("*\n\n");
            }
            if (Constant.ROLE_user.equals(rec.getRole())) {
                sb.append(getPartContent(parts, "text")).append("\n\n");
                continue;
            }
            // assistant：按 parts 还原 深度思考 / 正文 / 工具调用 / 错误
            for (MessagePartEntity p : parts) {
                switch (p.getKind()) {
                    case "thinking":
                        sb.append("> **深度思考**\n\n");
                        if (p.getContent() != null && !p.getContent().isEmpty()) {
                            sb.append("> ").append(p.getContent().replace("\n", "\n> ")).append("\n\n");
                        }
                        break;
                    case "text":
                        if (p.getContent() != null && !p.getContent().isEmpty()) {
                            sb.append(p.getContent()).append("\n\n");
                        }
                        break;
                    case "tool":
                        java.util.Map<String, String> tm = parseToolMeta(p.getMeta());
                        String toolName = tm.getOrDefault("toolName", "tool");
                        String toolInput = tm.getOrDefault("toolInput", "");
                        String toolOutput = tm.getOrDefault("toolOutput", "");
                        sb.append("**工具调用：").append(toolName).append("**\n\n");
                        sb.append("```json\n").append(toolInput).append("\n```\n\n");
                        if (!toolOutput.isEmpty()) {
                            sb.append("输出：\n\n```\n").append(toolOutput).append("\n```\n\n");
                        }
                        break;
                    case "error":
                        sb.append("**错误：**\n\n");
                        sb.append(p.getContent() != null ? p.getContent() : "").append("\n\n");
                        break;
                }
            }
        }
        return sb.toString();
    }

    /** 解析 tool part 的 meta JSON，安全返回 toolName/toolInput/toolOutput */
    private java.util.Map<String, String> parseToolMeta(String meta) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (meta == null) return m;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            if (o.has("toolName")) m.put("toolName", o.get("toolName").getAsString());
            if (o.has("toolInput")) m.put("toolInput", extractToolInputString(o.get("toolInput")));
            if (o.has("toolOutput") && !o.get("toolOutput").isJsonNull()) {
                m.put("toolOutput", o.get("toolOutput").getAsString());
            }
        } catch (Exception ignored) {}
        return m;
    }

    /**
     * 兜底提取 toolInput 字符串：旧版 buildToolMetaJson 把字符串当 JSON 拼接写入，
     * 读回时变成 JsonObject——直接 getAsString() 会抛 UnsupportedOperationException。
     * 遇到 JsonObject/JsonArray 退回 toString()（即该 JSON 的字符串形式），
     * 让历史回显时仍能拿到完整 file_path 等参数。
     */
    private static String extractToolInputString(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return "{}";
        if (el.isJsonPrimitive()) {
            try { return el.getAsString(); } catch (Exception ignored) {}
        }
        return el.toString();
    }

    /** 文件名安全化：去除路径非法字符 */
    private static String sanitizeFileName(String name) {
        if (name == null) return "会话";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 历史面板右键「编辑标题」：弹输入对话框修改会话标题 */
    private void renameHistorySession(ChatSessionEntity session) {
        if (session == null) return;
        String current = session.getName() != null ? session.getName() : "";
        String newTitle = Messages.showInputDialog(project,
                "输入新的会话标题：", "编辑标题",
                Messages.getQuestionIcon(), current, null);
        if (newTitle == null) return;          // 取消
        if (newTitle.trim().isEmpty()) return; // 不允许空标题
        chatSessionManager.renameSession(session.getId(), newTitle.trim());
    }

    private void openSelectedHistorySession() {
        HistoryItem selected = historyList.getSelectedValue();
        if (selected != null && selected.getSession() != null) {
            // ★ 多标签隔离：从历史面板打开会话时，不在当前标签内切换，
            //   而是通知 TabManager 在新标签中打开，避免覆盖当前正在进行的会话
            String sid = selected.getSession().getId();
            String title = selected.getSession().getName();
            if (title == null || title.isEmpty()) title = "未命名会话";
            if (tabCallbacks != null) {
                tabCallbacks.onOpenHistorySession(sid, title);
            }
            showChatPanel();
        }
    }

    private void refreshHistoryList() {
        historyListModel.clear();
        historyListModel.addElement(new HistoryItem(null, "加载中...", ""));
        ThreadHelper.executeAsync(project,
                () -> DBChatHistoryRepository.listSessions(project.getBasePath()),
                (sessions) -> {
                    System.out.println("sessions = " + sessions);
                    historyListModel.clear();
                    if (sessions.isEmpty()) {
                        historyListModel.addElement(new HistoryItem(null, "暂无历史会话", ""));
                        return;
                    }
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd HH:mm");
                    for (ChatSessionEntity s : sessions) {
                        String title = s.getName() != null && !s.getName().isEmpty() ? s.getName() : "未命名会话";
                        String time = s.getCreatedAt() > 0 ? df.format(s.getCreatedAt()) : "";
                        String info = s.getChatMessageCount() + " 轮对话  ·  " + time;
                        historyListModel.addElement(new HistoryItem(s, title, info));
                    }
                });
    }

    private void deleteHistorySession(ChatSessionEntity session) {
        if (session == null) return;
        boolean deleted = chatSessionManager.deleteSession(session);
        if (deleted) {
            refreshHistoryList();
        }
    }

    private void deleteAllSessions() {
        boolean deleted = chatSessionManager.deleteAllSessions();
        if (deleted) {
            refreshHistoryList();
        }
    }


    static class HistoryCellRenderer extends JPanel implements ListCellRenderer<HistoryItem> {
        private final JBLabel titleLabel;
        private final JBLabel infoLabel;

        HistoryCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(JBUI.Borders.empty(10, 12));

            JBPanel<?> textPanel = new JBPanel<>(new GridLayout(2, 1, 0, 4));
            textPanel.setOpaque(false);

            titleLabel = new JBLabel();
            titleLabel.setFont(JBUI.Fonts.label(13));
            textPanel.add(titleLabel);

            infoLabel = new JBLabel();
            infoLabel.setFont(JBUI.Fonts.label(11));
            infoLabel.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
            textPanel.add(infoLabel);

            add(textPanel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HistoryItem> list,
                                                      HistoryItem value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            titleLabel.setText(value.getTitle());
            infoLabel.setText(value.getInfo());

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                titleLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                titleLabel.setForeground(list.getForeground());
            }
            setOpaque(true);
            return this;
        }
    }

    private void saveSessionConfig() {
        chatSessionManager.saveSessionConfig();
    }

    private void clearSessionConfig(String sessionId) {
        chatSessionManager.clearSessionConfig(sessionId);
    }

    public void switchToSession(String sessionId) {
        if (sessionId == null || sessionId.equals(chatSessionManager.getCurrentSessionId())) return;

        // 重置取消标志
        toolOrchestrator.resetCancelled();

        // 委托给会话管理器处理会话切换逻辑；会话激活后由 onActivateSession 回调通知 TabManager
        chatSessionManager.switchToSession(sessionId);
    }

    /**
     * 加载指定历史会话到当前 ChatPanel（用于从历史面板在新标签中打开会话）。
     * 与 switchToSession 不同：不检查 currentSessionId 是否相同（因为新标签的 currentSessionId 为 null）。
     */
    public void loadHistorySession(String sessionId) {
        if (sessionId == null) return;
        toolOrchestrator.resetCancelled();
        chatSessionManager.switchToSession(sessionId);
    }

    /** 流结束后执行被延迟的会话切换（用户在本轮流进行中点了其它会话标签） */
    private void maybeDeferredSwitch() {
        // ★ 多标签隔离：不再需要延迟切换，切换标签不会中断流
        // 保留方法签名兼容旧调用点，但空实现
    }

    // ── ChatSessionManager.UiCallbacks 接口实现 ──

    @Override
    public void clearMessages() {
        if (chatWebView != null) {
            chatWebView.clearMessages();
        }
    }

    @Override
    public void clearTodoList() {
        taskDiffTabPanel.clearTodos();
    }

    @Override
    public void clearAllSessionState() {
        // 清空todo管理器状态：只清 UI/内存，不落库。
        todoManager.clearUiOnly();
        // 清空文件变更内存状态
        planFileStates.clear();
        planFilesWithCards.clear();
        // 重置完成标志
        todoCompletionSummaryAppended = false;
        // 清空TaskDiffTabPanel（任务+文件变更UI）并隐藏
        taskDiffTabPanel.clearAll();
        refreshCenterLayout();
    }

    @Override
    public void prependHistoryRecords(java.util.Map<ChatMessageEntity, java.util.List<com.codepal.model.MessagePartEntity>> records) {
        if (records == null || records.isEmpty()) return;
        // 构造 replay 记录 JSON 数组，交给 JS prependHistoryReplay 复用实时渲染链路前置插入
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map.Entry<ChatMessageEntity, java.util.List<com.codepal.model.MessagePartEntity>> entry : records.entrySet()) {
            String recJson = buildReplayRecordJson(entry.getKey(), entry.getValue());
            if (recJson == null || recJson.isEmpty()) continue;
            if (!first) sb.append(",");
            sb.append(recJson);
            first = false;
        }
        sb.append("]");
        chatWebView.prependHistoryReplay(sb.toString());
    }

    @Override
    public void setHasMoreHistory(boolean hasMore) {
        chatWebView.setHasMoreHistory(hasMore);
    }

    @Override
    public void removeMessagesByRound(int qaRound) {
        chatWebView.removeMessagesByRound(qaRound);
    }

    @Override
    public void setQaRound(int qaRound) {
        chatWebView.setQaRound(qaRound);
        updateContextCircleFromHistory();
    }

    @Override
    public void onHistoryLoaded() {
        iterationGuard.detectAndRecoverCrash();
    }

    @Override
    public void onSessionRenamed(String title) {
        // 标题改写后刷新历史会话列表
        refreshHistoryList();
        // 通知 TabManager 更新标签标题
        if (tabCallbacks != null) {
            tabCallbacks.onSessionTitleChanged(title);
        }
    }

    @Override
    public void onActivateSession(String sessionId, String title) {
        // 会话被激活（新建/切换/启动加载）：通知 TabManager 更新标签标题。
        final String sid = sessionId;
        final String t = title;
        ApplicationManager.getApplication().invokeLater(() -> {
            // 保存上一个会话的输入框草稿
            if (activeInputSessionId != null && inputField != null) {
                inputDraftBySession.put(activeInputSessionId, inputField.getText());
            }
            activeInputSessionId = sid;
            // 恢复新会话的草稿（无则空）
            String draft = inputDraftBySession.getOrDefault(sid, "");
            inputField.setText(draft);
            inputField.setCaretPosition(draft.length());
            // 通知 TabManager 更新标签标题
            if (tabCallbacks != null) {
                tabCallbacks.onSessionTitleChanged(t);
            }
            // 绑定当前会话的待办
            if (todoManager != null) todoManager.bindSession(sid);
        });
    }

    @Override
    public JComboBox<?> getModelCombo() {
        return modelCombo;
    }

    @Override
    public JComboBox<String> getAgentCombo() {
        return agentCombo;
    }

    @Override
    public JComboBox<String> getModeCombo() {
        return modeCombo;
    }

    @Override
    public boolean showConfirmDialog(String message, String title) {
        int result = Messages.showYesNoDialog(
                project,
                message,
                title,
                Messages.getWarningIcon()
        );
        return result == Messages.YES;
    }

    /**
     * 渲染一条持久化的消息记录到 ChatWebView
     */
    // ── 实现 ChatSessionManager.UiCallbacks ──

    public void renderMessageWithParts(ChatMessageEntity rec, List<com.codepal.model.MessagePartEntity> parts) {
        if (rec == null) return;

        // 解析 meta，判断是否为压缩消息/摘要消息
        boolean isCompressed = false;
        boolean isSummary = false;
        int compressedCount = 0;
        if (rec.getMeta() != null && !rec.getMeta().isBlank()) {
            try {
                com.google.gson.JsonObject metaObj = com.google.gson.JsonParser.parseString(rec.getMeta()).getAsJsonObject();
                isCompressed = metaObj.has("compressed") && metaObj.get("compressed").getAsBoolean();
                isSummary = metaObj.has("compressed_summary") && metaObj.get("compressed_summary").getAsBoolean();
                if (metaObj.has("compressed_count")) {
                    compressedCount = metaObj.get("compressed_count").getAsInt();
                }
            } catch (Exception ignored) {}
        }

        // 摘要消息：渲染为可折叠的系统卡片，不显示为普通用户消息
        if (isSummary) {
            String content = parts != null ? getPartContent(parts, "text") : "";
            chatWebView.addSummaryCard("历史对话摘要", compressedCount, content);
            return;
        }

        if (Constant.ROLE_user.equals(rec.getRole())) {
            // 过滤 SYSTEM AUTO / SYSTEM NUDDGE 消息（不渲染到消息流）
            String content = parts != null ? getPartContent(parts, "text") : "";
            if (content.startsWith(com.codepal.session.IterationGuard.SYSTEM_AUTO_PREFIX)
                    || content.startsWith("[SYSTEM NUDDGE]")) return;
            chatWebView.resetAiStream();
            addHistoryUserMessage(content, rec.getQaRound(), rec.getId());
            if (isCompressed) {
                chatWebView.markLastMessageCompressed();
            }
        } else if (Constant.ROLE_assistant.equals(rec.getRole())) {
            if (parts == null || parts.isEmpty()) return;
            // 副作用：重放 todo 工具状态（历史恢复需要）
            for (com.codepal.model.MessagePartEntity p : parts) {
                if ("tool".equals(p.getKind())) {
                    replayTodoIfNeeded(p);
                }
            }
            // 统一走实时渲染链路回放：把合并后的 assistant 段（含 parts）传给 replayHistory，
            // JS 端复用 mkFrame/appendReasoning/startAiStream/finalizeAiMessage/insertToolCard，
            // 与聊天同一套 DOM/样式，彻底消除历史/实时两套渲染漂移。整条仅 1 次 CEF 调用。
            String recordJson = buildReplayRecordJson(rec, parts);
            if (recordJson != null && !recordJson.isEmpty()) {
                chatWebView.replayHistory(recordJson);
            }
            if (isCompressed) {
                chatWebView.markLastMessageCompressed();
            }
        }
    }

    private void renderToolPart(com.codepal.model.MessagePartEntity p) {
        String meta = p.getMeta();
        if (meta == null) return;
        try {
            com.google.gson.JsonObject metaObj = com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            String toolName = metaObj.has("toolName") ? metaObj.get("toolName").getAsString() : "tool";
            String toolInput = metaObj.has("toolInput") ? extractToolInputString(metaObj.get("toolInput")) : "{}";
            String toolOutput = metaObj.has("toolOutput") && !metaObj.get("toolOutput").isJsonNull()
                    ? metaObj.get("toolOutput").getAsString() : "";

            // todo 工具：重放任务列表状态（恢复历史会话时需要）
            if ("todo".equals(toolName)) {
                ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                ChatMessage.ToolCall.Function fn = new ChatMessage.ToolCall.Function();
                fn.setName("todo");
                fn.setArguments(toolInput);
                tc.setFunction(fn);
                todoManager.replayTodoTool(tc);
                return;
            }

            String displayTitle = formatToolTitleWithArgs(toolName, toolInput);
            String detail = toolOutput.length() > 10000 ? toolOutput.substring(0, 10000) + "\n\n... (内容过长，已截断)" : toolOutput;
            // 历史回显工具卡片统一显示为已完成；详情用 markdown 渲染
            chatWebView.appendToolCardHtml(buildToolCardHtml(displayTitle, "completed", detail));
        } catch (Exception ignored) {}
    }

    /** 历史回显时重放 todo 工具（仅副作用，不渲染卡片；卡片由 buildMessageHtmlWithParts 负责） */
    private void replayTodoIfNeeded(com.codepal.model.MessagePartEntity p) {
        String meta = p.getMeta();
        if (meta == null) return;
        try {
            com.google.gson.JsonObject metaObj = com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            String toolName = metaObj.has("toolName") ? metaObj.get("toolName").getAsString() : "tool";
            if (!"todo".equals(toolName)) return;
            String toolInput = metaObj.has("toolInput") ? extractToolInputString(metaObj.get("toolInput")) : "{}";
            ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
            ChatMessage.ToolCall.Function fn = new ChatMessage.ToolCall.Function();
            fn.setName("todo");
            fn.setArguments(toolInput);
            tc.setFunction(fn);
            todoManager.replayTodoTool(tc);
        } catch (Exception ignored) {}
    }

    /** 构建工具卡片 HTML（支持 markdown 内容渲染） */
    private String buildToolCardHtml(String title, String status, String detail) {
        String statusLabel = "completed".equals(status) ? "已完成" : "执行中";
        String detailHtml;
        if (detail != null && !detail.isEmpty()) {
            // 用 markdown 渲染详情，保持格式
            String rendered = MarkdownUtil.toHtmlFragment(detail);
            detailHtml = "<div class=\"tool-card-body\">" + rendered + "</div>";
        } else {
            detailHtml = "";
        }
        return "<div class=\"tool-card\"><div class=\"tool-card-hdr\""
            + " onclick=\"this.parentElement.classList.toggle('open');\">"
            + "<span class=\"tool-card-icon\">" + getToolIconHtml(title) + "</span>"
            + "<span class=\"tool-card-name\">" + escHtml(title) + "</span>"
            + "<span class=\"tool-card-status\">" + statusLabel + "</span>"
            + "<span class=\"tool-card-toggle\">&#9662;</span></div>"
            + detailHtml + "</div>";
    }

    private static String getPartContent(List<com.codepal.model.MessagePartEntity> parts, String kind) {
        if (parts == null) return "";
        for (com.codepal.model.MessagePartEntity p : parts) {
            if (kind.equals(p.getKind()) && p.getContent() != null) return p.getContent();
        }
        return "";
    }

    /**
     * 把一条（合并后的）消息 + parts 构造成 replayHistory 所需的 JSON 记录（user / assistant 通吃）。
     * - user：html/raw 为文本内容（与初始加载 addHistoryUserMessage 形态一致）
     * - assistant：正文 text 预渲染 bodyHtml；tool 解析 meta 取 toolName/toolOutput（与实时接口一致）
     * 返回 null 表示应过滤（如 SYSTEM AUTO 消息）。
     */
    private String buildReplayRecordJson(ChatMessageEntity rec,
                                          List<com.codepal.model.MessagePartEntity> parts) {
        if (Constant.ROLE_user.equals(rec.getRole())) {
            String content = parts != null ? getPartContent(parts, "text") : "";
            if (content.startsWith(com.codepal.session.IterationGuard.SYSTEM_AUTO_PREFIX)
                    || content.startsWith("[SYSTEM NUDDGE]")) return null;
            JsonObject o = new JsonObject();
            o.addProperty("role", "user");
            o.addProperty("qaRound", rec.getQaRound());
            o.addProperty("timeStr", fmtTime(rec.getCreatedAt()));
            o.addProperty("html", content);
            o.addProperty("raw", content);
            o.addProperty("msgId", rec.getId());
            return o.toString();
        }
        if (!Constant.ROLE_assistant.equals(rec.getRole())) return null;
        JsonObject o = new JsonObject();
        o.addProperty("role", "assistant");
        o.addProperty("qaRound", rec.getQaRound());
        o.addProperty("timeStr", fmtTime(rec.getCreatedAt()));
        JsonArray arr = new JsonArray();
        for (com.codepal.model.MessagePartEntity p : parts) {
            String kind = p.getKind();
            if (kind == null) continue;
            JsonObject po = new JsonObject();
            if ("thinking".equals(kind)) {
                po.addProperty("kind", "thinking");
                po.addProperty("content", p.getContent() != null ? p.getContent() : "");
            } else if ("text".equals(kind)) {
                String c = p.getContent() != null ? p.getContent() : "";
                po.addProperty("kind", "text");
                po.addProperty("raw", c);
                po.addProperty("bodyHtml", MarkdownUtil.toHtmlFragment(c));
            } else if ("tool".equals(kind)) {
                java.util.Map<String, String> tm = parseToolMeta(p.getMeta());
                String tmName = tm.getOrDefault("toolName", "tool");
                String tmInput = tm.getOrDefault("toolInput", "{}");
                po.addProperty("kind", "tool");
                po.addProperty("toolName", tmName);
                po.addProperty("toolInput", tmInput);
                po.addProperty("toolOutput", tm.getOrDefault("toolOutput", ""));
                po.addProperty("toolTitle", formatToolTitleWithArgs(tmName, tmInput));
            } else if ("error".equals(kind)) {
                po.addProperty("kind", "error");
                po.addProperty("content", p.getContent() != null ? p.getContent() : "");
            } else {
                continue;
            }
            arr.add(po);
        }
        o.add("parts", arr);
        return o.toString();
    }

    /** @deprecated use renderMessageWithParts */
    @Deprecated
    public void renderMessageRecord(ChatMessageEntity rec) {
        if (Constant.ROLE_user.equals(rec.getRole())) {
            if (rec.getContent() != null && rec.getContent().startsWith(com.codepal.session.IterationGuard.SYSTEM_AUTO_PREFIX)) {
                return;
            }
            chatWebView.resetAiStream();
            addHistoryUserMessage(rec.getContent(), rec.getQaRound(), rec.getId());
        } else if (Constant.ROLE_assistant.equals(rec.getRole())) {
            renderAssistantMessage(rec.getContent(), rec.getReasoningContent(), rec.getToolCallsJson());
        } else if (Constant.ROLE_tool.equals(rec.getRole())) {
            String toolName = rec.getName() != null ? rec.getName() : "tool";
            if ("todo".equals(toolName)) return;
            String toolResult = rec.getContent() != null ? rec.getContent() : "";
            String detail = toolResult.length() > 10000 ? toolResult.substring(0,10000) + "\n\n... (内容过长，已截断)" : toolResult;
            String displayTitle = rec.getToolCallsJson() != null && !rec.getToolCallsJson().isEmpty()
                    ? rec.getToolCallsJson()
                    : formatToolTitle(toolName);
            chatWebView.appendToolCard(displayTitle, "completed", detail);
        }
    }

    // ── 懒加载更早的历史消息 ──

    /**
     * 滚动到顶部时触发，从 DB 加载更早的消息并向前插入。
     */
    private void loadMoreHistory() {
        chatSessionManager.loadMoreHistory();
    }

    /**
     * 删除指定 QA 轮次的所有消息。
     * Java 侧弹出确认框（替代 JCEF 中不可用的 confirm()），确认后同步清理 DOM + 内存 + DB。
     * JBCefJSQuery 回调在非 EDT 线程执行，需要切到 EDT 弹确认框。
     */
    private void deleteQaMessages(int qaRound) {
        chatSessionManager.deleteQaMessages(qaRound);
    }

    /** 在 IDEA 编辑器中打开文件（支持 file_path:line 与 file_path#start-end 行号范围） */
    private void openFileInEditor(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        String path = filePath.trim();
        int line = 0;
        int endLine = 0;
        // 解析行号范围：path/to/File.java#263-308 或 #263
        // （工具卡片标题「读取 A.java L1-100」点击时，JS 端将行号塞在 # 后）
        int hashIdx = path.lastIndexOf('#');
        if (hashIdx > 0 && hashIdx < path.length() - 1) {
            String range = path.substring(hashIdx + 1);
            path = path.substring(0, hashIdx);
            int dash = range.indexOf('-');
            try {
                if (dash > 0) {
                    line = Integer.parseInt(range.substring(0, dash).trim());
                    endLine = Integer.parseInt(range.substring(dash + 1).trim());
                } else {
                    line = Integer.parseInt(range.trim());
                }
            } catch (NumberFormatException ignored) {}
        }
        // 兼容旧格式 :line（单行）
        if (line == 0) {
            int colonIdx = path.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < path.length() - 1) {
                String after = path.substring(colonIdx + 1);
                try {
                    line = Integer.parseInt(after.trim());
                    path = path.substring(0, colonIdx);
                } catch (NumberFormatException ignored) {}
            }
        }
        // 去除可能包裹的引号或反引号
        path = path.replaceAll("^[`'\"]+|[`'\"]+$", "").trim();
        final String targetPath = path;
        final int targetLine = line;
        final int targetEndLine = endLine;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                java.io.File ioFile = new java.io.File(targetPath);
                if (!ioFile.isAbsolute() && project.getBasePath() != null) {
                    ioFile = new java.io.File(project.getBasePath(), targetPath);
                }
                if (!ioFile.exists()) {
                    // 尝试在项目中按相对路径查找
                    VirtualFile found = project.getBaseDir() != null
                            ? project.getBaseDir().findFileByRelativePath(targetPath) : null;
                    // ★ 工具卡片标题常传裸文件名（如「KeyEquipmentThresholdJudgeJob.java」），
                    //   ioFile/相对路径都找不到时，按文件名在项目范围内查找首个匹配
                    if (found == null || found.isDirectory()) {
                        VirtualFile byName = findProjectFileByName(targetPath);
                        if (byName != null && !byName.isDirectory()) found = byName;
                    }
                    if (found != null && !found.isDirectory()) {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                                .openFile(found, true);
                        if (targetLine > 0) {
                            navigateToLine(targetLine, targetEndLine);
                        }
                        return;
                    }
                    return;
                }
                VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
                if (vf == null) {
                    LocalFileSystem.getInstance().refreshIoFiles(
                            java.util.Collections.singletonList(ioFile), false, true, null);
                    vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
                }
                if (vf != null && !vf.isDirectory()) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                            .openFile(vf, true);
                    if (targetLine > 0) {
                        navigateToLine(targetLine, targetEndLine);
                    }
                }
            } catch (Exception ex) {
                System.err.println("[openFileInEditor] 打开文件失败: " + targetPath + " — " + ex.getMessage());
            }
        });
    }

    /**
     * 按文件名在项目范围内查找首个匹配（用于工具卡片标题的裸文件名跳转）。
     * 仅取基名，避免误传目录路径时匹配失败；ReadAction 包裹 FilenameIndex 索引访问。
     */
    private VirtualFile findProjectFileByName(String fileName) {
        if (fileName == null || fileName.isBlank() || project == null) return null;
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.isBlank()) return null;
        final String finalBase = base;
        return ReadAction.compute(() -> {
            java.util.Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
                    finalBase, GlobalSearchScope.projectScope(project));
            return files.isEmpty() ? null : files.iterator().next();
        });
    }

    /** 跳转到指定行；endLine>line 时选中 [line, endLine] 范围 */
    private void navigateToLine(int line, int endLine) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.intellij.openapi.editor.Editor editor =
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor != null) {
                    com.intellij.openapi.editor.Document doc = editor.getDocument();
                    if (line > 0 && line <= doc.getLineCount()) {
                        int startOffset = doc.getLineStartOffset(line - 1);
                        if (endLine > line && endLine <= doc.getLineCount()) {
                            // 选中 [start, endLine 末行行尾]
                            int endOffset = doc.getLineEndOffset(endLine - 1);
                            editor.getSelectionModel().setSelection(startOffset, endOffset);
                        }
                        editor.getCaretModel().moveToOffset(startOffset);
                        editor.getScrollingModel().scrollToCaret(
                                com.intellij.openapi.editor.ScrollType.CENTER);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    /** 封装：给消息打 round 标记后加入对话 */
    private void addMsgToConversation(com.codepal.model.ChatMessage msg) {
        chatSessionManager.addMsgWithRound(msg);
    }

    /** 构建 tool part 的 meta JSON（统一使用 completed 状态） */
    private String buildToolMetaJson(String toolName, String toolInput, String toolOutput) {
        // ★ 改用 Gson 序列化（而非手拼 JSON 字符串）：
        //   旧实现 `",\"toolInput\":" + input` 直接拼接，input 作为 JSON 字符串（含嵌套双引号）
        //   未加引号/未转义，被解析为嵌套对象 → 读回时 getAsString() 抛异常被吞 → toolInput 退回 "{}"
        //   → 历史回显工具卡片只剩 "读取"/"查看大纲" 裸名，文件路径全丢。
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("toolName", toolName != null ? toolName : "tool");
        o.addProperty("toolInput", toolInput != null ? toolInput : "{}");
        o.addProperty("toolOutput", toolOutput);
        o.addProperty("toolStatus", "completed");
        return o.toString();
    }

    /** 更新已有的 pending tool part，填入 toolOutput 并标记为 completed */
    private void updateToolPartResult(String partId, String toolName, String toolInput, String toolOutput) {
        String meta = buildToolMetaJson(toolName, toolInput, toolOutput);
        chatSessionManager.updatePartMetaAndStatus(partId, meta, "completed");
        // ★ 工具结果返回：恢复看门狗计时（pet + 取消暂停）
        streamRenderController.resumeWatchdog();
    }

    /** @deprecated 使用 updateToolPartResult 更新 pending part，不要新建重复 part */
    @Deprecated
    private void saveToolResultPart(String assistantMsgId, String toolName, String toolInput, String toolOutput) {
        com.codepal.model.MessagePartEntity part = com.codepal.model.MessagePartEntity.tool(
            assistantMsgId, chatSessionManager.getCurrentSessionId(),
            toolName, toolInput != null ? toolInput : "{}", toolOutput, "completed", 0
        );
        chatSessionManager.persistPart(part);
    }

    /** 构建单条消息（含 parts）的 HTML，用于批量前置插入 */
    private String buildMessageHtmlWithParts(ChatMessageEntity rec, List<com.codepal.model.MessagePartEntity> parts) {
        if (Constant.ROLE_user.equals(rec.getRole())) {
            String content = getPartContent(parts, "text");
            if (content.startsWith(com.codepal.session.IterationGuard.SYSTEM_AUTO_PREFIX)) return "";
            return "<div class=\"msg msg-user\"><div class=\"row row-user\">"
                + "<div class=\"avatar avatar-user\">U</div>"
                + "<div class=\"bubble-wrap\">"
                + "<div class=\"bubble bubble-user\">" + escHtml(content) + "</div>"
                + "<div class=\"ts ts-user\">" + fmtTime(rec.getCreatedAt()) + "</div>"
                + "</div></div></div>";
        }
        if (Constant.ROLE_assistant.equals(rec.getRole())) {
            if (parts == null || parts.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"msg msg-ai\"><div class=\"row\">"
                + "<div class=\"avatar avatar-ai\">L</div>"
                + "<div class=\"bubble-wrap\">");

            for (com.codepal.model.MessagePartEntity p : parts) {
                switch (p.getKind()) {
                    case "thinking":
                        if (p.getContent() != null && !p.getContent().isEmpty()) {
                            // 与实时 buildRsnBlock 对齐：svg 箭头（非字符 ▶），finalize 态无"已完成"标签
                            sb.append("<div class=\"rsn\"><div class=\"rsn-hdr\" onclick=\"var b=this.nextElementSibling;")
                              .append("var a=this.querySelector('.rsn-arrow');var o=b.classList.toggle('open');")
                              .append("a.classList.toggle('open',o);\">")
                              .append("<span class=\"rsn-label\">深度思考</span>")
                              .append("<span class=\"rsn-arrow\"><svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\">")
                              .append("<path d=\"M676.1 512L232.3 116.1l57.9-51.7L791.7 512 290.2 959.6l-57.9-51.7L676.1 512z\" p-id=\"1683\"></path></svg></span>")
                              .append("</div>")
                              .append("<div class=\"rsn-body\">").append(escHtml(p.getContent())).append("</div></div>");
                        }
                        break;
                    case "text":
                        if (p.getContent() != null && !p.getContent().isEmpty()) {
                            String bodyHtml = MarkdownUtil.toHtmlFragment(p.getContent());
                            sb.append("<div class=\"bubble bubble-ai\">").append(bodyHtml).append("</div>");
                        }
                        break;
                    case "tool":
                        sb.append(buildToolPartHtml(p));
                        break;
                    case "error":
                        sb.append("<div class=\"tool-card\"><div class=\"tool-card-hdr\">")
                          .append("<span class=\"tool-card-icon\"><svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z\"/><path d=\"M12 9v4\"/><path d=\"M12 17h.01\"/></svg></span>")
                          .append("<span class=\"tool-card-name\">错误</span></div>")
                          .append("<div class=\"tool-card-body\">")
                          .append(escHtml(p.getContent() != null ? p.getContent() : ""))
                          .append("</div></div>");
                        break;
                }
            }

            sb.append("<div class=\"ts ts-ai\">").append(fmtTime(rec.getCreatedAt())).append("</div>");
            sb.append("</div></div></div>");
            return sb.toString();
        }
        return "";
    }

    private String buildToolPartHtml(com.codepal.model.MessagePartEntity p) {
        String meta = p.getMeta();
        if (meta == null) return "";
        try {
            com.google.gson.JsonObject metaObj = com.google.gson.JsonParser.parseString(meta).getAsJsonObject();
            String toolName = metaObj.has("toolName") ? metaObj.get("toolName").getAsString() : "tool";
            String toolInput = metaObj.has("toolInput") ? extractToolInputString(metaObj.get("toolInput")) : "{}";
            String toolOutput = metaObj.has("toolOutput") && !metaObj.get("toolOutput").isJsonNull()
                    ? metaObj.get("toolOutput").getAsString() : "";
            if ("todo".equals(toolName)) return "";
            String title = formatToolTitleWithArgs(toolName, toolInput);
            String detail = toolOutput.length() > 10000
                ? toolOutput.substring(0, 10000) + "\n\n... (内容过长，已截断)" : toolOutput;
            String detailHtml = MarkdownUtil.toHtmlFragment(detail);
            return "<div class=\"tool-card\"><div class=\"tool-card-hdr\""
                + " onclick=\"this.parentElement.classList.toggle('open');\">"
                + "<span class=\"tool-card-icon\">" + getToolIconHtml(toolName) + "</span>"
                + "<span class=\"tool-card-name\">" + escHtml(title) + "</span>"
                + "<span class=\"tool-card-status\">已完成</span>"
                + "<span class=\"tool-card-toggle\">&#9662;</span></div>"
                + "<div class=\"tool-card-body\">" + detailHtml + "</div></div>";
        } catch (Exception ignored) {
            return "";
        }
    }

    /** @deprecated use buildMessageHtmlWithParts */
    @Deprecated
    private String buildMessageHtml(ChatMessageEntity rec) {
        if (Constant.ROLE_user.equals(rec.getRole())) {
            // 过滤 SYSTEM AUTO 消息，不渲染到消息流中
            if (rec.getContent() != null && rec.getContent().startsWith(com.codepal.session.IterationGuard.SYSTEM_AUTO_PREFIX)) {
                return "";
            }
            String content = rec.getContent() != null ? rec.getContent() : "";
            return "<div class=\"msg msg-user\"><div class=\"row row-user\">"
                + "<div class=\"avatar avatar-user\">U</div>"
                + "<div class=\"bubble-wrap\">"
                + "<div class=\"bubble bubble-user\">" + escHtml(content) + "</div>"
                + "<div class=\"ts ts-user\">" + fmtTime(rec.getCreatedAt()) + "</div>"
                + "</div></div></div>";
        }
        if (Constant.ROLE_assistant.equals(rec.getRole())) {
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"msg msg-ai\"><div class=\"row\">"
                + "<div class=\"avatar avatar-ai\">L</div>"
                + "<div class=\"bubble-wrap\">");

            String reasoning = rec.getReasoningContent();
            if (reasoning != null && !reasoning.isEmpty()) {
                sb.append("<div class=\"rsn\"><div class=\"rsn-hdr\" onclick=\"var b=this.nextElementSibling;")
                  .append("var a=this.querySelector('.rsn-arrow');var o=b.classList.toggle('open');")
                  .append("a.classList.toggle('open',o);\">")
                  .append("<span class=\"rsn-label\">深度思考</span>")
                  .append("<span class=\"rsn-arrow\">&#9654;</span>")
                  .append("<span class=\"rsn-status\">已完成</span></div>")
                  .append("<div class=\"rsn-body\">").append(escHtml(reasoning)).append("</div></div>");
            }

            String content = rec.getContent();
            if (content != null && !content.isEmpty()) {
                String bodyHtml = MarkdownUtil.toHtmlFragment(content);
                sb.append("<div class=\"bubble bubble-ai\">").append(bodyHtml).append("</div>");
            }

            sb.append("<div class=\"ts ts-ai\">").append(fmtTime(rec.getCreatedAt())).append("</div>");
            sb.append("</div></div></div>");
            return sb.toString();
        }
        if (Constant.ROLE_tool.equals(rec.getRole())) {
            String toolName = rec.getName() != null ? rec.getName() : "tool";
            if ("todo".equals(toolName)) return "";
            String toolResult = rec.getContent() != null ? rec.getContent() : "";
            String detail = toolResult.length() > 10000
                ? toolResult.substring(0, 10000) + "\n\n... (内容过长，已截断)" : toolResult;
            String title = rec.getToolCallsJson() != null && !rec.getToolCallsJson().isEmpty()
                ? rec.getToolCallsJson() : formatToolTitle(toolName);
            return "<div class=\"tool-card\"><div class=\"tool-card-hdr\""
                + " onclick=\"this.parentElement.classList.toggle('open');\">"
                + "<span class=\"tool-card-icon\">" + getToolIconHtml(toolName) + "</span>"
                + "<span class=\"tool-card-name\">" + escHtml(title) + "</span>"
                + "<span class=\"tool-card-status\">已完成</span>"
                + "<span class=\"tool-card-toggle\">&#9662;</span></div>"
                + "<div class=\"tool-card-body\">" + escHtml(detail) + "</div></div>";
        }
        return "";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 工具卡片图标：与实时 JS getToolIcon 对齐，按类型返回 svg/emoji（避免历史回显固定 ●） */
    private static String getToolIconHtml(String name) {
        String n = name == null ? "" : name.toLowerCase();
        String ask = "<svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\" style=\"stroke:none\"><path d=\"M606.354286 287.451429a119.222857 119.222857 0 0 1 36.571428 91.062857 123.611429 123.611429 0 0 1-24.137143 73.142857c-8.045714 9.508571-25.234286 25.965714-52.297142 50.834286a137.142857 137.142857 0 0 0-33.28 36.571428 92.891429 92.891429 0 0 0-13.897143 50.468572v19.382857h-40.228572v-19.382857a119.588571 119.588571 0 0 1 11.702857-52.662858 250.514286 250.514286 0 0 1 60.708572-73.142857c14.994286-14.628571 25.6-24.868571 31.085714-31.451428a96.914286 96.914286 0 0 0-4.022857-123.245715 98.377143 98.377143 0 0 0-73.142857-25.234285 91.428571 91.428571 0 0 0-78.994286 36.571428 134.217143 134.217143 0 0 0-24.137143 84.48H362.057143a162.377143 162.377143 0 0 1 36.571428-109.714285 133.485714 133.485714 0 0 1 107.885715-44.982858 137.874286 137.874286 0 0 1 99.84 37.302858z m-84.114286 387.291428a30.354286 30.354286 0 0 1 9.874286 23.771429 32.548571 32.548571 0 0 1-9.874286 23.771428 36.571429 36.571429 0 0 1-23.771429 9.142857 29.988571 29.988571 0 0 1-23.04-9.874285 29.257143 29.257143 0 0 1-10.605714-23.04 28.525714 28.525714 0 0 1 10.605714-23.771429 29.257143 29.257143 0 0 1 23.04-9.142857 31.817143 31.817143 0 0 1 23.771429 9.142857z\" fill=\"#dbdbdb\"></path><path d=\"M512 1024a512 512 0 1 1 512-512 512 512 0 0 1-512 512z m0-987.428571a475.428571 475.428571 0 1 0 475.428571 475.428571A475.428571 475.428571 0 0 0 512 36.571429z\" fill=\"#dbdbdb\"></path></svg>";
        String eye = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/></svg>";
        String search = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"11\" cy=\"11\" r=\"8\"/><path d=\"m21 21-4.3-4.3\"/></svg>";
        String file = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z\"/><path d=\"M14 2v4a2 2 0 0 0 2 2h4\"/><path d=\"M16 13H8\"/><path d=\"M16 17H8\"/><path d=\"M10 9H8\"/></svg>";
        String pen = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z\"/><path d=\"m15 5 4 4\"/></svg>";
        String trash = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M3 6h18\"/><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"/></svg>";
        String term = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><polyline points=\"4 17 10 11 4 5\"/><line x1=\"12\" x2=\"20\" y1=\"19\" y2=\"19\"/></svg>";
        String dot = "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"12\" cy=\"12\" r=\"3\"/></svg>";
        String todo = "<svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\" style=\"fill:#bfbfbf;stroke:none\"><path d=\"M512 695.466667l-128-123.733334 29.866667-29.866666 93.866666 93.866666 179.2-179.2 29.866667 29.866667-204.8 209.066667zM341.333333 298.666667h384V213.333333h42.666667v85.333334h128v554.666666H170.666667V298.666667h128V213.333333h42.666666v85.333334zM213.333333 341.333333v469.333334h640V341.333333H213.333333z\"/></svg>";
        String newfile = "<svg viewBox=\"0 0 1024 1024\" xmlns=\"http://www.w3.org/2000/svg\" style=\"fill:#999999;stroke:none\"><path d=\"M512 1024C229.674667 1024 0 794.304 0 511.957333S229.674667 0 512 0c282.389333 0 512 229.696 512 512.042667S794.389333 1024 512 1024z m0-989.589333c-263.338667 0-477.589333 214.208-477.589333 477.546666 0 263.36 214.250667 477.632 477.589333 477.632 263.338667 0 477.589333-214.272 477.589333-477.632C989.589333 248.618667 775.338667 34.410667 512 34.410667z\" fill=\"#999999\"></path><path d=\"M545.152 475.84v-207.978667a34.688 34.688 0 0 0-69.333333 0v208h-207.957334a34.688 34.688 0 0 0 0 69.312h207.978667v207.978667a34.688 34.688 0 0 0 69.333333 0v-208h207.957334a34.688 34.688 0 1 0 0-69.312h-207.978667z\" fill=\"#999999\"></path></svg>";
        // view / read / 查看图片 / 读取代码 / 查看大纲 → 眼睛；list_files / 浏览目录 → 眼睛；ask_user_question → 问号圆圈
        if (n.contains("ask_user_question") || n.contains("提问")) return ask;
        if (n.contains("todo") || n.contains("待办") || n.contains("任务计划") || n.contains("task") || n.contains("清单")) return todo;
        if (n.contains("查看图片") || n.contains("view_image") || n.contains("图片") || n.contains("image")
                || n.contains("read_file_range") || n.contains("view_file_outline") || n.contains("读取") || n.contains("查看")
                || n.contains("list_files") || n.contains("浏览目录")) return eye;
        if (n.contains("搜索") || n.contains("查找") || n.contains("grep") || n.contains("search")) return search;
        if (n.contains("list_files") || n.contains("浏览目录") || n.contains("浏览") || n.contains("list")) return eye;
        if (n.contains("create_new_file") || n.contains("新建文件")) return newfile;
        if (n.contains("写入") || n.contains("创建") || n.contains("编辑") || n.contains("write") || n.contains("edit") || n.contains("new")) return pen;
        if (n.contains("删除") || n.contains("delete") || n.contains("remove")) return trash;
        if (n.contains("命令") || n.contains("执行") || n.contains("run") || n.contains("command")) return term;
        return dot;
    }

    private static String fmtTime(long millis) {
        if (millis <= 0) return "";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        return sdf.format(new java.util.Date(millis));
    }

    private void renderAssistantMessage(String content, String reasoningContent, String toolCallsJson) {
        boolean hasToolCalls = toolCallsJson != null && !toolCallsJson.isEmpty();
        boolean hasContent = content != null && !content.isEmpty();
        boolean hasReasoning = reasoningContent != null && !reasoningContent.isEmpty();
        if (hasContent || hasToolCalls || hasReasoning) {
            chatWebView.startAiStream();
        }
        if (hasReasoning) {
            chatWebView.appendReasoning(reasoningContent);
            chatWebView.finalizeReasoning();
        }
        if (hasToolCalls) {
            try {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<ChatMessage.ToolCall>>(){}.getType();
                java.util.List<ChatMessage.ToolCall> toolCalls = new com.google.gson.Gson().fromJson(toolCallsJson, type);
                if (toolCalls != null) {
                    for (ChatMessage.ToolCall tc : toolCalls) {
                        if (tc != null && tc.getFunction() != null && "todo".equals(tc.getFunction().getName())) {
                            todoManager.replayTodoTool(tc);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (hasContent) {
            String bodyHtml = MarkdownUtil.toHtmlFragment(content);
            chatWebView.finalizeAiMessage(bodyHtml, content);
        } else if (hasToolCalls || hasReasoning) {
            streamRenderController.cancelPendingRenderAndBump();
            chatWebView.sealStream();
        }
    }

    // ── 技能弹层渲染器（JList 内每行）—— 与 ModeComboRenderer / ModelComboRenderer 同构的扁平风格 ──
    // 每行：图标+skill 名+右侧勾选框；最末 "Import skill" 项用蓝色 + 号图标 + 柔灰文字
    // 勾选态由 enabledSet 决定（renderer 持有，ChatPanel 在 toggle/刷新时注入）

    /** skill 弹层列表项：普通 skill 或 Import 入口 */
    public static class SkillListItem {
        public static final int KIND_SKILL = 0;
        public static final int KIND_IMPORT = 1;
        /** 占位项：保留以备扩展（当前弹层用不到收起态标题） */
        public static final int KIND_NONE = 2;

        public final int kind;
        public final String name;       // 普通 skill 时=skill 名；Import 时=" Import skill"；占位时="Skills"
        public String itemTitle;        // 收起态标题（由 ChatPanel 在渲染时写入）

        private SkillListItem(int kind, String name) {
            this.kind = kind;
            this.name = name;
            this.itemTitle = "Skills";
        }

        public static SkillListItem skill(String name) {
            return new SkillListItem(KIND_SKILL, name);
        }
        public static SkillListItem importItem() {
            return new SkillListItem(KIND_IMPORT, " Import skill");
        }
        /** 占位项（"未选 skill"），收起态默认显示 "Skills" */
        public static SkillListItem none() {
            return new SkillListItem(KIND_NONE, "Skills");
        }

        @Override
        public String toString() {
            // 收起态宽度由 CPComboUI.getPreferredSize 按 selected.toString() 计算，
            // 必须返回与收起态渲染（this.text = itemTitle）一致的字符串，否则文字与箭头间距被撑大。
            return itemTitle;
        }
    }

    private static class SkillComboRenderer extends JComponent implements ListCellRenderer<SkillListItem> {
        private final Icon skillIcon, addIcon;
        private final Icon deleteIcon; // 垃圾桶 SVG（resources/icons/delete_model.svg）
        private final javax.swing.JList<SkillListItem> ownerList; // 用于删除图标 hover 重绘
        private String text = "Skills";
        private boolean isImport = false;
        private boolean isSelectedItem = false;
        private int currentRow = -1;
        private boolean rowHover = false;
        private boolean renderingAsList = false;
        private boolean checked = false;          // 当前 skill 是否已被勾选启用
        private boolean deletable = false;        // 当前 skill 是否为用户导入（可删除）
        private boolean deleteHover = false;      // 鼠标是否悬停在本行删除图标上（在 calcHover 内按 modelMousePoint 判定，复用渲染器安全）
        private int currentListIndex = -1;        // 列表态行号（供 ChatPanel 反算删除图标命中区）
        private java.util.Set<String> enabledSet; // 已启用的 skill 名集合（由 ChatPanel 注入）

        /** 注入当前已启用的 skill 名集合，供绘制勾选框时判断 */
        void setEnabledSet(java.util.Set<String> set) { this.enabledSet = set; }

        /** 删除图标几何参数（与 paintComponent 内绘制保持一致）：
         *  删除图标 16×16 位于行最右（贴右内边距）；勾选框在其左侧 gap=8。 */
        private static final int DEL_ICON_SIZE = 16;
        private static final int DEL_CB_GAP = 8;

        /** 计算某行内删除图标的 x 范围（相对列表宽度 w）：[delX, delX+16] */
        private static int delIconX(int rowWidth) {
            return rowWidth - ComboStyle.itemPaddingX() - DEL_ICON_SIZE;
        }

        SkillComboRenderer(javax.swing.JList<SkillListItem> ownerList, Icon skillIcon, Icon addIcon, Icon deleteIcon) {
            this.ownerList = ownerList;
            this.skillIcon = skillIcon;
            this.addIcon = addIcon;
            this.deleteIcon = deleteIcon;
            setOpaque(false);
            setBorder(null);
        }

        /** 判断鼠标点是否落在某行的删除图标上（由 ChatPanel 的鼠标监听调用）。
         *  仅真实 skill 行且可删时命中。 */
        boolean hitDeleteIcon(Point p) {
            // ★ 不依赖渲染器字段（deletable/currentListIndex 只在渲染时更新，时序不可靠），
            //   直接用 locationToIndex 反算行号 + 从数据源实时判断可删性。
            if (ownerList == null) return false;
            int idx = ownerList.locationToIndex(p);
            if (idx < 0 || idx >= ownerList.getModel().getSize()) return false;
            SkillListItem it = ownerList.getModel().getElementAt(idx);
            if (it == null || it.kind != SkillListItem.KIND_SKILL) return false;
            if (!com.codepal.skills.SkillStore.isUserImported(it.name)) return false;
            int rowW = ownerList.getWidth();
            if (rowW <= 0) return false;
            int fixedH = ownerList.getFixedCellHeight();
            if (fixedH <= 0) return false;
            int topPad = ownerList.getInsets().top;
            int rowY = topPad + idx * fixedH;
            int delX = delIconX(rowW);
            int delY = rowY + (fixedH - DEL_ICON_SIZE) / 2;
            return p.x >= delX - 3 && p.x <= delX + DEL_ICON_SIZE + 3
                    && p.y >= delY - 3 && p.y <= delY + DEL_ICON_SIZE + 3;
        }

        /** 删除图标 hover 态现由 calcHover 在每次重绘时依据 CP.modelMousePoint 统一判定
         *  （与灰色行 hover 同一机制，复用渲染器安全）。此方法仅用于在鼠标移动时触发重绘，
         *  使 calcHover 的判定即时生效；光标也已在 calcHover 内设置。 */
        boolean updateDeleteHover(Point p) {
            if (ownerList != null) {
                ownerList.repaint();
                return true;
            }
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            boolean inList = renderingAsList;

            if (inList) {
                g2.setColor(ComboStyle.surfaceColor());
                g2.fillRect(0, 0, w, h);
                if (isSelectedItem) {
                    g2.setColor(ComboStyle.selectionColor());
                    g2.fillRect(0, 0, w, h);
                } else if (rowHover) {
                    g2.setColor(ComboStyle.hoverColor());
                    g2.fillRect(0, 0, w, h);
                }

                int px = ComboStyle.itemPaddingX();
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = px;
                // 末项 Import skill 用 + 号图标（蓝+号），其余用 skill 图标
                Icon ic = isImport ? addIcon : skillIcon;
                int icX = iconX + (iconSize - ic.getIconWidth()) / 2;
                int icY = iconY + (iconSize - ic.getIconHeight()) / 2;
                ic.paintIcon(this, g2, icX, icY);

                // Import 项用柔灰 Font.PLAIN，与 modelCombo 的"＋ 配置..."风格一致
                if (isImport) {
                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(isSelectedItem ? Color.WHITE : ComboStyle.textSecondary());
                } else {
                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(isSelectedItem ? Color.WHITE : ComboStyle.textPrimary());
                }
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 10;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                // ★ 文字右界以"更靠左的勾选框左缘"为界（可删行勾选框比删除图标更靠左），
                //   并在其左侧留 8px 余量，确保长名被裁剪为"..."而非压到勾选框/删除图标上。
                int cbStartX = deletable
                        ? (delIconX(w) - DEL_CB_GAP - 16)
                        : (w - ComboStyle.itemPaddingX() - 16);
                int textMaxW = cbStartX - 8 - textX;
                if (textMaxW < 1) textMaxW = 1;
                String drawn = ComboStyle.clipTextIfNeeded(text, fm, textMaxW);
                g2.drawString(drawn, textX, textY);

                // 真实 skill 行右侧画删除图标（仅用户导入的可删）：位于勾选框右侧（行最右）
                // 样式与模型下拉框编辑笔一致：hover 时淡半透明黑圆角实心底框（阴阳框）
                if (!isImport && deletable) {
                    int delX = delIconX(w);
                    int delY = (h - DEL_ICON_SIZE) / 2;
                    if (deleteHover) {
                        com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                        int r = 6;
                        int inset = 2;
                        int cardSize = DEL_ICON_SIZE + inset * 2;
                        // ★ 主题自适应：亮色主题用半透明黑，暗色主题用半透明白（否则黑底上黑框=不可见）
                        boolean bright = com.intellij.ui.JBColor.isBright();
                        g2.setColor(bright ? new Color(0, 0, 0, 45) : new Color(255, 255, 255, 50));
                        g2.fillRoundRect(delX - inset, delY - inset, cardSize, cardSize, r, r);
                    }
                    deleteIcon.paintIcon(this, g2, delX, delY);
                }

                // 真实 skill 行右侧画勾选框（多选）：勾选=蓝底白勾，未勾选=描边空框；
                // 可删行勾选框左移，给删除图标让位（删除图标在勾选框右侧 gap=8）
                if (!isImport) {
                    int cbSize = 16;
                    int cbX = deletable ? (delIconX(w) - DEL_CB_GAP - cbSize)
                            : (w - ComboStyle.itemPaddingX() - cbSize);
                    int cbY = (h - cbSize) / 2;
                    if (checked) {
                        g2.setColor(ComboStyle.brandAccent());
                        g2.fillRoundRect(cbX, cbY, cbSize, cbSize, 4, 4);
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawLine(cbX + 4, cbY + 8, cbX + 7, cbY + 11);
                        g2.drawLine(cbX + 7, cbY + 11, cbX + 12, cbY + 5);
                    } else {
                        g2.setColor(ComboStyle.iconMuted());
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(cbX, cbY, cbSize, cbSize, 4, 4);
                    }
                }
                g2.dispose();
            } else {
                // 收起态：显示 itemTitle（"Skills" / 已选 skill 名），与 ModeComboRenderer 收尾风格一致
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = 8;
                int icX = iconX + (iconSize - skillIcon.getIconWidth()) / 2;
                int icY = iconY + (iconSize - skillIcon.getIconHeight()) / 2;
                skillIcon.paintIcon(this, g2, icX, icY);

                g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                g2.setColor(UIUtil.getLabelForeground());
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 6;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(text, textX, textY);
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Font font = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
            FontMetrics fm = getFontMetrics(font);
            int textW = fm.stringWidth(text);
            int iconArea = 8 + ComboStyle.iconSize() + 6;
            int rightPad = (currentRow == -1) ? 4 : ComboStyle.itemPaddingX();
            int h = ComboStyle.rowHeight();
            return new Dimension(iconArea + textW + rightPad, h);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SkillListItem> list, SkillListItem value,
                int index, boolean isSelected, boolean cellHasFocus) {
            // 列表态(index>=0)显示真实 skill 名(value.name)，收起态(index=-1)才用 itemTitle。
            // 之前所有项的 itemTitle 都被 refreshSkillCombo 设成 currentTitle="Skills"，
            // 导致列表里所有 skill 都显示成 "Skills"，与占位项重复。
            this.text = (value == null) ? "Skills"
                    : (index < 0 ? value.itemTitle : value.name);
            this.isImport = value != null && value.kind == SkillListItem.KIND_IMPORT;
            this.isSelectedItem = isSelected;
            this.currentRow = index;
            this.renderingAsList = (index >= 0);
            // 勾选框状态：仅真实 skill 项、且位于已启用集合中才勾选
            this.checked = value != null && value.kind == SkillListItem.KIND_SKILL
                    && enabledSet != null && enabledSet.contains(value.name);
            // 可删状态：仅真实 skill 且存在于用户目录（非出厂内置）才显示删除图标
            this.deletable = value != null && value.kind == SkillListItem.KIND_SKILL
                    && com.codepal.skills.SkillStore.isUserImported(value.name);
            this.currentListIndex = index;
            calcHover(list, index);
            return this;
        }

        private void calcHover(JList<?> list, int index) {
            rowHover = false;
            deleteHover = false;
            if (list == null) return;
            Object mpObj = list.getClientProperty("CP.modelMousePoint");
            if (!(mpObj instanceof Point)) return;
            Point mp = (Point) mpObj;
            int fixedH = list.getFixedCellHeight();
            if (fixedH <= 0) return;
            int topPad = list.getInsets().top;
            if (mp.y < topPad) return;
            int mouseRow = (mp.y - topPad) / fixedH;
            if (mouseRow < 0 || mouseRow >= list.getModel().getSize()) return;
            if (mouseRow == index) {
                rowHover = true;
                // 删除图标 hover：同一行且鼠标 x 落在右侧删除图标命中区
                if (deletable) {
                    int w = list.getWidth();
                    int delX = delIconX(w);
                    if (mp.x >= delX - 3 && mp.x <= delX + DEL_ICON_SIZE + 3) {
                        deleteHover = true;
                    }
                }
                // 光标：悬停在删除图标上变手型，否则默认
                list.setCursor(deleteHover
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        }
    }

    // ── 模式下拉框渲染器（仅 Craft）—— 完全自绘，避免嵌套 JPanel/JLabel 布局问题 ──

    private static class ModeComboRenderer extends JComponent implements ListCellRenderer<String> {
        private final Icon planIcon, craftIcon;
        private boolean isSelectedItem = false;
        private int currentRow = -1;
        private String text = "";
        private boolean isCraft = false;
        private boolean rowHover = false;
        private boolean renderingAsList = false;

        ModeComboRenderer(Icon planIcon, Icon craftIcon) {
            this.planIcon = planIcon;
            this.craftIcon = craftIcon;
            setOpaque(false);
            setBorder(null);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();
            boolean inList = renderingAsList;

            if (inList) {
                // 弹窗列表项 —— 与 ModelComboRenderer 完全一致的面板样式
                g2.setColor(ComboStyle.surfaceColor());
                g2.fillRect(0, 0, w, h);
                // 选中：比表面稍深一档的灰（无蓝、无圆角、无强调条）；hover：更淡一档灰
                if (isSelectedItem) {
                    g2.setColor(ComboStyle.selectionColor());
                    g2.fillRect(0, 0, w, h);
                } else if (rowHover) {
                    g2.setColor(ComboStyle.hoverColor());
                    g2.fillRect(0, 0, w, h);
                }

                int px = ComboStyle.itemPaddingX();
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = px; // 与 ModelComboRenderer 对齐：iconX = px（不再叠加 innerX）
                // 去掉图标阴影框：直接绘制图标
                Icon ic = isCraft ? craftIcon : planIcon;
                int icX = iconX + (iconSize - ic.getIconWidth()) / 2;
                int icY = iconY + (iconSize - ic.getIconHeight()) / 2;
                ic.paintIcon(this, g2, icX, icY);

                g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                g2.setColor(isSelectedItem ? Color.WHITE : ComboStyle.textPrimary());
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 10;
                // 模式渲染器无勾选框，文字裁剪到右侧内边距即可
                int textRightBound = w - px;
                String visible = ComboStyle.clipTextIfNeeded(text, fm, textRightBound - textX);
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(visible, textX, textY);

                g2.dispose();
            } else {
                // 收起态（显示在按钮上）
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = 8;
                // 去掉图标阴影框：直接绘制图标
                Icon ic = isCraft ? craftIcon : planIcon;
                int icX = iconX + (iconSize - ic.getIconWidth()) / 2;
                int icY = iconY + (iconSize - ic.getIconHeight()) / 2;
                ic.paintIcon(this, g2, icX, icY);

                g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                g2.setColor(UIUtil.getLabelForeground());
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 6;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                // 名字完整自适应显示（不加省略号）；combo 宽度按名字自适应，Swing clip 仅兜底
                g2.drawString(text, textX, textY);

                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Font font = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
            FontMetrics fm = getFontMetrics(font);
            int textW = fm.stringWidth(text);
            // 与 CPComboUI.getPreferredSize + 收起态绘制对齐：leftPad(8) + iconSize + gap(6)
            int iconArea = 8 + ComboStyle.iconSize() + 6;
            // 弹窗内 rightPad 与 ModelComboRenderer 对齐（itemPaddingX=8，不再叠加 itemInnerX）
            int rightPad = (currentRow == -1) ? 4 : ComboStyle.itemPaddingX();
            int h = ComboStyle.rowHeight();
            return new Dimension(iconArea + textW + rightPad, h);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                int index, boolean isSelected, boolean cellHasFocus) {
            this.text = value == null ? "" : value;
            this.isCraft = "Craft".equals(value);
            this.isSelectedItem = isSelected;
            this.currentRow = index;
            // 列表态判断必须用 index>=0，不能用 list!=null：
            // 收起态绘制时 BasicComboBoxUI 会把 comboBox 本身作为 list 传进来（非 null），
            // 若按 list!=null 判断会误入 inList 分支，额外画一层 surfaceColor 背景 → hover 双层色。
            this.renderingAsList = (index >= 0);
            calcHover(list, index);
            return this;
        }

        /** 读取列表上记录的鼠标 Point，判断当前行是否 hover（与 ModelComboRenderer 对齐） */
        private void calcHover(JList<?> list, int index) {
            rowHover = false;
            if (list == null) return;
            Object mpObj = list.getClientProperty("CP.modelMousePoint");
            if (!(mpObj instanceof Point)) return;
            Point mp = (Point) mpObj;
            int fixedH = list.getFixedCellHeight();
            if (fixedH <= 0) return;
            int topPad = list.getInsets().top;
            if (mp.y < topPad) return;
            int mouseRow = (mp.y - topPad) / fixedH;
            if (mouseRow < 0 || mouseRow >= list.getModel().getSize()) return;
            if (mouseRow == index) rowHover = true;
        }
    }

    // ── 模型下拉框自定义组合框：在setSelectedItem层面拦截"配置自定义模型"项 ──

    /**
     * 模型下拉项包装类：携带模型ID（UUID）与显示名称。
     * toString() 返回显示名称，确保Swing默认渲染和键盘选择可用。
     * kind: 0=普通模型, 1=添加聊天模型, 2=添加补全模型
     */
    public static class ModelComboItem {
        public static final int KIND_MODEL = 0;
        public static final int KIND_ADD_CHAT = 1;
        public static final int KIND_ADD_COMPLETION = 2;
        public static final int KIND_CONFIG_VISION = 3;

        public final String id;
        public final String name;
        public final int kind;

        ModelComboItem(String id, String name) {
            this(id, name, KIND_MODEL);
        }

        ModelComboItem(String id, String name, int kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }

        static ModelComboItem addChatItem(String label) {
            return new ModelComboItem(null, label, KIND_ADD_CHAT);
        }

        static ModelComboItem addCompletionItem(String label) {
            return new ModelComboItem(null, label, KIND_ADD_COMPLETION);
        }

        static ModelComboItem configVisionItem(String label) {
            return new ModelComboItem(null, label, KIND_CONFIG_VISION);
        }

        boolean isAddItem() { return kind != KIND_MODEL; }
        boolean isAddChat() { return kind == KIND_ADD_CHAT; }
        boolean isAddCompletion() { return kind == KIND_ADD_COMPLETION; }
        boolean isConfigVision() { return kind == KIND_CONFIG_VISION; }

        @Override
        public String toString() { return name; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ModelComboItem)) return false;
            ModelComboItem that = (ModelComboItem) o;
            if (id != null && that.id != null) return id.equals(that.id);
            if (kind != KIND_MODEL && that.kind != KIND_MODEL) return kind == that.kind && name.equals(that.name);
            return name != null ? name.equals(that.name) : that.name == null;
        }

        @Override
        public int hashCode() {
            if (id != null) return id.hashCode();
            return 31 * kind + (name != null ? name.hashCode() : 0);
        }
    }

    /**
     * 自定义模型选择组合框。
     * 核心特性：重写 setSelectedItem()，当尝试选中"配置自定义模型"或"配置补全模型"项时，
     * 直接拦截——不改变选中项，关闭popup，并触发对应的回调。
     */
    private static class ModelComboBox extends ComboBox<ModelComboItem> {
        private final java.util.function.Consumer<ModelComboItem> onAddItem;

        ModelComboBox(ModelComboItem[] items, java.util.function.Consumer<ModelComboItem> onAddItem) {
            super(items);
            this.onAddItem = onAddItem;
        }

        @Override
        public void setSelectedItem(Object anObject) {
            if (anObject instanceof ModelComboItem && ((ModelComboItem) anObject).isAddItem()) {
                if (isPopupVisible()) {
                    setPopupVisible(false);
                }
                final ModelComboItem item = (ModelComboItem) anObject;
                if (onAddItem != null) {
                    SwingUtilities.invokeLater(() -> onAddItem.accept(item));
                }
                return;
            }
            super.setSelectedItem(anObject);
        }
    }

    // ── 模型下拉框编辑/删除支持 ──

    /** 带右侧编辑/删除图标的模型下拉项渲染器 —— 纯绘制，hover状态在绘制时自查询鼠标位置，彻底避免状态同步问题。
     *  视觉规则：
     *  - 默认：灰色图标，无按钮背景
     *  - 行hover（鼠标在行上但不在按钮上）：卡片背景变色，图标保持灰色，无按钮背景
     *  - 选中：卡片背景变色，图标白色，无按钮背景
     *  - 按钮hover（鼠标在编辑/删除按钮上）：白色圆角按钮背景+紫色图标
     */
    // 扁平下拉渲染器（贴合 CodeBuddy 原生风：灰选中、无圆角、无强调条）
    // hover 模型行时，右侧出现一支编辑笔 SVG（无底色无边框，纯图标）；「＋ 配置...」项去图标块
    private static class ModelComboRenderer extends JComponent implements ListCellRenderer<ModelComboItem> {
        private final Icon modelIcon, addIcon, editIcon, checkIcon;
        private boolean isAdd = false;
        private boolean isAddChat = false;
        private boolean isSelectedItem = false;
        private int currentRow = -1;
        private String text = "";
        private boolean rowHover = false;
        private boolean editHover = false;
        private boolean isVisionConfigured = false;
        private ModelComboItem itemValue = null; // 存当前单元格值，绘制时据此重算，避免单例复用污染
        private boolean renderingAsList = false;

        ModelComboRenderer(Icon modelIcon, Icon addIcon, Icon editIcon, Icon checkIcon) {
            this.modelIcon = modelIcon;
            this.addIcon = addIcon;
            this.editIcon = editIcon;
            this.checkIcon = checkIcon;
            setOpaque(false);
            setBorder(null);
        }

        /** 直接读取列表上记录的鼠标 Point，计算当前行是否 hover / 是否在编辑笔上（不调用 locationToIndex 以避免递归）。 */
        private void calcHover(JList<?> list, int index) {
            rowHover = false;
            editHover = false;
            // 收起态（如 getPreferredSize 度量时）list 为 null，无任何鼠标 hover 信息，直接返回
            if (list == null) return;
            Object mpObj = list.getClientProperty("CP.modelMousePoint");
            if (!(mpObj instanceof Point)) return;
            Point mp = (Point) mpObj;
            int fixedH = list.getFixedCellHeight();
            if (fixedH <= 0) return;
            int topPad = list.getInsets().top;
            if (mp.y < topPad) return;
            int mouseRow = (mp.y - topPad) / fixedH;
            if (mouseRow < 0 || mouseRow >= list.getModel().getSize()) return;
            if (mouseRow != index) return;
            rowHover = true;

            // 计算编辑笔命中区（与 paintComponent 中保持一致）
            int w = list.getWidth() - list.getInsets().left - list.getInsets().right;
            int px = ComboStyle.itemPaddingX();
            int editSize = 16;
            int editX = w - px - editSize;
            int editY = (fixedH - editSize) / 2;
            int pad = 6;
            int rx = mp.x - list.getInsets().left;
            int ry = mp.y - topPad - mouseRow * fixedH;
            if (isAdd) return; // add 项不显示编辑笔
            if (rx >= editX - pad && rx <= editX + editSize + pad
                    && ry >= editY - pad && ry <= editY + editSize + pad) {
                editHover = true;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();

            // 绘制时按当前单元格真实值重算（不依赖可能被单例复用冲掉的 isVisionConfigured 字段）
            isVisionConfigured = itemValue != null && itemValue.isConfigVision()
                    && CPSettings.getInstance().getVisionModel() != null;

            boolean inList = renderingAsList;

            if (inList) {
                // 选中：比表面稍深一档的灰（无蓝、无圆角、无强调条）；hover：更淡一档灰
                if (isSelectedItem) {
                    g2.setColor(ComboStyle.selectionColor());
                    g2.fillRect(0, 0, w, h);
                } else if (rowHover) {
                    g2.setColor(ComboStyle.hoverColor());
                    g2.fillRect(0, 0, w, h);
                }

                int px = ComboStyle.itemPaddingX();
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = px;

                if (isAdd) {
                    // 第一项添加项上方细分割线
                    if (isAddChat) {
                        g2.setColor(dividerColor());
                        g2.setStroke(new BasicStroke(1.0f));
                        g2.drawLine(px, 1, w - px, 1);
                    }
                    // 无图标块背景：直接贴一个小的蓝色 + 图标 + 文字（贴近参考图样式）
                    int addSize = 16;
                    int addY = (h - addSize) / 2;
                    addIcon.paintIcon(this, g2, iconX, addY);

                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(ComboStyle.textSecondary());
                    FontMetrics fm = g2.getFontMetrics();
                    int textX = iconX + addSize + 6;
                    // 已配置的视觉子智能体项：文字右边界留出对号 + 右边距空间，避免压到对号
                    int addRightBound = (text != null && text.contains("已配置"))
                            ? (w - iconX - 16 - 6)
                            : (w - px);
                    String addVisible = ComboStyle.clipTextIfNeeded(text, fm, addRightBound - textX);
                    int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(addVisible, textX, textY);
                } else {
                    // 去掉图标阴影框：直接绘制图标
                    int mx = iconX + (iconSize - modelIcon.getIconWidth()) / 2;
                    int my = iconY + (iconSize - modelIcon.getIconHeight()) / 2;
                    modelIcon.paintIcon(this, g2, mx, my);

                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(isSelectedItem ? Color.WHITE : ComboStyle.textPrimary());
                    FontMetrics fm = g2.getFontMetrics();
                    int textX = iconX + iconSize + 10;
                    // 文本右边界：让出编辑笔位置（hover 时右侧会出现 16px 图标 + 一些 padding）
                    int editReserved = (rowHover ? (px + 16 + 12) : px);
                    int textRightBound = w - editReserved;
                    String visible = ComboStyle.clipTextIfNeeded(text, fm, textRightBound - textX);
                    int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(visible, textX, textY);

                    // ── hover 时右侧浮现编辑笔 SVG（默认白色；移到笔上时出现阴影底框 + 变蓝）──
                    if (rowHover) {
                        int editSize = 16;
                        int editX = w - px - editSize;
                        int editY = (h - editSize) / 2;
                        // 鼠标移到笔上时：与圆环/+号一致的悬浮卡片（同心居中、淡半透明黑圆角实心，无描边）
                        if (editHover) {
                            com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                            int r = 6;
                            int inset = 2;
                            int cardSize = editSize + inset * 2;
                            int sx = editX - inset;
                            int sy = editY - inset;
                            // ★ 主题自适应：亮色用半透明黑，暗色用半透明白（与 Skill 删除图标 hover 框一致，避免暗色下黑底不可见）
                            boolean bright = com.intellij.ui.JBColor.isBright();
                            g2.setColor(bright ? new Color(0, 0, 0, 45) : new Color(255, 255, 255, 50));
                            g2.fillRoundRect(sx, sy, cardSize, cardSize, r, r);
                        }
                        editIcon.paintIcon(this, g2, editX, editY);
                    }
                }

                // 已配置的视觉子智能体：绿色对号贴在弹窗右边缘（与上面 model 项的编辑笔 SVG 同一条 x 轴线，整列对齐）。
                if (text != null && text.contains("已配置")) {
                    int chkX = w - iconX - 16; // 与编辑笔 editX = w - px - 16 完全一致
                    int chkY = (h - 16) / 2;
                    checkIcon.paintIcon(this, g2, chkX, chkY);
                }
                g2.dispose();
            } else {
                // 收起态
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = 8; // 与 getPreferredSize 的 leftPad(8) 对齐
                // 去掉图标阴影框：直接绘制图标
                int mx = iconX + (iconSize - modelIcon.getIconWidth()) / 2;
                int my = iconY + (iconSize - modelIcon.getIconHeight()) / 2;
                modelIcon.paintIcon(this, g2, mx, my);

                g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                g2.setColor(UIUtil.getLabelForeground());
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 6; // 与 getPreferredSize 的 iconArea 对齐
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                // 名字完整自适应显示（不加省略号）；combo 宽度按名字自适应，Swing clip 仅兜底
                g2.drawString(text, textX, textY);

                // 已配置的视觉子智能体：绿色对号贴右边缘（收起态），与文字同行
                if (text != null && text.contains("已配置")) {
                    int chkX = w - ComboStyle.arrowWidth() - 16 - 4; // 留 4px 给右边距，与箭头对齐
                    int chkY = (h - 16) / 2;
                    checkIcon.paintIcon(this, g2, chkX, chkY);
                }
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Font font = (currentRow == -1 || isAdd)
                    ? JBUI.Fonts.label(13).deriveFont(Font.PLAIN)
                    : JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
            FontMetrics fm = getFontMetrics(font);
            int textW = fm.stringWidth(text);
            int px = ComboStyle.itemPaddingX();
            int iconSize = ComboStyle.iconSize();
            int addSize = 16;
            int gap = 10;
            int checkW = (text != null && text.contains("已配置")) ? 22 : 0;
            int w;
            if (currentRow == -1) {
                w = 14 + iconSize + gap + textW + 30 + checkW;
            } else if (isAdd) {
                // add 项：左侧 + 16 + 6 + 文字 + 右侧 px；视觉子智能体项需额外预留对号宽度
                int checkExtra = (text != null && text.contains("已配置")) ? (16 + 8) : 0;
                w = px + addSize + 6 + textW + px + checkExtra;
            } else {
                // 普通模型项：预留编辑笔位置（即使未 hover 也按 hover 预留，避免选中抖动）
                int checkExtra = (text != null && text.contains("已配置")) ? (16 + 8) : 0;
                w = px + iconSize + gap + checkExtra + textW + px + 16 + 12;
            }
            return new Dimension(w, ComboStyle.rowHeight());
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ModelComboItem> list, ModelComboItem value,
                int index, boolean isSelected, boolean cellHasFocus) {
            text = value != null ? value.name : "";
            isAdd = value != null && value.isAddItem();
            isAddChat = value != null && value.isAddChat();
            isSelectedItem = isSelected;
            currentRow = index;
            isVisionConfigured = value != null && value.isConfigVision()
                    && CPSettings.getInstance().getVisionModel() != null;
            itemValue = value;
            // 与 ModeComboRenderer 一致：用 index>=0 判定列表态，收起态绘制时 BasicComboBoxUI 传的
            // list=comboBox(非null) 但 index=-1，若按 list!=null 会误入 inList 分支画多余背景。
            renderingAsList = (index >= 0);
            calcHover(list, index);
            return this;
        }
    }

    /** 获取 ComboBox 弹层中的列表组件 */
    private static JList<?> getComboList(JComboBox<?> combo) {
        Object child = combo.getUI().getAccessibleChild(combo, 0);
        if (child instanceof ComboPopup) {
            return ((ComboPopup) child).getList();
        }
        return null;
    }

    /** 给下拉弹层列表挂上 hover 鼠标点追踪（所有 combo 共用，驱动 calcHover） */
    private void attachListHoverTracking(JComboBox<?> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                JList<?> list = getComboList(combo);
                if (list == null) return;
                if (Boolean.TRUE.equals(list.getClientProperty("CP.hoverBound"))) return;
                list.putClientProperty("CP.hoverBound", Boolean.TRUE);
                list.putClientProperty("CP.modelMousePoint", null);
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent me) {
                        list.putClientProperty("CP.modelMousePoint", me.getPoint());
                        list.repaint();
                    }
                });
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseExited(MouseEvent me) {
                        list.putClientProperty("CP.modelMousePoint", null);
                        list.repaint();
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    /** attachListHoverTracking 的 JList 重载：直接给列表挂 hover 鼠标点追踪（用于 JBPopup 内的 skill 列表，无 combo 弹层） */
    private void attachListHoverTracking(JList<?> list) {
        if (Boolean.TRUE.equals(list.getClientProperty("CP.hoverBound"))) return;
        list.putClientProperty("CP.hoverBound", Boolean.TRUE);
        list.putClientProperty("CP.modelMousePoint", null);
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent me) {
                list.putClientProperty("CP.modelMousePoint", me.getPoint());
                list.repaint();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent me) {
                list.putClientProperty("CP.modelMousePoint", null);
                list.repaint();
            }
        });
    }

    /** 给模型下拉的弹层列表挂上：双击=编辑、右键=编辑/删除菜单（替代原行内悬浮按钮，视觉零按钮） */
    private void attachModelListActions(JComboBox<?> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                JList<?> list = getComboList(combo);
                if (list == null) return;
                if (Boolean.TRUE.equals(list.getClientProperty("CP.modelListBound"))) return;
                list.putClientProperty("CP.modelListBound", Boolean.TRUE);
                list.putClientProperty("CP.modelMousePoint", null);
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent me) {
                        list.putClientProperty("CP.modelMousePoint", me.getPoint());
                        // 鼠标移到模型行（非 add 项）→ 手型光标；否则默认箭头
                        int row = list.locationToIndex(me.getPoint());
                        boolean hand = row >= 0;
                        Object v = (row >= 0) ? list.getModel().getElementAt(row) : null;
                        if (v instanceof ModelComboItem && ((ModelComboItem) v).isAddItem()) hand = false;
                        list.setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                : Cursor.getDefaultCursor());
                        list.repaint();
                    }
                });
                list.addMouseListener(new MouseAdapter() {
                    // 注意：必须放 mousePressed 而不是 mouseClicked。
                    // BasicComboPopup 在 mousePressed 时就选中并关闭弹层，会打断后续的 mouseClicked 序列，
                    // 导致 mouseClicked 经常不触发 → 点编辑笔“没反应”。mousePressed 在弹层关闭前稳定触发。
                    @Override
                    public void mousePressed(MouseEvent me) {
                        if (me.isPopupTrigger()) { showModelRowMenu(me, list); return; }
                        if (!SwingUtilities.isLeftMouseButton(me)) return;
                        int row = list.locationToIndex(me.getPoint());
                        if (row < 0) return;
                        Object v = list.getModel().getElementAt(row);
                        if (!(v instanceof ModelComboItem) || ((ModelComboItem) v).isAddItem()) return;
                        ModelComboItem item = (ModelComboItem) v;

                        // 坐标基准必须与 renderer 的 calcHover 完全一致（用 list 内容宽度 + 减去 insets），
                        // 不能再用 getCellBounds().x/.width（其 x 未必等于 insets.left，会导致命中区偏移、点了笔却没反应）。
                        int px = ComboStyle.itemPaddingX();
                        int editSize = 16;
                        int pad = 6;
                        int contentW = list.getWidth() - list.getInsets().left - list.getInsets().right;
                        int editX = contentW - px - editSize;
                        int fixedH = list.getFixedCellHeight();
                        int topPad = list.getInsets().top;
                        int editY = (fixedH - editSize) / 2;
                        int rx = me.getPoint().x - list.getInsets().left;
                        int ry = me.getPoint().y - topPad - row * fixedH;
                        boolean onPen = rx >= editX - pad && rx <= editX + editSize + pad
                                && ry >= editY - pad && ry <= editY + editSize + pad;

                        // 单击命中编辑笔 → 关闭 popup 后，延后到 EDT 下一轮再开编辑弹框
                        // （等 popup 完全消失，避免模态 AddModelDialog 与弹层焦点/父窗口竞态导致不开）
                        if (onPen) {
                            me.consume();
                            combo.setPopupVisible(false);
                            final String id = item.id;
                            SwingUtilities.invokeLater(() -> editModel(id));
                            return;
                        }
                        // 双击行任意位置 → 编辑（兼容旧习惯）
                        if (me.getClickCount() == 2) {
                            me.consume();
                            combo.setPopupVisible(false);
                            final String id = item.id;
                            SwingUtilities.invokeLater(() -> editModel(id));
                        }
                    }
                    @Override
                    public void mouseReleased(MouseEvent me) {
                        if (me.isPopupTrigger()) showModelRowMenu(me, list);
                    }
                    @Override
                    public void mouseExited(MouseEvent me) {
                        list.putClientProperty("CP.modelMousePoint", null);
                        list.repaint();
                    }
                });
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // 编辑动作改由点击处理里直接 invokeLater 触发；这里仅清理 pending 标记，避免重复打开
                combo.putClientProperty("CP.pendingEditId", null);
            }
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                combo.putClientProperty("CP.pendingEditId", null);
            }
        });
    }

    private void showModelRowMenu(MouseEvent me, JList<?> list) {
        int row = list.locationToIndex(me.getPoint());
        if (row < 0) return;
        Object v = list.getModel().getElementAt(row);
        if (!(v instanceof ModelComboItem) || ((ModelComboItem) v).isAddItem()) return;
        ModelComboItem item = (ModelComboItem) v;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("编辑");
        edit.addActionListener(a -> editModel(item.id));
        JMenuItem del = new JMenuItem("删除");
        del.addActionListener(a -> deleteModel(item.id));
        menu.add(edit);
        menu.add(del);
        menu.show(list, me.getX(), me.getY());
    }

    /** 构建模型下拉项数组（含底部三个入口：补全模型 / 聊天模型 / 配置视觉子智能体） */
    private ModelComboItem[] buildModelComboItems(String addCompletionLabel, String addChatLabel, String configVisionLabel) {
        CPSettings.getInstance().reloadModelsFromDb();
        List<ModelConfig> models = CPSettings.getInstance().getChatModels();
        int modelCount = (models != null) ? models.size() : 0;
        ModelComboItem[] items = new ModelComboItem[modelCount + 3];
        for (int i = 0; i < modelCount; i++) {
            ModelConfig m = models.get(i);
            items[i] = new ModelComboItem(m.getId(), m.getName());
        }
        items[modelCount] = ModelComboItem.addChatItem(addChatLabel);
        items[modelCount + 1] = ModelComboItem.addCompletionItem(addCompletionLabel);
        items[modelCount + 2] = ModelComboItem.configVisionItem(configVisionLabel);
        return items;
    }

    /** 视觉子智能体下拉项文案：已配置则在右侧显示绿色对号，否则显示“配置视觉子智能体” */
    private String visionComboLabel() {
        ModelConfig vm = CPSettings.getInstance().getEffectiveVisionModel();
        if (vm != null) {
            return " 视觉子智能体已配置 (" + vm.getName() + ")";
        }
        return " 配置视觉子智能体";
    }

    /** 按模型名称选中下拉框中的项 */
    private void selectModelComboByName(String name) {
        if (name == null) return;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            ModelComboItem item = modelCombo.getItemAt(i);
            if (item != null && name.equals(item.name)) {
                modelCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** 按模型ID选中下拉框中的项 */
    private void selectModelComboById(String id) {
        if (id == null) return;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            ModelComboItem item = modelCombo.getItemAt(i);
            if (item != null && id.equals(item.id)) {
                modelCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** 添加新聊天模型 */
    private void addModel() {
        Window parentWindow = getParentWindow();
        if (parentWindow == null) return;
        AddModelDialog dialog = new AddModelDialog(project, parentWindow, AddModelDialog.MODE_CHAT);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            ModelConfig newCfg = dialog.getModelConfig();
            refreshModelCombo();
            if (newCfg != null) {
                selectModelComboById(newCfg.getId());
            }
            if (agentBackendManager != null) agentBackendManager.refreshCurrentBackend();
        }
    }

    /** 配置代码补全模型：有则编辑，无则新增 */
    private void addCompletionModel() {
        Window parentWindow = getParentWindow();
        if (parentWindow == null) return;
        CPSettings settings = CPSettings.getInstance();
        ModelConfig compModel = settings.getCurrentCompletionModel();
        AddModelDialog dialog;
        if (compModel != null) {
            dialog = new AddModelDialog(project, parentWindow, compModel);
        } else {
            dialog = new AddModelDialog(project, parentWindow, AddModelDialog.MODE_COMPLETION);
        }
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            refreshModelCombo();
        }
    }

    /** 编辑指定模型（按UUID） */
    private void editModel(String modelId) {
        if (modelId == null) return;
        CPSettings settings = CPSettings.getInstance();
        ModelConfig target = settings.findChatModelById(modelId);
        if (target == null) return;
        Window parentWindow = getParentWindow();
        AddModelDialog dialog = new AddModelDialog(project, parentWindow, target);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            ModelConfig newCfg = dialog.getModelConfig();
            refreshModelCombo();
            if (newCfg != null) {
                selectModelComboById(modelId);
            }
            if (agentBackendManager != null) agentBackendManager.refreshCurrentBackend();
        }
    }

    /** 删除指定模型（按UUID） */
    private void deleteModel(String modelId) {
        if (modelId == null) return;
        CPSettings settings = CPSettings.getInstance();
        ModelConfig target = settings.findChatModelById(modelId);
        if (target == null) return;
        int r = JOptionPane.showConfirmDialog(this, "确定删除模型 \"" + target.getName() + "\"？",
                "删除模型", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        settings.removeChatModelById(modelId);
        refreshModelCombo();
    }

    /** 安全获取父窗口——始终从modelCombo获取，确保拿到IDE主窗口而非popup */
    private Window getParentWindow() {
        return SwingUtilities.getWindowAncestor(modelCombo);
    }

    private void refreshModelCombo() {
        CPSettings.getInstance().reloadModelsFromDb();
        List<ModelConfig> models = CPSettings.getInstance().getChatModels();
        if (models == null) models = new ArrayList<>();

        String currentSelectedId = null;
        Object sel = modelCombo.getSelectedItem();
        if (sel instanceof ModelComboItem) {
            currentSelectedId = ((ModelComboItem) sel).id;
        }

        final String ADD_COMPLETION_ITEM = " 配置补全模型";
        final String ADD_CHAT_ITEM = " 配置自定义模型";
        modelCombo.removeAllItems();
        for (ModelConfig config : models) {
            modelCombo.addItem(new ModelComboItem(config.getId(), config.getName()));
        }
        modelCombo.addItem(ModelComboItem.addChatItem(ADD_CHAT_ITEM));
        modelCombo.addItem(ModelComboItem.addCompletionItem(ADD_COMPLETION_ITEM));
        modelCombo.addItem(ModelComboItem.configVisionItem(visionComboLabel()));

        if (currentSelectedId != null) {
            selectModelComboById(currentSelectedId);
        } else if (!models.isEmpty()) {
            int curIdx = CPSettings.getInstance().getCurrentChatModelIndex();
            if (curIdx >= 0 && curIdx < modelCombo.getItemCount() - 2) {
                modelCombo.setSelectedIndex(curIdx);
            } else {
                modelCombo.setSelectedIndex(0);
            }
        }
        // 列表重建后，收起态宽度需随当前选中模型名重新自适应
        modelCombo.revalidate();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  技能入口（skillButton + JBPopup 勾选面板）刷新 + Import skill 处理
    // ═════════════════════════════════════════════════════════════════════════

    /** skill 弹层底部的"Import skill"项显示文本（与 ModelComboRenderer 的 addChatLabel 风格一致：前置 + icon） */
    private static final String IMPORT_SKILL_LABEL = " Import skill";

    /** 刷新 skill 列表数据：从 SkillStore.listSkills() 重读，同步已启用集合到渲染器。 */
    private void refreshSkillCombo() {
        // 同步已启用集合（与 CPSettings 对齐），供渲染器画勾选框
        enabledSkillNames.clear();
        enabledSkillNames.addAll(CPSettings.getInstance().getEnabledSkills());
        if (skillComboRenderer != null) skillComboRenderer.setEnabledSet(enabledSkillNames);
        java.util.List<SkillListItem> items = new java.util.ArrayList<>();
        java.util.List<String> names = com.codepal.skills.SkillStore.listSkills();
        for (String n : names) {
            items.add(SkillListItem.skill(n));
        }
        SkillListItem importItem = SkillListItem.importItem();
        importItem.itemTitle = IMPORT_SKILL_LABEL;
        items.add(importItem);
        if (skillList != null) {
            skillList.setModel(new javax.swing.AbstractListModel<SkillListItem>() {
                @Override public int getSize() { return items.size(); }
                @Override public SkillListItem getElementAt(int i) { return items.get(i); }
            });
            skillList.repaint();
        }
    }

    /** 切换某 skill 的启用态：持久化 + 重绘勾选框 + 重新注入系统提示词（勾选=模型可见） */
    private void toggleSkillEnabled(String name) {
        if (name == null) return;
        boolean nowOn = !CPSettings.getInstance().isSkillEnabled(name);
        CPSettings.getInstance().setSkillEnabled(name, nowOn);
        enabledSkillNames.clear();
        enabledSkillNames.addAll(CPSettings.getInstance().getEnabledSkills());
        if (skillComboRenderer != null) skillComboRenderer.setEnabledSet(enabledSkillNames);
        if (skillList != null) skillList.repaint();
        // 重新注入系统提示词：勾选=可见，取消=不可见
        conversationManager.updateSystemPrompt(
                CPSettings.getInstance().getSystemPrompt(project, craftMode));
    }

    /** 弹出 skill 勾选面板：JPopupMenu，渲染机制与 modelCombo 的 BasicComboPopup 完全同源 ——
     *  setOpaque(false) + 透明背景 + paintComponent 自绘圆角阴影 + EmptyBorder(8,8,8,8)。
     *  Swing popup 用透明窗口承载，自绘内容完整透出，不会有两层面板；点击行仅 toggle 不自动关闭。 */
    private void showSkillPopup() {
        // 重新拉取最新数据
        refreshSkillCombo();
        if (skillPopup != null) skillPopup.setVisible(false); // 旧弹层先关

        final int shadowPad = 8;
        final int radius = ComboStyle.popupRadius();
        final int rowH = ComboStyle.rowHeight();
        final int popupPadV = 8; // 与 modelCombo 的 listPadV 一致

        int rows = Math.max(1, skillList.getModel().getSize());
        int visibleRows = Math.min(rows, 12);
        int listH = visibleRows * rowH;
        // ★ 宽度自适应：按最长 skill 名计算所需宽度（图标+间距+文字+右侧控件区），不再固定 240，
        //   保证长名字完整显示、不出省略号。
        java.awt.Font itemFont = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
        java.awt.FontMetrics itemFm = skillList.getFontMetrics(itemFont);
        int px0 = ComboStyle.itemPaddingX();
        int maxTextW = 0;
        for (int i = 0; i < skillList.getModel().getSize(); i++) {
            SkillListItem it = skillList.getModel().getElementAt(i);
            String t = it == null ? "" : (it.kind == SkillListItem.KIND_IMPORT ? IMPORT_SKILL_LABEL : it.name);
            if (t != null) maxTextW = Math.max(maxTextW, itemFm.stringWidth(t));
        }
        // 右侧控件区：勾选框16 + gap8 + 删除图标16 + gap8 + 右内边距，取最大可能值留余量
        int rightControls = px0 + 16 + 8 + 16 + 8;
        // 末位 +16（而非 +8）：为最长 skill 名与左侧勾选框之间预留约 8px 间隙，
        // 配合渲染器的文字裁剪，确保长名不会被勾选框/删除图标掩盖。
        int neededW = px0 + ComboStyle.iconSize() + 10 + maxTextW + rightControls + 16;
        int contentW = Math.max(skillButton.getWidth() > 0 ? skillButton.getWidth() : 240, neededW);
        int contentH = listH + popupPadV * 2;
        int totalW = contentW + shadowPad * 2;
        int totalH = contentH + shadowPad * 2;
        System.out.println("[SkillPopup] contentW=" + contentW + " neededW=" + neededW
                + " maxTextW=" + maxTextW + " rows=" + rows);

        // ── JPopupMenu：与 modelCombo 的 BasicComboPopup 同款自绘（圆角 + 多层阴影 + 1px 描边）──
        JPopupMenu popup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                int bx = shadowPad, by = shadowPad, bw = w - shadowPad * 2, bh = h - shadowPad * 2;
                // 多层阴影（与 ComboStyle.CPComboUI 的 paintComponent 对齐）
                for (int i = 10; i >= 2; i -= 2) {
                    float alpha = 0.04f * (10 - i + 2);
                    int sx = bx + 2 - i / 4, sy = by + 3 - i / 4;
                    int sw = bw + i / 2, sh = bh + i / 2;
                    g2.setColor(new Color(0, 0, 0, Math.min((int) (alpha * 255), 60)));
                    g2.fillRoundRect(sx, sy, sw, sh, radius + i / 2, radius + i / 2);
                }
                // 主体圆角背景
                g2.setColor(ComboStyle.popupSurfaceColor());
                g2.fillRoundRect(bx, by, bw, bh, radius, radius);
                // 1px 描边
                g2.setColor(JBColor.isBright() ? new Color(0, 0, 0, 30) : new Color(255, 255, 255, 20));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(bx + 0.5f, by + 0.5f, bw - 1, bh - 1, radius, radius));
                g2.dispose();
            }
        };
        popup.setOpaque(false);
        popup.setBackground(new Color(0, 0, 0, 0));
        popup.setBorder(BorderFactory.createEmptyBorder(shadowPad, shadowPad, shadowPad, shadowPad));

        // 列表区域：透明 + 内边距，让自绘圆角底色透出
        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(popupPadV, popupPadV, popupPadV, popupPadV));
        content.add(skillList, BorderLayout.CENTER);
        skillList.setOpaque(false);
        skillList.setBackground(new Color(0, 0, 0, 0));
        popup.add(content);
        popup.setPreferredSize(new Dimension(totalW, totalH));

        // ── 位置：相对按钮，优先朝上（与 modelCombo 行为一致）──
        int btnY = skillButton.getLocationOnScreen().y;
        java.awt.Window owner = SwingUtilities.getWindowAncestor(skillButton);
        int screenTop = owner != null ? owner.getLocationOnScreen().y : btnY;
        int spaceAbove = btnY - screenTop;
        int screenH = Toolkit.getDefaultToolkit().getScreenSize().height;
        int spaceBelow = screenH - btnY - skillButton.getHeight();
        int showY = (spaceAbove >= totalH + 6 || spaceAbove > spaceBelow)
                ? (-totalH - 6)                        // 朝上：按钮上方，留6px间隙
                : (skillButton.getHeight() + 6);       // 朝下
        skillPopup = popup;
        popup.show(skillButton, 0, showY);
        // 点击行只 toggle、不自动关闭（JPopupMenu 点内部默认不关；点外部由 Swing 自动关闭）
    }

    /**
     * 打开系统文件选择器选文件夹，校验含 SKILL.md 后调用 SkillStore.importSkillFolder 导入。
     * 必须在 EDT 调用；通常由 skill 弹层内 Import 项点击触发。
     */
    /**
     * 删除一个用户导入的 skill：确认后从用户目录删除文件，并同步取消启用态、刷新列表。
     * 必须在 EDT 调用（由 skill 弹层删除图标点击触发）。
     */
    private void deleteSkill(String name) {
        if (name == null || name.isBlank()) return;
        if (!com.codepal.skills.SkillStore.isUserImported(name)) {
            statusLabel.setText("内置出厂技能不可删除：" + name);
            return;
        }
        int opt = Messages.showYesNoDialog(project,
                "确定要删除 skill「" + name + "」吗？\n\n将从磁盘删除其文件夹，且不可恢复。",
                "Delete skill", Messages.getWarningIcon());
        if (opt != Messages.YES) return;
        boolean ok = com.codepal.skills.SkillStore.removeSkill(name);
        if (ok) {
            // 若该 skill 处于启用态，先取消勾选并重新注入系统提示词
            if (CPSettings.getInstance().isSkillEnabled(name)) {
                CPSettings.getInstance().setSkillEnabled(name, false);
                conversationManager.updateSystemPrompt(
                        CPSettings.getInstance().getSystemPrompt(project, craftMode));
            }
            refreshSkillCombo();
            if (skillList != null) skillList.repaint();
            statusLabel.setText("已删除 skill: " + name);
        } else {
            Messages.showErrorDialog(this,
                    "删除失败：无法删除 " + name + " 的技能目录（可能被占用）。", "Delete skill");
        }
    }

    private void importSkillFromChooser() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("选择 skill 文件夹（含 SKILL.md）");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        // 默认定位到当前项目根（用户常见操作位置）
        String basePath = project != null ? project.getBasePath() : null;
        if (basePath != null && new java.io.File(basePath).isDirectory()) {
            chooser.setCurrentDirectory(new java.io.File(basePath));
        }
        int ret = chooser.showOpenDialog(SwingUtilities.getWindowAncestor(this));
        if (ret != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File selected = chooser.getSelectedFile();
        if (selected == null) return;
        // 按文件夹名校验是否已存在同名 skill：存在则提示用户，确认后才覆盖
        String name = selected.getName();
        java.nio.file.Path existing = com.codepal.skills.SkillStore.getUserSkillsDir().resolve(name);
        if (java.nio.file.Files.exists(existing)) {
            int opt = Messages.showYesNoDialog(project,
                    "已存在同名 skill「" + name + "」，继续将覆盖其现有内容且不可恢复。\n\n是否继续覆盖？",
                    "Import skill", Messages.getWarningIcon());
            if (opt != Messages.YES) {
                statusLabel.setText("已取消导入：同名 skill「" + name + "」已存在");
                return;
            }
        }
        try {
            com.codepal.skills.SkillStore.importSkillFolder(selected.toPath());
            // 导入后刷新列表（新导入的 skill 默认未勾选，由用户自行勾选启用）
            refreshSkillCombo();
            if (skillList != null) skillList.repaint();
            statusLabel.setText("已导入 skill: " + name + "（在 Skills 面板中勾选以启用）");
        } catch (java.io.IOException e) {
            Messages.showErrorDialog(this,
                    "导入失败：" + e.getMessage() + "\n\n提示：所选文件夹根目录必须包含 SKILL.md 文件。",
                    "Import skill");
        }
    }

    /**
     * 创建新会话：生成会话ID，清空聊天区，输出问候语并入库
     */
    public void createNewSession() {
        toolOrchestrator.resetCancelled();

        if (toolConfirmManager != null) toolConfirmManager.reset();
        if (policyEngine != null) policyEngine.clearSessionTrusted();
        iterationGuard.resetRoundCounters();

        ThreadHelper.executeAsync(project, () -> {
            chatSessionManager.createNewSession();
            chatSessionManager.saveSessionConfig();

            sessionPromptTokens = 0;
            sessionCompletionTokens = 0;
            sessionCacheHitTokens = 0;
            sessionCacheMissTokens = 0;
            if (tokenStatsLabel != null) {
                tokenStatsLabel.setToolTipText("暂无统计数据");
                tokenStatsLabel.putClientProperty("detail_text", null);
            }
            if (contextCircle != null) contextCircle.setTokens(0);
            compressionHintShown = false;
            taskWasInterrupted = false;
        });
    }

    // ── Todo 辅助方法 ──

    private List<com.codepal.tools.TodoListPanel.TodoItem> parseTodoItemsFromJson(String todosJson) {
        List<com.codepal.tools.TodoListPanel.TodoItem> items = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(todosJson).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonObject obj = arr.get(i).getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : String.valueOf(i);
                String content = obj.has("content") ? obj.get("content").getAsString() : "";
                String status = obj.has("status") ? obj.get("status").getAsString() : "pending";
                String priority = obj.has("priority") ? obj.get("priority").getAsString() : "medium";
                items.add(new com.codepal.tools.TodoListPanel.TodoItem(id, content, status, priority));
            }
        } catch (Exception e) {
            System.err.println("[CP] 解析 todo JSON 失败: " + e.getMessage());
        }
        return items;
    }

    /**
     * 待办全部完成时，把待办列表追加到消息末尾进入上下文（节省 token）
     */
    private void appendTodoListToMessage(String todosJson) {
        List<com.codepal.tools.TodoListPanel.TodoItem> items = parseTodoItemsFromJson(todosJson);
        if (items.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n**任务完成清单**\n\n");
        for (com.codepal.tools.TodoListPanel.TodoItem item : items) {
            sb.append("- [x] ").append(item.content).append("\n");
        }
        String text = sb.toString();

        // 追加到当前 AI 消息末尾：走控制器以同步 currentAiRawText 并触发 Markdown 渲染，
        // 否则清单既不可见、又会在 finalize 时被 currentAiRawText 覆盖掉。
        streamRenderController.appendStreamChunk(text);
    }

    /** 释放 ChatWebView 资源 */
    public void dispose() {
        if (streamRenderController != null) {
            streamRenderController.stopWatchdog();
        }
        if (chatWebView != null) {
            chatWebView.dispose();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  数据源下拉框：新增/编辑/删除
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 加载数据源弹层列表项：真实数据源 + 末尾「配置数据源」入口。
     * 注意：不含占位项 —— 占位文案由按钮自身显示，列表里不需要占位行，
     * 因此弹层顶部不会残留空白（这也是改用按钮范式而非 JComboBox 的根本原因）。
     */
    private java.util.List<com.codepal.model.DatabaseComboItem> loadDatabaseItems() {
        java.util.List<com.codepal.model.DatabaseComboItem> items = new java.util.ArrayList<>();
        try {
            items.addAll(DataSourceDao.getAll());
        } catch (Exception e) {
            System.err.println("[DatabaseCombo] 读取数据源失败: " + e.getMessage());
        }
        items.add(com.codepal.model.DatabaseComboItem.addItem());
        return items;
    }

    /** 按 id 查找数据源项（编辑用）：直接从 DAO 读取，不依赖弹层列表的当前 model */
    private com.codepal.model.DatabaseComboItem findDatabaseItemById(String id) {
        if (id == null) return null;
        for (com.codepal.model.DatabaseComboItem item : loadDatabaseItems()) {
            if (item != null && id.equals(item.id)) return item;
        }
        return null;
    }

    /**
     * 数据源弹层的行点击处理：
     * - 右键真实数据源行 → 编辑/删除菜单
     * - 命中编辑笔 或 双击行 → 编辑该数据源
     * - 单击真实数据源行 → 选中（按钮文案变为该数据源名）
     * - 点击「配置数据源」→ 打开新增对话框
     */
    private void onDataSourceItemClicked(com.codepal.model.DatabaseComboItem item, int index, java.awt.event.MouseEvent e) {
        if (item == null) return;
        if (item.isAddItem()) {
            databaseCombo.closePopup();
            SwingUtilities.invokeLater(() -> openDataSourceDialog(null));
            return;
        }
        // 右键 → 编辑/删除菜单（保持与原 DatabaseComboBox 一致的交互）
        if (SwingUtilities.isRightMouseButton(e)) {
            showDataSourceRowMenu(item, e);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;

        boolean onPen = databaseListRenderer != null
                && databaseListRenderer.hitEditIcon(e.getPoint(), databaseCombo.getList());
        if (onPen || e.getClickCount() == 2) {
            databaseCombo.closePopup();
            SwingUtilities.invokeLater(() -> openDataSourceDialog(findDatabaseItemById(item.id)));
            return;
        }
        // 单击选中：更新按钮文案，并把"默认数据源"通报给 ToolExecutor，
        // 让 query_database 在模型没传 db_name 时自动用它；同时刷新系统提示，让模型立刻知晓。
        selectedDataSourceId = item.id;
        databaseCombo.setDisplayText(item.name);
        com.codepal.tools.ToolExecutor.setDefaultDataSource(item.name);
        refreshSystemPromptForDataSource();
        databaseCombo.closePopup();
    }

    /** 数据源变化（选中/新增/删除）后重新注入系统提示，让模型立即看到最新的数据源清单与默认选择 */
    private void refreshSystemPromptForDataSource() {
        if (conversationManager == null) return;
        try {
            conversationManager.updateSystemPrompt(
                    CPSettings.getInstance().getSystemPrompt(project, craftMode));
        } catch (Exception e) {
            // 系统提示刷新失败不影响主流程
        }
    }

    /** 数据源行右键菜单：编辑 / 删除 */
    private void showDataSourceRowMenu(com.codepal.model.DatabaseComboItem item, java.awt.event.MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("编辑");
        edit.addActionListener(a -> {
            databaseCombo.closePopup();
            SwingUtilities.invokeLater(() -> openDataSourceDialog(findDatabaseItemById(item.id)));
        });
        JMenuItem del = new JMenuItem("删除");
        del.addActionListener(a -> {
            databaseCombo.closePopup();
            SwingUtilities.invokeLater(() -> deleteDataSource(item.id));
        });
        menu.add(edit);
        menu.add(del);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /** 当前选中的数据源名称（未选择返回 null） */
    private String getSelectedDataSourceName() {
        if (selectedDataSourceId == null) return null;
        com.codepal.model.DatabaseComboItem it = findDatabaseItemById(selectedDataSourceId);
        return it == null ? null : it.name;
    }

    /** 打开数据源新增/编辑对话框 */
    private void openDataSourceDialog(com.codepal.model.DatabaseComboItem existing) {
        java.awt.Window parent = SwingUtilities.getWindowAncestor(this);
        com.codepal.ui.AddDataSourceDialog dialog = new com.codepal.ui.AddDataSourceDialog(parent, existing);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            refreshDatabaseCombo();
        }
    }

    /** 删除数据源 */
    private void deleteDataSource(String id) {
        if (id == null || id.isEmpty()) return;
        int opt = Messages.showYesNoDialog(project,
                "确定要删除该数据源吗？此操作不可恢复。", "删除数据源", Messages.getWarningIcon());
        if (opt != Messages.YES) return;
        try {
            DataSourceDao.delete(id);
            // 若删除的正是当前选中项，回退到未选择状态
            if (id.equals(selectedDataSourceId)) {
                selectedDataSourceId = null;
                databaseCombo.setDisplayText("数据源");
            }
            refreshDatabaseCombo();
            statusLabel.setText("已删除数据源");
        } catch (Exception e) {
            Messages.showErrorDialog(this, "删除失败：" + e.getMessage(), "删除数据源");
        }
    }

    /** 刷新数据源弹层内容（新增/编辑/删除后调用） */
    private void refreshDatabaseCombo() {
        if (databaseCombo == null) return;
        databaseCombo.refreshData();
        // 选中项若已不存在（被删/改名），回退到占位文案
        if (selectedDataSourceId != null && findDatabaseItemById(selectedDataSourceId) == null) {
            selectedDataSourceId = null;
            databaseCombo.setDisplayText("数据源");
        }
        // 同步"默认数据源"给工具层（query_database 在模型未传 db_name 时回退用它），
        // 并刷新系统提示，让模型立即看到最新清单与默认选择。
        com.codepal.tools.ToolExecutor.setDefaultDataSource(getSelectedDataSourceName());
        refreshSystemPromptForDataSource();
        databaseCombo.revalidate();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  @ 提示弹窗：在输入框输入 @ 时弹出数据源列表（仿 skillPopup 同源圆角阴影渲染）
    // ═════════════════════════════════════════════════════════════════════════

    /** @ 弹窗列表项：标题（Database）/ 数据源条目 */
    public static class MentionListItem {
        public static final int KIND_HEADER = 0;
        public static final int KIND_DATABASE = 1;

        public final int kind;
        public final String name;
        public final String type;

        private MentionListItem(int kind, String name, String type) {
            this.kind = kind;
            this.name = name;
            this.type = type;
        }

        public static MentionListItem header() {
            return new MentionListItem(KIND_HEADER, "Database", null);
        }

        public static MentionListItem database(String name, String type) {
            return new MentionListItem(KIND_DATABASE, name, type);
        }
    }

    /** @ 弹窗列表渲染器：数据源为图标+名称+右侧类型标签；header（Database 标题）单独画一行小号灰字 */
    private static class MentionComboRenderer extends JComponent implements ListCellRenderer<MentionListItem> {
        private final JList<MentionListItem> ownerList;
        private final Icon dbIcon;
        private MentionListItem current;
        private boolean rollover;

        // 自适应行高 & 文本宽缓存：宽高由 getPreferredSize 报告给 JList，list 据此撑开。
        private int cachedTextW = 0;
        private int cachedTypeBadgeW = 0;

        MentionComboRenderer(JList<MentionListItem> ownerList, Icon dbIcon) {
            this.ownerList = ownerList;
            this.dbIcon = dbIcon;
            setOpaque(false);
            setFont(JBUI.Fonts.label(13));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MentionListItem> list,
                                                      MentionListItem value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.current = value;
            this.rollover = (list == ownerList) && (index >= 0) && (index == (list.getSelectedIndex()));
            setEnabled(list.isEnabled());
            // 预量当前行的"理想内容宽度"：icon + 文字 + 徽章 + padding，徽章按 type 文本自适应。
            int tw = 0;
            int tbw = 0;
            if (value != null) {
                String t = value.name == null ? "" : value.name;
                java.awt.Font plain = getFont().deriveFont(Font.PLAIN);
                FontMetrics fm = getFontMetrics(plain);
                tw = fm.stringWidth(t);
                if (value.type != null) {
                    java.awt.Font small = plain.deriveFont((float) JBUI.scale(10));
                    FontMetrics sfm = getFontMetrics(small);
                    int inner = sfm.stringWidth(value.type);
                    tbw = inner + JBUI.scale(14); // 左右各 7px padding
                }
            }
            this.cachedTextW = tw;
            this.cachedTypeBadgeW = tbw;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            MentionListItem it = current;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (rollover) {
                g2.setColor(ComboStyle.hoverColor());
                g2.fillRect(0, 0, w, h);
            }
            if (it == null) { g2.dispose(); return; }

            int padX = ComboStyle.itemPaddingX();
            int iconSize = ComboStyle.iconSize();

            // ── header 行（Database 标题）：左侧不要图标，画小号灰字、整行 hover 不变色 ──
            if (it.kind == MentionListItem.KIND_HEADER) {
                g2.setFont(getFont().deriveFont(Font.PLAIN, (float) JBUI.scale(11)));
                g2.setColor(ComboStyle.textSecondary());
                FontMetrics fm = g2.getFontMetrics();
                int textY = h / 2 + fm.getAscent() / 2 - 1;
                g2.drawString(it.name == null ? "" : it.name, padX, textY);
                g2.dispose();
                return;
            }

            // ── 数据源行 ──
            int iconX = padX;
            int iconY = (h - iconSize) / 2;
            if (dbIcon != null) dbIcon.paintIcon(this, g2, iconX, iconY);
            g2.setFont(getFont().deriveFont(Font.PLAIN));
            g2.setColor(ComboStyle.textPrimary());
            String text = it.name == null ? "" : it.name;
            FontMetrics fm = g2.getFontMetrics();
            int textY = h / 2 + 4;
            int textX = iconX + iconSize + 8;
            // 右侧预留：徽章按文本宽自适应 + 徽章与文字之间 12px 间距 + padX
            int typeBadgeW = it.type != null ? (cachedTypeBadgeW > 0 ? cachedTypeBadgeW : JBUI.scale(46)) : 0;
            int typeGap = it.type != null ? JBUI.scale(12) : 0;
            int maxTextW = w - textX - typeBadgeW - typeGap - padX;
            if (maxTextW < 1) maxTextW = 1;
            String clipped = ComboStyle.clipTextIfNeeded(text, fm, maxTextW);
            g2.drawString(clipped, textX, textY);
            if (it.type != null) {
                int bx = w - padX - typeBadgeW;
                int by = (h - JBUI.scale(16)) / 2;
                g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(255, 255, 255, 22)));
                g2.fillRoundRect(bx, by, typeBadgeW, JBUI.scale(16), 6, 6);
                g2.setColor(ComboStyle.textSecondary());
                java.awt.Font smallFont = getFont().deriveFont(Font.PLAIN, (float) JBUI.scale(10));
                g2.setFont(smallFont);
                FontMetrics sfm = g2.getFontMetrics();
                int tx = bx + (typeBadgeW - sfm.stringWidth(it.type)) / 2;
                int ty = by + (JBUI.scale(16) + sfm.getAscent() - sfm.getDescent()) / 2;
                g2.drawString(it.type, tx, ty);
            }
            g2.dispose();
        }

        @Override
        public Dimension getPreferredSize() {
            // 向 JList 报告"理想内容宽度"，让弹层据此撑开面板
            int padX = ComboStyle.itemPaddingX();
            int iconSize = ComboStyle.iconSize();
            int gap = 8;
            int typeGap = cachedTypeBadgeW > 0 ? JBUI.scale(12) : 0;
            int contentW = padX + iconSize + gap + cachedTextW + typeGap + cachedTypeBadgeW + padX;
            int rowH = (current != null && current.kind == MentionListItem.KIND_HEADER)
                    ? JBUI.scale(26) : (ComboStyle.rowHeight() + JBUI.scale(6));
            // 下限：避免列特别短时宽度不够
            contentW = Math.max(contentW, JBUI.scale(120));
            return new Dimension(contentW, rowH);
        }
    }

    /** 初始化 @ 弹窗的 JList 与渲染器（在 ChatPanel 构造期调用一次） */
    private void initMentionPopup() {
        mentionList = new JList<>();
        MentionComboRenderer renderer = new MentionComboRenderer(mentionList, ComboStyle.databaseIcon());
        mentionList.setCellRenderer(renderer);
        mentionList.setFixedCellHeight(ComboStyle.rowHeight() + JBUI.scale(8));
        mentionList.setVisibleRowCount(8);
        mentionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mentionList.setOpaque(false);
        mentionList.setBackground(new Color(0, 0, 0, 0));
        attachListHoverTracking(mentionList);

        MouseAdapter click = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int idx = mentionList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                MentionListItem it = mentionList.getModel().getElementAt(idx);
                if (it == null || it.kind != MentionListItem.KIND_DATABASE) return;
                insertMentionedDatabase(it.name);
            }
        };
        mentionList.addMouseListener(click);
        mentionList.addMouseMotionListener(click);
    }

    private void showMentionPopup(int atPos) {
        if (mentionList == null) initMentionPopup();
        if (mentionPopup != null) mentionPopup.cancel();

        java.util.List<MentionListItem> items = new java.util.ArrayList<>();
        items.add(MentionListItem.header());
        java.util.List<com.codepal.model.DatabaseComboItem> sources;
        try {
            sources = DataSourceDao.getAll();
        } catch (Exception ex) {
            sources = java.util.Collections.emptyList();
        }
        if (sources.isEmpty()) {
            items.add(MentionListItem.database("(暂无数据源，请先在「数据源」下拉框配置)", null));
        } else {
            for (com.codepal.model.DatabaseComboItem s : sources) {
                items.add(MentionListItem.database(s.name, s.type));
            }
        }
        mentionList.setModel(new javax.swing.AbstractListModel<MentionListItem>() {
            @Override public int getSize() { return items.size(); }
            @Override public MentionListItem getElementAt(int i) { return items.get(i); }
        });
        if (sources.isEmpty()) {
            mentionList.setEnabled(false);
        } else {
            mentionList.setEnabled(true);
            mentionList.setSelectedIndex(1);
        }

        // 行高：header 用小高度，DB 行用 rowHeight+6（更舒展，跟列表图标匹配）
        mentionList.setFixedCellHeight(-1); // 用 renderer preferredSize
        mentionList.setCellRenderer(mentionList.getCellRenderer()); // 触发一次 pref

        // 重新触发 renderer 计算，并按行汇总"理想宽度"
        java.awt.Font plainFont = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
        java.awt.FontMetrics itemFm = mentionList.getFontMetrics(plainFont);
        java.awt.FontMetrics headerFm = mentionList.getFontMetrics(
                JBUI.Fonts.label(11).deriveFont(Font.PLAIN));
        int padX = ComboStyle.itemPaddingX();
        int iconSize = ComboStyle.iconSize();
        int gap = 8;
        int maxRowW = 0;
        for (MentionListItem it : items) {
            int rowW;
            if (it.kind == MentionListItem.KIND_HEADER) {
                rowW = padX + headerFm.stringWidth(it.name == null ? "" : it.name) + padX;
            } else {
                int tw = itemFm.stringWidth(it.name == null ? "" : it.name);
                int tbw = 0;
                if (it.type != null) {
                    java.awt.Font small = plainFont.deriveFont((float) JBUI.scale(10));
                    int inner = mentionList.getFontMetrics(small).stringWidth(it.type);
                    tbw = inner + JBUI.scale(14);
                }
                int typeGap = tbw > 0 ? JBUI.scale(12) : 0;
                rowW = padX + iconSize + gap + tw + typeGap + tbw + padX;
            }
            maxRowW = Math.max(maxRowW, rowW);
        }
        // 最小宽度不低于 320（DPI 自适应），并预留 12% 视觉余量
        int minW = JBUI.scale(320);
        int contentW = Math.max(minW, maxRowW + JBUI.scale(8));
        // 高度：按行数直接计算（与 renderer preferredSize 公式保持一致），更可控
        int rowCount = sources.isEmpty() ? 1 : sources.size();
        int visibleLimit = Math.min(rowCount, 8);
        mentionList.setVisibleRowCount(visibleLimit);
        int headerH = JBUI.scale(26);
        int dbRowH = ComboStyle.rowHeight() + JBUI.scale(6);
        int contentH = headerH + visibleLimit * dbRowH + JBUI.scale(8);

        // 列表尺寸：设给 list 而非 panel —— 与 showAttachPopup 一致，让 JBPopup 自行 pack。
        // （直接给 panel 设 preferredSize 会让窗口尺寸与内容/border 计算脱节，是"双层"诱因之一）
        mentionList.setPreferredSize(new Dimension(contentW, contentH));

        // ── JBPopup + PopupBorder（与 showAttachPopup 逐字同构）──
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new ComboStyle.PopupBorder(ComboStyle.popupSurfaceColor(), ComboStyle.popupRadius()));
        panel.add(mentionList, BorderLayout.CENTER);

        // 选项 hover 时鼠标变手形
        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        mentionList.setCursor(hand);
        panel.setCursor(hand);

        // ★ builder 配置与 showAttachPopup 严格一致：
        //   setShowBorder(false) + setShowShadow(false) + setRequestFocus(true)，且【不】调用
        //   setFocusable / setCancelOnClickOutside / setCancelOnOtherWindowOpen ——
        //   这些额外选项会让 JBPopup 改用非透明的窗口外壳，在圆角外的角落露出直角灰底（即"双层"）。
        com.intellij.openapi.ui.popup.JBPopup popup =
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                        .createComponentPopupBuilder(panel, mentionList)
                        .setShowBorder(false)
                        .setShowShadow(false)
                        .setRequestFocus(true)
                        .createPopup();

        mentionPopup = popup;
        mentionStartPos = atPos;
        mentionActive = true;

        // ── 位置：朝上优先（与 showAttachPopup 同源），水平 clamp 到所属屏幕内 ──
        java.awt.Point baseAbove = computeAbovePoint(inputField, panel);
        java.awt.Window owner2 = SwingUtilities.getWindowAncestor(inputField);
        java.awt.Rectangle screenBounds = (owner2 != null && owner2.getGraphicsConfiguration() != null)
                ? owner2.getGraphicsConfiguration().getBounds()
                : new java.awt.Rectangle(new java.awt.Point(0, 0),
                        Toolkit.getDefaultToolkit().getScreenSize());
        int totalW = panel.getPreferredSize().width;
        int screenX = baseAbove.x;
        if (screenX + totalW > screenBounds.x + screenBounds.width) {
            screenX = screenBounds.x + screenBounds.width - totalW;
        }
        if (screenX < screenBounds.x) {
            screenX = screenBounds.x;
        }
        popup.showInScreenCoordinates(inputField, new java.awt.Point(screenX, baseAbove.y));

        popup.addListener(new com.intellij.openapi.ui.popup.JBPopupListener() {
            @Override public void onClosed(com.intellij.openapi.ui.popup.LightweightWindowEvent event) {
                mentionActive = false;
                mentionStartPos = -1;
                if (mentionPopup == popup) mentionPopup = null;
            }
        });
    }

    private void hideMentionPopup() {
        if (mentionPopup != null) mentionPopup.cancel();
        mentionActive = false;
        mentionStartPos = -1;
    }

    private void insertMentionedDatabase(String dbName) {
        try {
            int caret = inputField.getCaretPosition();
            int from = Math.max(0, Math.min(mentionStartPos, caret));
            int to = Math.max(from, caret);
            String cur = inputField.getText();
            String before = cur.substring(0, from);
            String after = cur.substring(to);
            String sep = after.startsWith(" ") ? "" : " ";
            // ★ 参考 yours_agent（ChatInput.vue:532）：数据源插入带「库」语义前缀。
            //   裸库名（codepalSQL）模型无法归类，会转而去项目里 grep 连接配置；
            //   加「库」字后消息形如「查一下 @库codepalSQL 的 users 表」，
            //   模型一眼认出这是数据库名，直接填进 query_database 的 db_name。
            String replacement = "@库" + dbName + sep;
            inputField.setText(before + replacement + after);
            int newCaret = before.length() + replacement.length();
            inputField.setCaretPosition(newCaret);
        } finally {
            hideMentionPopup();
        }
    }

    private String safeGetEventText(javax.swing.event.DocumentEvent e) {
        try {
            return e.getDocument().getText(e.getOffset(), e.getLength());
        } catch (javax.swing.text.BadLocationException ex) {
            return "";
        }
    }

    private int findMentionAtCaret() {
        try {
            String txt = inputField.getText();
            int caret = inputField.getCaretPosition();
            for (int i = caret - 1; i >= 0; i--) {
                char c = txt.charAt(i);
                if (c == '@') {
                    if (i == 0) return 0;
                    char prev = txt.charAt(i - 1);
                    if (Character.isWhitespace(prev)) return i;
                    return -1;
                }
                if (Character.isWhitespace(c)) return -1;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

}
