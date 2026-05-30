package com.loongc.toolwindow;

import com.google.gson.Gson;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.*;
import com.intellij.util.ui.UIUtil;
import com.loongc.api.DeepSeekClient;
import com.loongc.common.StringUtils;
import com.loongc.model.*;
import com.loongc.common.CollectUtils;
import com.loongc.common.Constant;
import com.loongc.db.DBChatHistoryRepository;
import com.loongc.settings.LoongCSettings;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.loongc.utils.FileReaderUtil;
import com.loongc.utils.MarkdownUtil;
import com.loongc.utils.ThreadHelper;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LoongC 聊天面板
 * - 头部操作通过 LoongCToolWindowFactory.setTitleActions 注入（AllIcons 原生图标）
 * - 消息气泡：左侧 AI（头像+名称+圆角卡片），右侧用户（圆角气泡+颜文字头像）
 * - Markdown 渲染：流式阶段 JTextArea，收到完整内容后切换为 JEditorPane(HTML)
 * - 右键菜单：所有气泡支持复制/全选
 * - 文件上下文条：监听编辑器文件切换 & 选区变化，点击可插入输入框
 */
public class ChatPanel extends JPanel {

    private static Color aiBubbleBg() {
        return JBColor.namedColor("Panel.background",
                UIUtil.isUnderDarcula() ? new Color(0x3C3F41) : new Color(0xF0F1F3));
    }

    /** 用户消息气泡背景：使用 IDEA 按钮强调色 */
    private static Color userBubbleBg() {
        return JBColor.namedColor("Button.default.background",
                UIUtil.isUnderDarcula() ? new Color(0x365880) : new Color(0x2E5FBE));
    }

    /** 思考过程区块背景 */
    private static Color reasoningBg() {
        return JBColor.namedColor("Plugins.tagBackground",
                UIUtil.isUnderDarcula() ? new Color(0x3D2F4A) : new Color(0xF3E5F5));
    }

    /** 思考过程文字颜色 */
    private static Color reasoningFg() {
        return JBColor.namedColor("Plugins.tagForeground",
                UIUtil.isUnderDarcula() ? new Color(0xD1C4E9) : new Color(0x5E35B1));
    }

    /** 思考过程边框颜色 */
    private static Color reasoningBd() {
        return JBColor.namedColor("Component.focusedBorderColor",
                UIUtil.isUnderDarcula() ? new Color(0x5E35B1) : new Color(0xCE93D8));
    }

    /** 分隔线颜色 */
    private static Color dividerColor() {
        return JBColor.namedColor("Separator.separatorColor",
                UIUtil.isUnderDarcula() ? new Color(0x4E5157) : new Color(0xC9CCD6));
    }

    /** 输入框边框：普通态 */
    private static Color inputBorderNormal() {
        return JBColor.namedColor("Component.borderColor",
                UIUtil.isUnderDarcula() ? new Color(0x4E5157) : new Color(0xC9CCD6));
    }

    /** 输入框边框：聚焦态 */
    private static Color inputBorderFocus() {
        return JBColor.namedColor("Component.focusedBorderColor", new Color(0x4B8EF0));
    }

    /** 状态/提示文字颜色 */
    private static Color mutedFg() {
        return JBColor.namedColor("Label.infoForeground", JBColor.GRAY);
    }

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
    private final DeepSeekClient client;
    private final List<ChatMessage> conversationHistory;

    // 会话管理
    private String currentSessionId = null;
    private int historyLoadedCount = 0;   // 已从底部向上加载的消息数
    private int historyTotalCount = 0;    // 会话总消息数
    private boolean isLoadingHistory = false; // 是否正在懒加载历史消息
    private JBPanel<?> loadingIndicator = null; // 顶部加载中动画


    private final JBPanel<?>  messagesPanel;
    private final JBScrollPane messagesScrollPane;
    private final JTextArea inputField;
    private final JComboBox<String> modelCombo;
    private final JLabel statusLabel;
    /** 底部 token 用量 + 费用统计标签（显示在 modelCombo 右侧） */
    private JLabel tokenStatsLabel;

    // 文件上下文条组件
    private final JBPanel<?> ctxBar;
    private final JBLabel ctxFileLabel;
    private final JBLabel  ctxLineLabel;
    private final JButton ctxInsertBtn;
    private String ctxCurrentFile = null;
    private int    ctxStartLine   = -1;
    private int    ctxEndLine     = -1;

    // 当前正在接收的 AI 消息组件引用
    private boolean isReceiving        = false;
    private JBTextArea currentStreamArea  = null;
    private JBPanel<?>     currentBubbleInner = null;
    private String     currentAiRawText   = "";

    // 思考过程区块组件引用
    private JBTextArea currentReasoningArea  = null;
    private JBPanel<?>    currentReasoningPanel = null;
    private JBPanel<?>    currentReasoningContent = null;
    private JBLabel    currentReasoningStatus  = null;
    private String    currentReasoningText    = "";
    private boolean   isReasoningCollapsed    = true; // 会话级折叠状态记忆

    // 当前会话累计 token 用量
    private int sessionPromptTokens      = 0;
    private int sessionCompletionTokens  = 0;
    private int sessionCacheHitTokens    = 0;
    private int sessionCacheMissTokens   = 0;

    // 发送按钮
    JButton sendBtn;

    // 面板切换（CardLayout）
    private static final String CHAT_CARD = "chat";
    private static final String HISTORY_CARD = "history";
    private final CardLayout cardLayout;
    private final JBPanel<?> cardPanel;

    // 历史会话面板组件
    private JBList<HistoryItem> historyList;
    private DefaultListModel<HistoryItem> historyListModel;
    private JBPanel<?> historyPanel;

    public ChatPanel(Project project) {
        this.project = project;
        this.client  = new DeepSeekClient();
        this.conversationHistory = new ArrayList<>();

        setLayout(new BorderLayout());
        setBackground(UIUtil.getPanelBackground());

        conversationHistory.add(new ChatMessage("system", LoongCSettings.getInstance().getSystemPrompt()));

        messagesPanel = new JBPanel(null);
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(UIUtil.getPanelBackground());
        messagesPanel.setBorder(JBUI.Borders.empty(14, 12, 6, 12));
        // 确保 BoxLayout 下子组件能正确展开：在 Java 21 中某些情况下需要显式设置对齐
        //messagesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        messagesScrollPane = new JBScrollPane(messagesPanel);
        messagesScrollPane.setBorder(null);
        messagesScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messagesScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        // 注册滚动懒加载监听器
        messagesScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) return;
            maybeLoadOlderMessages();
        });


        //add(messagesScrollPane, BorderLayout.CENTER);

        // 初始化上下文条（先隐藏，有文件时显示）
        ctxFileLabel  = new JBLabel();
        ctxLineLabel  = new JBLabel();
        ctxInsertBtn  = new JButton("插入");
        ctxBar        = buildContextBar();
        ctxBar.setVisible(false);

        statusLabel = new JBLabel("就绪");
        inputField = new JTextArea(3, 0);
        modelCombo = new JComboBox<>(LoongCSettings.getInstance().getChatModelNames());
        add(buildInputPanel(), BorderLayout.SOUTH);

        // CardLayout：聊天视图 + 历史视图
        cardLayout = new CardLayout();
        cardPanel = new JBPanel<>(cardLayout);
        cardPanel.setOpaque(false);
        cardPanel.add(messagesScrollPane, CHAT_CARD);
        // 构建历史会话面板
        historyPanel = buildHistoryPanel();
        cardPanel.add(historyPanel, HISTORY_CARD);

        add(cardPanel, BorderLayout.CENTER);
        // 注册编辑器监听器
        if (project != null) {
            registerFileListener();
            registerSelectionListener();
        }

        // 创建默认会话（问候语入库）
        openSession();
//        if(LoongCSettings.getInstance().isEnableMessagePersistence()){
//
//        } else {
//            ThreadHelper.runOnUi(project,()->{
//                addAiMessage(LoongCSettings.getInstance().getSayHello());
//            });
//        }
    }

    private void openSession() {
        ThreadHelper.queryAsync(project, DBChatHistoryRepository::getRecentConversation,
                (conversation)->{
            System.out.println("openSession:"+conversation);
            if(null == conversation){
                createNewSession();
            } else {
                switchToSession(conversation.getId());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口（供 LoongCToolWindowFactory 的 titleAction 调用）
    // ─────────────────────────────────────────────────────────────────────────

    public void clearChat() {
        ApplicationManager.getApplication().invokeLater(() -> {
            messagesPanel.removeAll();
            messagesPanel.revalidate();
            messagesPanel.repaint();
            conversationHistory.clear();
            conversationHistory.add(new ChatMessage("system", LoongCSettings.getInstance().getSystemPrompt()));
            // 重置 token 统计
            sessionPromptTokens     = 0;
            sessionCompletionTokens = 0;
            sessionCacheHitTokens   = 0;
            sessionCacheMissTokens  = 0;
            if (tokenStatsLabel != null) tokenStatsLabel.setText("—");
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
        //iconLbl.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.BOLD, 13));
        iconLbl.setForeground(accent);
        leftGroup.add(iconLbl);

       // ctxFileLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.BOLD, 11));
        ctxFileLabel.setFont(JBUI.Fonts.label(11));
        ctxFileLabel.setForeground(accent);
        leftGroup.add(ctxFileLabel);

       // ctxLineLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
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
        ctxInsertBtn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(accent, 1, true),
                JBUI.Borders.empty(2, 8)
        ));
        ctxInsertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ctxInsertBtn.setFocusable(false);
        ctxInsertBtn.addActionListener(e -> insertContextIntoInput());
        ctxInsertBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                ctxInsertBtn.setBackground(JBColor.namedColor("ActionButton.hoverBackground",
                        UIUtil.isUnderDarcula() ? new Color(0x4B8EF0, true) : new Color(0xE3F2FD)));
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 底部输入区
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildInputPanel() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 0));
        panel.setBackground(UIUtil.getPanelBackground());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, dividerColor()));

        // 上下文条（文件+行号引用）
        panel.add(ctxBar, BorderLayout.NORTH);

        JBPanel<?>  inner = new JBPanel<>(new BorderLayout(0, 6));
        inner.setOpaque(false);
        inner.setBorder(JBUI.Borders.empty(10, 12, 12, 12));


//        statusLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
        statusLabel.setFont(JBUI.Fonts.label(11));
        statusLabel.setForeground(mutedFg());
        inner.add(statusLabel, BorderLayout.NORTH);


        // "Dialog" 逻辑字体获得更好的 Unicode fallback，支持 emoji 不会乱码
      //  inputField.setFont(new Font("Dialog", Font.PLAIN, 13));
        inputField.setFont(JBUI.Fonts.label(13));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setBackground(UIUtil.getTextFieldBackground());
        inputField.setForeground(UIUtil.getTextFieldForeground());
        inputField.setCaretColor(UIUtil.getTextFieldForeground());
        inputField.setBorder(JBUI.Borders.empty(8, 10));
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        JBScrollPane inputScroll = new JBScrollPane(inputField);
        inputScroll.setBorder(BorderFactory.createLineBorder(inputBorderNormal(), 1));
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        inputField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                inputScroll.setBorder(BorderFactory.createLineBorder(inputBorderFocus(), 1));
            }
            @Override public void focusLost(FocusEvent e) {
                inputScroll.setBorder(BorderFactory.createLineBorder(inputBorderNormal(), 1));
            }
        });

//        JButton sendBtn = new JButton() {
//            @Override
//            protected void paintComponent(Graphics g) {
//                Graphics2D g2 = (Graphics2D) g.create();
//                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
//                // 按钮背景圆角
//                g2.setColor(getBackground());
//                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
//                // 绘制发送图标（纸飞机风格）
//                paintSendIcon(g2, getWidth(), getHeight(), isReceiving);
//                g2.dispose();
//            }
//            @Override protected void paintBorder(Graphics g) { /* 不画系统边框 */ }
//            @Override public boolean isOpaque() { return false; }
//        };
        sendBtn = new JButton(AllIcons.Actions.Execute);
        sendBtn.setToolTipText("发送 (Enter)");
        sendBtn.setFocusable(false);
        sendBtn.setOpaque(true);
        sendBtn.setBorderPainted(false);
        sendBtn.setPreferredSize(new Dimension(40, 0));
        sendBtn.addActionListener(e -> sendMessage());

        JBPanel<?>  inputRow = new   JBPanel<> (new BorderLayout(8, 0));
        inputRow.setOpaque(false);
        inputRow.add(inputScroll, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);
        inner.add(inputRow, BorderLayout.CENTER);

        JBPanel<?>  bottomBar = new JBPanel<> (new BorderLayout(0, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(JBUI.Borders.emptyTop(6));


        modelCombo.setSelectedItem(LoongCSettings.getInstance().getChatModelName());
//        modelCombo.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
        modelCombo.setFont(JBUI.Fonts.label(11));
        modelCombo.addActionListener(e -> {
            String sel = (String) modelCombo.getSelectedItem();
            if (sel != null) {
                // 根据选中项的名称，查找其在 LoongCSettings 中的索引
                List<ModelConfig> models = LoongCSettings.getInstance().getChatModels();
                for (int i = 0; i < models.size(); i++) {
                    if (models.get(i).getName().equals(sel)) {
                        LoongCSettings.getInstance().setCurrentChatModelIndex(i);
                        break;
                    }
                }
            }
        });
        modelCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshModelCombo();
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        bottomBar.add(modelCombo, BorderLayout.WEST);

        // Token 用量统计区（modelCombo 右侧，hint 左侧）
        tokenStatsLabel = new JBLabel("Token消耗实况");
//        tokenStatsLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        tokenStatsLabel.setFont(JBUI.Fonts.label(10));
        tokenStatsLabel.setForeground(mutedFg());
        tokenStatsLabel.setToolTipText("当前会话累计：输入 token / 输出 token | 缓存命中 / 未命中 | 预估费用");
        tokenStatsLabel.setBorder(JBUI.Borders.emptyLeft(8));
        bottomBar.add(tokenStatsLabel, BorderLayout.CENTER);

        JBLabel hint = new JBLabel("Enter 发送  |  Shift+Enter 换行");
        hint.setFont(JBUI.Fonts.label(10));
        hint.setForeground(mutedFg());
        bottomBar.add(hint, BorderLayout.EAST);

        inner.add(bottomBar, BorderLayout.SOUTH);
        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 右键菜单（复制/全选）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 为可选择的文本组件（JTextArea / JEditorPane）安装统一的右键菜单。
     * 菜单包含小标题 + 复制 + 全选。
     */
    private void installPopupMenu(JTextComponent comp, String title) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(new LineBorder(dividerColor(), 1, true));
        menu.setBackground(UIUtil.getPanelBackground());

        // 小标题（不可点击，起分组说明作用）
        JBLabel header = new JBLabel("  " + title);
        header.setFont(JBUI.Fonts.label(11).asBold());
        header.setForeground(mutedFg());
        header.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        menu.add(header);
        menu.addSeparator();

        // 复制选中内容
        JMenuItem copyItem = new JMenuItem("复制选中");
        copyItem.setFont(JBUI.Fonts.label(12));
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyItem.addActionListener(e -> {
            String sel = comp.getSelectedText();
            if (sel != null && !sel.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sel), null);
            }
        });
        menu.add(copyItem);

        // 复制全部（对于 AI 消息，复制原始文本更有用）
        JMenuItem copyAllItem = new JMenuItem("复制全部");
        copyAllItem.setFont(JBUI.Fonts.label(12));
        copyAllItem.addActionListener(e -> {
            String all = comp.getText();
            if (all != null && !all.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(all), null);
            }
        });
        menu.add(copyAllItem);

        // 全选
        JMenuItem selectAllItem = new JMenuItem("全选");
        selectAllItem.setFont(JBUI.Fonts.label(12));
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        selectAllItem.addActionListener(e -> comp.selectAll());
        menu.add(selectAllItem);

        comp.setComponentPopupMenu(menu);
    }

    /**
     * 为 AI JEditorPane 安装右键菜单（"复制全部"复制原始 Markdown 文本）
     */
    private void installPopupMenuForAi(JEditorPane pane, String rawMarkdown) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(new LineBorder(dividerColor(), 1, true));
        menu.setBackground(UIUtil.getPanelBackground());

        JBLabel header = new JBLabel("  LoongC 回复");
        header.setFont(JBUI.Fonts.label(11).asBold());
        header.setForeground(mutedFg());
        header.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        menu.add(header);
        menu.addSeparator();

        JMenuItem copySelItem = new JMenuItem("复制选中");
        copySelItem.setFont(JBUI.Fonts.label(12));
        copySelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copySelItem.addActionListener(e -> {
            String sel = pane.getSelectedText();
            if (sel != null && !sel.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sel), null);
            }
        });
        menu.add(copySelItem);

        JMenuItem copyMdItem = new JMenuItem("复制全部");
        copyMdItem.setFont(JBUI.Fonts.label(12));
        copyMdItem.addActionListener(e ->
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(rawMarkdown), null));
        menu.add(copyMdItem);

//        JMenuItem selectAllItem = new JMenuItem("全选");
//        selectAllItem.setFont(JBUI.Fonts.label(12));
//        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
//        selectAllItem.addActionListener(e -> pane.selectAll());
//        menu.add(selectAllItem);

        pane.setComponentPopupMenu(menu);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 发送逻辑
    // ─────────────────────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() && !isReceiving) return;

        if (isReceiving) {
            isReceiving = false;
            client.cancelCurrentStream();
            restoreSendButton();
            return;
        }

        inputField.setText("");
        addUserMessage(text);

        if ("/file".equals(text) || "/files".equals(text)) {
            readProjectFilesAndRespond();
            return;
        }

//        StringBuilder prompt = new StringBuilder(text);
//        if (text.contains("@")) {
//            String fc = extractFileReferences(text);
//            if (!fc.isEmpty()) prompt.append("\n\n").append(fc);
//        }
//        String ec = getEditorContext();
//        if (!ec.isEmpty()) prompt.append("\n\n当前编辑的文件内容：\n").append(ec);

        conversationHistory.add(new ChatMessage("user", text));
        ChatMessageEntity message = new ChatMessageEntity(
                DBChatHistoryRepository.getUUID(),
                currentSessionId,
                "user",
                text,
                (String) null,
                (String) null,
                (String) null,
                (String) null
        );
        ThreadHelper.executeAsync(project,()->DBChatHistoryRepository.saveMessage(message),null);
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
            conversationHistory.add(new ChatMessage("user",
                    "请分析以下项目文件，总结项目结构、主要功能和潜在问题：\n\n"
                            + FileReaderUtil.buildFileContext(files)));
            addAiMessage("正在分析 " + files.size() + " 个文件...\n");
            sendToApi();
        });
    }

    //业务层
    private void sendToApi() {
//        final int MAX_TOOL_ROUNDS = 5;
        isReceiving = true;
        statusLabel.setForeground(new Color(0x4CAF50));
        if(!sendBtn.getIcon().equals(AllIcons.Actions.Suspend)){
            sendBtn.setIcon(AllIcons.Actions.Suspend);
            sendBtn.setToolTipText("停止生成");
        }
//        if (toolRound >= MAX_TOOL_ROUNDS) {
//            ApplicationManager.getApplication().invokeLater(() -> {
//                addAiMessage("*(已达到工具调用上限，继续回答...)*\n");
//            });
//        }

        client.streamChat(conversationHistory, com.loongc.tools.ToolDefinitions.getAllTools(),
                new DeepSeekClient.StreamCallback() {
                    StringBuilder currentReasoning = new StringBuilder();
                    //StringBuilder currentMessage = new StringBuilder();
                    @Override
                    public void onMessage(String chunk) {
//                        System.out.println("message------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            //currentMessage.append(chunk);
                            // 第一次收到正式内容时，隐藏思考中状态
                            if (currentReasoningStatus != null && !currentReasoningStatus.getText().isEmpty()) {
                                currentReasoningStatus.setText("");
                                currentReasoningStatus.setVisible(false);
                            }
                            statusLabel.setText("回复中...");
                            statusLabel.setForeground(new Color(0x4CAF50));
                            appendStreamChunk(chunk);
                        });
                    }

                    //如果存在思考，则首先被执行
                    @Override
                    public void onReasoning(String reasoning) {
                        System.out.println("reasoning------------------------");
                        currentReasoning.append(reasoning);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            statusLabel.setText("思考中...");
                            statusLabel.setForeground(new Color(0xCE93D8));
                            appendReasoningChunk(reasoning);
                        });
                    }

                    @Override
                    public void onToolCalls(java.util.List<com.loongc.model.ChatMessage.ToolCall> toolCalls) {
                        System.out.println("onToolCalls------------------------:"+toolCalls);
                        ChatMessage assistantMsg = com.loongc.model.ChatMessage.assistantWithToolCalls(toolCalls);
                        if (!currentReasoning.isEmpty()) {
                            assistantMsg.setReasoning_content(currentReasoning.toString());
                        }
                        conversationHistory.add(assistantMsg);
                        ChatMessageEntity assistantEntity = new ChatMessageEntity(
                                DBChatHistoryRepository.getUUID(),
                                currentSessionId,
                                Constant.ROLE_assistant,    //助手：我调用的工具是toolCalls
                                null,                           // 发起工具调用时 content 为 null
                                GSON.toJson(toolCalls),         // 👈 核心：List 转成 String 丢给实体类
                                null,                           // toolCallId 为 null
                                null,                           // name 为 null
                                currentReasoning.toString()     // 思考过程
                        );
                        ThreadHelper.executeAsync(project,()->DBChatHistoryRepository.saveMessage(assistantEntity),null);
                        for (com.loongc.model.ChatMessage.ToolCall tc : toolCalls) {
                            String result = com.loongc.tools.ToolExecutor.execute(tc, project);
                            String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                            System.out.println(String.format("toolName = %s,result = %s",toolName,result));
                            addToolCallMessage(toolName, result);

                            conversationHistory.add(com.loongc.model.ChatMessage.toolResult(tc.getId(),toolName, result));

                            //持久化每次结果
                            ChatMessageEntity toolEntity = new ChatMessageEntity(
                                    DBChatHistoryRepository.getUUID(),
                                    currentSessionId,
                                    Constant.ROLE_tool,
                                    result,                     // 工具返回的结果字串
                                    (String) null,                       // toolCalls 为 null
                                    tc.getId(),                 // 👈 匹配当前工具的 call_id
                                    toolName,                   // 👈 工具名称
                                    (String) null                       // 工具没有思考过程
                            );
                            ThreadHelper.executeAsync(project,()->DBChatHistoryRepository.saveMessage(toolEntity),null);
                        }
                        sendToApi();
                    }

                    //调用消息 思考 工具链路都完成时才被调用。
                    @Override
                    public void onComplete() {
                        System.out.println("onComplete------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            isReceiving = false;
                            statusLabel.setText("就绪");
                            restoreSendButton();
                            statusLabel.setForeground(JBColor.GRAY);
                            if (!currentAiRawText.isEmpty()) {
                                ChatMessage assistant = new ChatMessage("assistant", currentAiRawText);
                                if (!currentReasoning.isEmpty()) {
                                    assistant.setReasoning_content(currentReasoning.toString()); // 👈 关键：带上思考过程
                                }
                                conversationHistory.add(assistant);
                                ChatMessageEntity chatMessageEntity = new ChatMessageEntity(
                                        DBChatHistoryRepository.getUUID(),
                                        currentSessionId,
                                        "assistant",
                                        currentAiRawText,
                                        (String) null,
                                        (String) null,
                                        (String) null,
                                        currentReasoning.toString()
                                );
                                ThreadHelper.executeAsync(project,()-> DBChatHistoryRepository.saveMessage(chatMessageEntity),null);
                                finalizeAiMessage();
                            }
                            clearStreamRefs();
                        });
                    }

                    @Override
                    public void onUsage(com.loongc.model.ChatResponse.Usage usage) {
                        System.out.println("onUsage------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> updateTokenStats(usage));
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println("onError------------------------");
                        ApplicationManager.getApplication().invokeLater(() -> {
                            isReceiving = false;
                            restoreSendButton();
                            statusLabel.setText("出错");
                            statusLabel.setForeground(JBColor.RED);
                            appendStreamChunk("\n\n**[错误]** " + error.getMessage());
                            finalizeAiMessage();
                            clearStreamRefs();
                        });
                    }
                });
    }

    /**
     * 累加本轮 usage 到会话统计，计算费用后刷新 tokenStatsLabel。
     *
     * DeepSeek 价格（元 / 百万 tokens，参考官方文档）：
     *   deepseek-chat / deepseek-v4-flash:
     *     缓存命中 0.02，未命中 1，输出 2
     *   deepseek-reasoner / deepseek-v4-pro:
     *     缓存命中 0.1，未命中 12，输出 24
     *   其他模型使用 deepseek-chat 价格作为默认值
     */
    private void updateTokenStats(com.loongc.model.ChatResponse.Usage usage) {
        if (usage == null) return;

        sessionPromptTokens     += usage.getPromptTokens();
        sessionCompletionTokens += usage.getCompletionTokens();
        sessionCacheHitTokens   += usage.getPromptCacheHitTokens();
        sessionCacheMissTokens  += usage.getPromptCacheMissTokens();

        // 根据当前选择的模型确定单价（元 / token）
        String model = (String) modelCombo.getSelectedItem();
        double hitPrice, missPrice, outPrice;
        if ("deepseek-v4-pro".equals(model)) {
            hitPrice  = 0.1  / 1_000_000.0;
            missPrice = 12.0 / 1_000_000.0;
            outPrice  = 24.0 / 1_000_000.0;
        } else {
            // deepseek-chat / deepseek-coder / 默认
            hitPrice  = 0.02 / 1_000_000.0;
            missPrice = 1.0  / 1_000_000.0;
            outPrice  = 2.0  / 1_000_000.0;
        }

        double totalCost = sessionCacheHitTokens  * hitPrice
                + sessionCacheMissTokens * missPrice
                + sessionCompletionTokens * outPrice;

        // 格式化费用：小于 0.01 分用科学计数，否则保留 4 位小数
        String costStr;
        if (totalCost < 0.0001) {
            costStr = String.format("¥%.2e", totalCost);
        } else {
            costStr = String.format("¥%.4f", totalCost);
        }

        // 缓存命中率
        int totalInput = sessionCacheHitTokens + sessionCacheMissTokens;
        String hitRateStr = totalInput > 0
                ? String.format("%.0f%%", sessionCacheHitTokens * 100.0 / totalInput)
                : "—";

        // 💡【核心修改 1】将原本的 %d 改为 %s，并调用单位转换函数缩写数字
        String text = String.format(
                "↑%s ↓%s  |  💾命中:%s(%s) 未命中:%s  |  %s",
                formatTokenCount(sessionPromptTokens),
                formatTokenCount(sessionCompletionTokens),
                formatTokenCount(sessionCacheHitTokens),
                hitRateStr,
                formatTokenCount(sessionCacheMissTokens),
                costStr
        );

        tokenStatsLabel.setText(text);
        tokenStatsLabel.setForeground(JBColor.GRAY);

        // 💡【核心修改 2】悬浮提示（Tooltip）中依然保留最精准的原始数字 %d，方便需要时核对
        tokenStatsLabel.setToolTipText(String.format(
                "<html>当前会话累计<br>"
                        + "输入 tokens：%s（缓存命中 %s + 未命中 %s）<br>"
                        + "输出 tokens：%s<br>"
                        + "缓存命中率：%s<br>"
                        + "预估费用：%s 元</html>",
                formatTokenCount(sessionPromptTokens),
                formatTokenCount(sessionCacheHitTokens),
                formatTokenCount(sessionCacheMissTokens),
                formatTokenCount(sessionCompletionTokens),
                hitRateStr,
                costStr
        ));
    }

    /**
     * 恢复发送按钮为默认状态（发送图标）
     */
    private void restoreSendButton() {
        if (sendBtn != null) {
            sendBtn.setIcon(AllIcons.Actions.Execute);
            sendBtn.setToolTipText("发送 (Enter)");
        }
    }

    /**
     * 💡【新增辅助方法】智能格式化 Token 计数
     * 规则：
     * - 950 -> "950"
     * - 1000 -> "1K"
     * - 1500 -> "1.5K"
     * - 12345 -> "12.3K"
     * - 1000000 -> "1M"
     */
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
     * 清理本轮流式输出的所有组件引用
     */
    private void clearStreamRefs() {
        currentStreamArea  = null;
        currentBubbleInner = null;
        currentAiRawText   = "";
        currentReasoningArea   = null;
        currentReasoningPanel  = null;
        currentReasoningContent = null;
        currentReasoningStatus = null;
        currentReasoningText   = "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 流式追加 & Markdown 渲染切换
    // ─────────────────────────────────────────────────────────────────────────

    private void appendStreamChunk(String chunk) {
        currentAiRawText += chunk;
        if (currentStreamArea == null) {
            addAiStreamRow();
        }
        currentStreamArea.append(chunk);
        currentStreamArea.setCaretPosition(currentStreamArea.getDocument().getLength());
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /**
     * 追加思考过程流式块，自动创建思考区块
     */
    private void appendReasoningChunk(String chunk) {
        currentReasoningText += chunk;
        if (currentReasoningPanel == null) {
            addAiReasoningStreamRow();
        }
        if (currentReasoningArea != null) {
            currentReasoningArea.append(chunk);
            currentReasoningArea.setCaretPosition(currentReasoningArea.getDocument().getLength());
        }
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /**
     * 流式思考过程区块：可折叠、与正式回复视觉区分
     */
    private void addAiReasoningStreamRow() {
        Color bg = reasoningBg();
        Color fg = reasoningFg();
        Color bd = reasoningBd();

        // ── 标题栏 ──
        JBLabel arrowLabel = new JBLabel(isReasoningCollapsed ? "▶" : "▼");
        arrowLabel.setFont(JBUI.Fonts.label(11));
        arrowLabel.setForeground(fg);

        JBLabel titleLabel = new JBLabel(AllIcons.Actions.IntentionBulb);
        titleLabel.setText(" 思考过程");
        titleLabel.setFont(JBUI.Fonts.label(12).asBold());
        titleLabel.setForeground(fg);

        JBLabel statusLabel = new JBLabel("思考中…");
        statusLabel.setFont(JBUI.Fonts.label(11));
        statusLabel.setForeground(fg);

        JBPanel<?> header = new JBPanel<>(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 10));

        JBPanel<?> leftGroup = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftGroup.setOpaque(false);
        leftGroup.add(arrowLabel);
        leftGroup.add(titleLabel);

        header.add(leftGroup, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);

        // ── 内容区（JBTextArea）──
        JBTextArea reasoningArea = new JBTextArea();
        reasoningArea.setFont(JBUI.Fonts.label(12));
        reasoningArea.setLineWrap(true);
        reasoningArea.setWrapStyleWord(true);
        reasoningArea.setEditable(false);
        reasoningArea.setBackground(bg);
        reasoningArea.setForeground(fg);
        reasoningArea.setCaretColor(fg);
        reasoningArea.setBorder(JBUI.Borders.empty(6, 10));
        installPopupMenu(reasoningArea, "LoongC 思考过程");

        JBPanel<?> contentPanel = new JBPanel<>(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(reasoningArea, BorderLayout.CENTER);
        contentPanel.setVisible(!isReasoningCollapsed);

        // ── 整体面板 ──
        JBPanel<?> inner = new JBPanel<>(new BorderLayout());
        inner.setOpaque(false);
        inner.add(header, BorderLayout.NORTH);
        inner.add(contentPanel, BorderLayout.CENTER);

        JBPanel<?> bubble = new JBPanel<>(new BorderLayout());
        bubble.setBackground(bg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bd, 1),
                JBUI.Borders.empty(1)
        ));
        bubble.add(inner, BorderLayout.CENTER);

        // 点击标题栏折叠/展开
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Runnable toggleAction = () -> {
            isReasoningCollapsed = !isReasoningCollapsed;
            arrowLabel.setText(isReasoningCollapsed ? "▶" : "▼");
            contentPanel.setVisible(!isReasoningCollapsed);
            bubble.revalidate();
            bubble.repaint();
            messagesPanel.revalidate();
            messagesPanel.repaint();
        };
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });
        arrowLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });
        titleLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });

        // 引用保存
        currentReasoningArea    = reasoningArea;
        currentReasoningPanel   = bubble;
        currentReasoningContent = contentPanel;
        currentReasoningStatus  = statusLabel;

        JBPanel<?> row = wrapAiRow("LoongC", bubble);
        //row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        messagesPanel.add(row);
        messagesPanel.add(Box.createVerticalStrut(8));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }


    /**
     * 流式完成：将气泡内 JTextArea 替换为 JEditorPane 渲染 Markdown，并安装右键菜单
     */
    private void finalizeAiMessage() {
        if (currentBubbleInner == null || currentAiRawText.isEmpty()) return;
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = aiBubbleBg();
        String rawMd = currentAiRawText;  // 保留原始 MD 供右键"复制 Markdown"使用
        String html  = MarkdownUtil.toHtml(rawMd, dark, bubbleBg);

        JEditorPane mdPane = new JEditorPane("text/html", html);
        mdPane.setEditable(false);
        mdPane.setOpaque(true);
        mdPane.setBackground(bubbleBg);
        mdPane.setForeground(UIUtil.getLabelForeground());
        mdPane.setBorder(JBUI.Borders.empty(10, 14));
        mdPane.setCaretPosition(0);
        installPopupMenuForAi(mdPane, rawMd);

        JBPanel<?> bubbleInner = currentBubbleInner;
        mdPane.addPropertyChangeListener("preferredSize", e ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    bubbleInner.revalidate();
                    messagesPanel.revalidate();
                    messagesPanel.repaint();
                    scrollToBottom();
                })
        );

        bubbleInner.removeAll();
        bubbleInner.add(mdPane, BorderLayout.CENTER);
        bubbleInner.revalidate();
        bubbleInner.repaint();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 消息行构建
    // ─────────────────────────────────────────────────────────────────────────

    private void addUserMessage(String content) {
        messagesPanel.add(buildUserRow(content));
        messagesPanel.add(Box.createVerticalStrut(12));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    //
    private void addAiMessage(String content) {
        messagesPanel.add(buildAiRow(content));
        messagesPanel.add(Box.createVerticalStrut(12));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /** 流式 AI 消息：先用 JTextArea 占位，并安装右键菜单 */
    private void addAiStreamRow() {
        Color bubbleBg = aiBubbleBg();

        JBTextArea streamArea = new JBTextArea();
        streamArea.setFont(JBUI.Fonts.label(13));
        streamArea.setLineWrap(true);
        streamArea.setWrapStyleWord(true);
        streamArea.setEditable(false);
        streamArea.setBackground(bubbleBg);
        // 确保前景色不为透明，避免在某些主题下看不见
        streamArea.setForeground(UIUtil.getLabelForeground());
        streamArea.setCaretColor(UIUtil.getLabelForeground());
        streamArea.setBorder(JBUI.Borders.empty(10, 14));
        installPopupMenu(streamArea, "LoongC 回复（接收中）");

        JBPanel<?> bubbleInner = new  JBPanel<>(new BorderLayout());
        bubbleInner.setOpaque(false);
        bubbleInner.add(streamArea, BorderLayout.CENTER);

        JBPanel<?> bubble = new JBPanel<>(new BorderLayout());
        bubble.setBackground(bubbleBg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(dividerColor(), 1),
                JBUI.Borders.empty(1)
        ));
        bubble.add(bubbleInner, BorderLayout.CENTER);

        currentStreamArea  = streamArea;
        currentBubbleInner = bubbleInner;

        JBPanel<?> row = wrapAiRow("LoongC", bubble);
        //row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        messagesPanel.add(row);
        messagesPanel.add(Box.createVerticalStrut(12));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }


    /**
     * 显示工具调用信息（灰色小字，不进入对话历史）
     */
    private void addToolCallMessage(String toolName, String result) {
        JBPanel<?> row = new JBPanel<>(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        row.setBorder(JBUI.Borders.empty(2, 40, 2, 12));

        JBLabel lbl = new JBLabel(AllIcons.Actions.Search);
        lbl.setText(" " + toolName + " 已执行");
        lbl.setFont(JBUI.Fonts.label(11));
        lbl.setForeground(mutedFg());
        row.add(lbl, BorderLayout.WEST);

        messagesPanel.add(row);
        messagesPanel.revalidate();

        ThreadHelper.runOnUi(project,()->{
            JScrollBar sb = messagesScrollPane.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    /**
     * 从历史记录的 toolCallsJson 构建工具调用信息块
     */
    private JBPanel<?> buildToolCallsBlock(String toolCallsJson) {
        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(JBUI.Borders.empty(2, 40, 2, 12));
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JBPanel<?> inner = new JBPanel<>(null);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);

        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<ChatMessage.ToolCall>>(){}.getType();
            java.util.List<ChatMessage.ToolCall> toolCalls = gson.fromJson(toolCallsJson, type);
            if (toolCalls != null) {
                for (ChatMessage.ToolCall tc : toolCalls) {
                    String toolName = tc.getFunction() != null ? tc.getFunction().getName() : "unknown";
                    JBLabel lbl = new JBLabel(AllIcons.Actions.IntentionBulb);
                    lbl.setText(" 请求调用工具: " + toolName);
                    lbl.setFont(JBUI.Fonts.label(11));
                    lbl.setForeground(mutedFg());
                    inner.add(lbl);
                }
            }
        } catch (Exception e) {
            JBLabel lbl = new JBLabel("工具调用信息（解析失败）");
            lbl.setFont(JBUI.Fonts.label(11));
            lbl.setForeground(mutedFg());
            inner.add(lbl);
        }

        wrapper.add(inner, BorderLayout.WEST);
        return wrapper;
    }

    private JBPanel<?> buildAiReasoningBlock(String reasoningContent, boolean collapsed) {
        Color bg = reasoningBg();
        Color fg = reasoningFg();
        Color bd = reasoningBd();

        JBLabel arrowLabel = new JBLabel(collapsed ? "▶" : "▼");
        arrowLabel.setFont(JBUI.Fonts.label(11));
        arrowLabel.setForeground(fg);

        JBLabel titleLabel = new JBLabel(AllIcons.Actions.IntentionBulb);
        titleLabel.setText(" 思考过程");
        titleLabel.setFont(JBUI.Fonts.label(12).asBold());
        titleLabel.setForeground(fg);

        JBPanel<?> header = new JBPanel<>(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 10));

        JBPanel<?> leftGroup = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftGroup.setOpaque(false);
        leftGroup.add(arrowLabel);
        leftGroup.add(titleLabel);
        header.add(leftGroup, BorderLayout.WEST);

        JBTextArea reasoningArea = new JBTextArea(reasoningContent);
        reasoningArea.setFont(JBUI.Fonts.label(12));
        reasoningArea.setLineWrap(true);
        reasoningArea.setWrapStyleWord(true);
        reasoningArea.setEditable(false);
        reasoningArea.setBackground(bg);
        reasoningArea.setForeground(fg);
        reasoningArea.setCaretColor(fg);
        reasoningArea.setBorder(JBUI.Borders.empty(6, 10));
        installPopupMenu(reasoningArea, "LoongC 思考过程");

        JBPanel<?> contentPanel = new JBPanel<>(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(reasoningArea, BorderLayout.CENTER);
        contentPanel.setVisible(!collapsed);

        JBPanel<?> inner = new JBPanel<>(new BorderLayout());
        inner.setOpaque(false);
        inner.add(header, BorderLayout.NORTH);
        inner.add(contentPanel, BorderLayout.CENTER);

        JBPanel<?> bubble = new JBPanel<>(new BorderLayout());
        bubble.setBackground(bg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bd, 1),
                JBUI.Borders.empty(1)
        ));
        bubble.add(inner, BorderLayout.CENTER);

        Runnable toggleAction = () -> {
            boolean nowCollapsed = !contentPanel.isVisible();
            arrowLabel.setText(nowCollapsed ? "▶" : "▼");
            contentPanel.setVisible(nowCollapsed);
            bubble.revalidate();
            bubble.repaint();
            messagesPanel.revalidate();
            messagesPanel.repaint();
        };
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });
        arrowLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });
        titleLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleAction.run(); }
        });

        JBPanel<?> row = wrapAiRow("LoongC", bubble);
        //row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    /** 非流式 AI 消息行（欢迎语等）：直接渲染 Markdown，安装右键菜单 */
        private JBPanel<?> buildAiRow(String mdContent) {
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = aiBubbleBg();
        String html = MarkdownUtil.toHtml(mdContent, dark, bubbleBg);


        JEditorPane mdPane = new JEditorPane("text/html", html);
        mdPane.setEditable(false);
        mdPane.setOpaque(true);
        mdPane.setBackground(bubbleBg);
        mdPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        mdPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                // 可选：在浏览器中打开链接
            }
        });
        mdPane.setForeground(UIUtil.getLabelForeground());
        mdPane.setBorder(JBUI.Borders.empty(10, 14));
        mdPane.setCaretPosition(0);
        installPopupMenuForAi(mdPane, mdContent);

        JBPanel<?> bubbleInner = new JBPanel<>(new BorderLayout());
        bubbleInner.setOpaque(false);
        bubbleInner.add(mdPane, BorderLayout.CENTER);

        JBPanel<?> bubble = new JBPanel<>(new BorderLayout());
        bubble.setBackground(bubbleBg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(dividerColor(), 1),
                JBUI.Borders.empty(1)
        ));
        bubble.add(bubbleInner, BorderLayout.CENTER);

        JBPanel<?> row = wrapAiRow("LoongC", bubble);
        //row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    /**
     * 用户消息行：+ 圆角蓝色气泡靠右，带右键菜单
     */
    private JBPanel<?> buildUserRow(String content) {
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = userBubbleBg();
        String html = MarkdownUtil.toHtml(content, dark, bubbleBg);


        JEditorPane mdPane = new JEditorPane("text/html", html);
        mdPane.setEditable(false);
        mdPane.setOpaque(true);
        mdPane.setBackground(bubbleBg);
        mdPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        mdPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                // 可选：在浏览器中打开链接
            }
        });
        mdPane.setForeground(UIUtil.getLabelForeground());
        mdPane.setBorder(JBUI.Borders.empty(10, 14));
        mdPane.setCaretPosition(0);
        installPopupMenuForAi(mdPane, content);

        JBPanel<?> bubbleInner = new JBPanel<>(new BorderLayout());
        bubbleInner.setOpaque(false);
        bubbleInner.add(mdPane, BorderLayout.CENTER);

        JBPanel<?> bubble = new JBPanel<>(new BorderLayout());
        bubble.setBackground(bubbleBg);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(dividerColor(), 1),
                JBUI.Borders.empty(1)
        ));
        bubble.add(bubbleInner, BorderLayout.CENTER);

        JBLabel nameLabel = new JBLabel("你");
        nameLabel.setFont(JBUI.Fonts.label(11));
        nameLabel.setForeground(mutedFg());
        nameLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        nameLabel.setBorder(JBUI.Borders.emptyBottom(2));

        JBPanel<?> contentArea = new JBPanel<>(null);
        contentArea.setLayout(new BoxLayout(contentArea, BoxLayout.Y_AXIS));
        contentArea.setOpaque(false);
        nameLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bubble.setAlignmentX(Component.RIGHT_ALIGNMENT);
        contentArea.add(nameLabel);
        contentArea.add(bubble);

        // 使用 IntelliJ 用户图标
        JBLabel avatar = new JBLabel(AllIcons.General.User);
        avatar.setBorder(JBUI.Borders.emptyTop(4));

        JBPanel<?> row = new JBPanel<>(new BorderLayout(8, 0));
        row.setOpaque(false);

        JBPanel<?> leftSpacer = new JBPanel<>(null);
        leftSpacer.setOpaque(false);
        row.add(leftSpacer, BorderLayout.CENTER);

        JBPanel<?> rightGroup = new JBPanel<>(new BorderLayout(8, 0));
        rightGroup.setOpaque(false);
        rightGroup.add(contentArea, BorderLayout.CENTER);
        rightGroup.add(avatar, BorderLayout.EAST);
        row.add(rightGroup, BorderLayout.EAST);

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }
    /**
     * AI 消息行包装：[蓝色圆形头像"L"] [8px] [内容区（名称+气泡）] [60px空白]
     */
    private JBPanel<?> wrapAiRow(String name, JBPanel<?> bubble) {
        JBLabel nameLabel = new JBLabel(name);
        nameLabel.setFont(JBUI.Fonts.label(11));
        nameLabel.setForeground(mutedFg());
        nameLabel.setBorder(JBUI.Borders.emptyBottom(2));

        JBPanel<?> contentArea = new JBPanel<>(null);
        contentArea.setLayout(new BoxLayout(contentArea, BoxLayout.Y_AXIS));
        contentArea.setOpaque(false);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentArea.add(nameLabel);
        contentArea.add(bubble);

        // 使用 IntelliJ AI/机器人图标
        JBLabel avatar = new JBLabel(AllIcons.Actions.IntentionBulb);
        avatar.setBorder(JBUI.Borders.emptyTop(4));

        JBPanel<?> row = new JBPanel<>(new BorderLayout(8, 0));
        row.setOpaque(false);
        JBPanel<?> avatarWrapper = new JBPanel<>(new BorderLayout());
        avatarWrapper.setOpaque(false);
        avatarWrapper.add(avatar, BorderLayout.NORTH);
        row.add(avatarWrapper, BorderLayout.WEST);
        row.add(contentArea, BorderLayout.CENTER);

        JBPanel<?> rightSpacer = new JBPanel<>(null);
        rightSpacer.setOpaque(false);
        rightSpacer.setPreferredSize(new Dimension(60, 0));
        row.add(rightSpacer, BorderLayout.EAST);
        return row;
    }
    // ─────────────────────────────────────────────────────────────────────────
    // 头像组件
    // ─────────────────────────────────────────────────────────────────────────

    /** 字母圆形头像（AI 使用） */
    private JPanel buildLetterAvatar(String letter, Color bgColor) {
        JPanel avatar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        avatar.setOpaque(false);
        Dimension size = new Dimension(30, 30);
        avatar.setPreferredSize(size);
        avatar.setMinimumSize(size);
        avatar.setMaximumSize(size);

        JLabel lbl = new JLabel(letter);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        lbl.setVerticalAlignment(SwingConstants.CENTER);
        avatar.add(lbl, BorderLayout.CENTER);
        return avatar;
    }

    /**
     * 颜文字头像（用户使用）：透明背景 + 颜文字表情，尺寸稍大以容纳多字符
     */
    private JPanel buildKaomojiAvatar() {
        JPanel avatar = new JPanel(new BorderLayout());
        avatar.setOpaque(false);
        Dimension size = new Dimension(38, 38);
        avatar.setPreferredSize(size);
        avatar.setMinimumSize(size);
        avatar.setMaximumSize(size);

        JLabel lbl = new JLabel(USER_KAOMOJI_PICK);
        lbl.setFont(new Font("Monospaced", Font.BOLD, 15));
        lbl.setForeground(JBColor.namedColor("Label.foreground", Color.WHITE));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        lbl.setVerticalAlignment(SwingConstants.CENTER);
        avatar.add(lbl, BorderLayout.CENTER);
        return avatar;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 圆角面板
    // ─────────────────────────────────────────────────────────────────────────

    private static class RoundedPanel extends JPanel {
        private final Color bgColor;
        private final int   radius;

        RoundedPanel(Color bgColor, int radius) {
            this.bgColor = bgColor;
            this.radius  = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    private void scrollToBottom() {
        JScrollBar v = messagesScrollPane.getVerticalScrollBar();
        v.setValue(v.getMaximum());
    }


    /**
     * 显示历史会话面板
     */
    public void showHistoryPanel() {
        refreshHistoryList();
        cardLayout.show(cardPanel, HISTORY_CARD);
    }

    /**
     * 显示聊天面板（从历史面板返回）
     */
    public void showChatPanel() {
        cardLayout.show(cardPanel, CHAT_CARD);
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
        titleLabel.setFont(JBUI.Fonts.label(15).asBold());
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


    private void showHistoryPopup(MouseEvent e) {
        int idx = historyList.locationToIndex(e.getPoint());
        if (idx < 0) return;
        historyList.setSelectedIndex(idx);
        HistoryItem item = historyListModel.getElementAt(idx);
        if (item == null || item.getSession() == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("打开会话", AllIcons.Actions.ShowCode);
        openItem.addActionListener(ev -> {
            switchToSession(item.getSession().getId());
            showChatPanel();
        });
        menu.add(openItem);

        JMenuItem deleteItem = new JMenuItem("删除会话", AllIcons.Actions.GC);
        deleteItem.addActionListener(ev -> deleteHistorySession(item.getSession()));
        menu.add(deleteItem);

        menu.show(historyList, e.getX(), e.getY());
    }

    private void openSelectedHistorySession() {
        HistoryItem selected = historyList.getSelectedValue();
        System.out.println("selected = "+selected);
        if (selected != null && selected.getSession() != null) {
            switchToSession(selected.getSession().getId());
            showChatPanel();
        }
    }

    private void refreshHistoryList() {
        historyListModel.clear();
        List<ChatSessionEntity> sessions = DBChatHistoryRepository.listSessions();
        System.out.println("sessions = "+sessions);
        if (sessions.isEmpty()) {
            historyListModel.addElement(new HistoryItem(null, "暂无历史会话", ""));
            return;
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd HH:mm");
        for (ChatSessionEntity s : sessions) {
            String title = s.getName() != null && !s.getName().isEmpty() ? s.getName() : "未命名会话";
            String time = s.getCreatedAt() != null ? df.format(s.getCreatedAt()) : "";
            String info = s.getChatMessageCount() + " 条消息  ·  " + time;
            historyListModel.addElement(new HistoryItem(s, title, info));
        }
    }

    private void deleteHistorySession(ChatSessionEntity session) {
        if (session == null) return;
        int result = JOptionPane.showConfirmDialog(
                this,
                "确定要删除会话「" + session.getName() + "」吗？",
                "删除会话",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result == JOptionPane.YES_OPTION) {
            DBChatHistoryRepository.deleteSession(session.getId());
            if (session.getId().equals(currentSessionId)) {
                createNewSession();
            }
            refreshHistoryList();
        }
    }

    private void deleteAllSessions() {
        int result = JOptionPane.showConfirmDialog(
                this,
                "确定要删除所有历史会话吗？此操作不可恢复。",
                "删除所有会话",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result == JOptionPane.YES_OPTION) {
            List<ChatSessionEntity> sessions = DBChatHistoryRepository.listSessions();
            for (ChatSessionEntity s : sessions) {
                DBChatHistoryRepository.deleteSession(s.getId());
            }
            createNewSession();
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

    public void switchToSession(String sessionId) {
        if (sessionId == null || sessionId.equals(currentSessionId)) return;

        // 取消当前流
        if (isReceiving) {
            client.cancelCurrentStream();
        }
        isReceiving = false;
        restoreSendButton();
        clearStreamRefs();

        currentSessionId = sessionId;
        historyLoadedCount = 0;
        historyTotalCount = 10;
        isLoadingHistory = false;
        loadingIndicator = null;

        // 重置 token 统计
        sessionPromptTokens = 0;
        sessionCompletionTokens = 0;
        sessionCacheHitTokens = 0;
        sessionCacheMissTokens = 0;
        if (tokenStatsLabel != null) tokenStatsLabel.setText("—");

        // 加载全部消息到 conversationHistory（用于 API 上下文）
        ChatMessage systemMsg = conversationHistory.isEmpty() ? null : conversationHistory.get(0);
        conversationHistory.clear();
        if (systemMsg != null) conversationHistory.add(systemMsg);

        ThreadHelper.queryAsync(project,
                ()->DBChatHistoryRepository.getRecentMessages(sessionId,10,0),
                (uiMsgs)->{
                    System.out.println("uiMsgs = "+uiMsgs);
                    historyLoadedCount = uiMsgs.size();
                    messagesPanel.removeAll();
                    if(CollectUtils.isNotEmpty(uiMsgs)){
                        for (ChatMessageEntity rec : uiMsgs) {
                            ChatMessage msg = new ChatMessage();
                            msg.setRole(rec.getRole());
                            msg.setContent(rec.getContent());
                            msg.setReasoning_content(rec.getReasoningContent()); // 还原 DeepSeek 思考过程
                            msg.setTool_call_id(rec.getToolCallId());             // 还原工具调用 ID
                            msg.setName(rec.getName());                           // 还原工具名称
                            String toolCallsJson = rec.getToolCallsJson();
                            if (StringUtils.isNotBlank(toolCallsJson)) {
                                try {
                                    java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<List<ChatMessage.ToolCall>>(){}.getType();
                                    List<ChatMessage.ToolCall> toolCallsList = GSON.fromJson(toolCallsJson, listType);
                                    msg.setTool_calls(toolCallsList);
                                } catch (Exception e) {
                                    System.err.println("还原历史 tool_calls 失败: " + e.getMessage());
                                }
                            }
                            conversationHistory.add(msg);
                        }
                        // UI 渲染最新 10 条（从尾部取）
                        int startIndex = Math.max(0, uiMsgs.size() - 10);
                        List<ChatMessageEntity> subList = uiMsgs.subList(startIndex, uiMsgs.size());
                        for (ChatMessageEntity rec : subList) {
                            renderMessageRecord(rec);
                        }
                    }
                    messagesPanel.revalidate();
                    messagesPanel.repaint();
                    scrollToBottom();
        });
    }

    /**
     * 渲染一条持久化的消息记录到 UI
     */
    private void renderMessageRecord(ChatMessageEntity rec) {
        if (Constant.ROLE_user.equals(rec.getRole())) {
            addUserMessage(rec.getContent());
        } else if (Constant.ROLE_assistant.equals(rec.getRole())) {
            renderAssistantMessage(rec.getContent(), rec.getReasoningContent(), rec.getToolCallsJson());
        } else if (Constant.ROLE_tool.equals(rec.getRole())) {
            System.out.println("name: "+rec.getName());
            //renderToolMessage(rec.getContent(), rec.getName(), rec.getToolCallId());
        }
    }

    private void renderToolMessage(String content, String toolName, String toolCallId) {
        // 构建工具执行结果 UI（例如灰色小字，显示工具名 + 结果）
        JBPanel<?> row = new JBPanel<>(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        row.setBorder(JBUI.Borders.empty(2, 40, 2, 12));

        JBLabel lbl = new JBLabel(AllIcons.Actions.Search);
        lbl.setText(" " + toolName + " 已执行");
        lbl.setFont(JBUI.Fonts.label(11));
        lbl.setForeground(mutedFg());
        row.add(lbl, BorderLayout.WEST);

        // 显示结果（可折叠或简短显示）
        JBTextArea resultArea = new JBTextArea(content);
        resultArea.setEditable(false);
        resultArea.setFont(JBUI.Fonts.label(11));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setBackground(UIUtil.getPanelBackground());
        resultArea.setBorder(JBUI.Borders.empty(4, 0));
        row.add(resultArea, BorderLayout.CENTER);

        messagesPanel.add(row);
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    private void renderAssistantMessage(String content, String reasoningContent, String toolCallsJson) {
        // 思考过程
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            messagesPanel.add(buildAiReasoningBlock(reasoningContent, true));
        }
        // 工具调用
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            messagesPanel.add(buildToolCallsBlock(toolCallsJson));
        }
        // 消息正文（content 可能为 null，如纯工具调用请求）
        if (content != null && !content.isEmpty()) {
            messagesPanel.add(buildAiRow(content));
        }
        messagesPanel.add(Box.createVerticalStrut(12));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /**
     * 检测是否需要加载更旧的历史消息
     */
    private void maybeLoadOlderMessages() {
        if (isLoadingHistory || historyLoadedCount >= historyTotalCount) return;
        JScrollBar vbar = messagesScrollPane.getVerticalScrollBar();
        // 滚动到顶部附近（值 < 80）时触发加载
        if (vbar.getValue() <= 80) {
            loadOlderMessages();
        }
    }

    /**
     * 懒加载更旧的消息：插入到 UI 顶部，保持滚动位置
     */
    private void loadOlderMessages() {
        if (isLoadingHistory || currentSessionId == null) return;
        isLoadingHistory = true;

        ApplicationManager.getApplication().invokeLater(() -> {
            showLoadingIndicator();
            int oldHeight = messagesPanel.getHeight();
            JScrollBar vbar = messagesScrollPane.getVerticalScrollBar();

            // 加载下一页（更旧的消息）
            List<ChatMessageEntity> records = DBChatHistoryRepository
                    .getRecentMessages(currentSessionId, historyLoadedCount,10);
            if (records.isEmpty()) {
                hideLoadingIndicator();
                isLoadingHistory = false;
                return;
            }

            // 倒序排列（从旧到新），然后插入到顶部
            Collections.reverse(records);
            for (ChatMessageEntity rec : records) {
                Component comp = buildMessageComponent(rec);
                if (comp != null) {
                    messagesPanel.add(comp, 0);
                }
            }
            historyLoadedCount += records.size();

            hideLoadingIndicator();
            messagesPanel.revalidate();
            messagesPanel.repaint();

            // 保持滚动位置（新增内容在上方，滚动条向下偏移）
            int newHeight = messagesPanel.getHeight();
            vbar.setValue(vbar.getValue() + (newHeight - oldHeight));

            isLoadingHistory = false;
        });
    }

    private Component buildMessageComponent(ChatMessageEntity rec) {
        if ("user".equals(rec.getRole())) {
            JPanel row = buildUserRow(rec.getContent());
            JPanel wrapper = new JBPanel<>(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(row, BorderLayout.CENTER);
            wrapper.add(Box.createVerticalStrut(12), BorderLayout.SOUTH);
            return wrapper;
        } else if ("assistant".equals(rec.getRole())) {
            JPanel row = buildAiRow(rec.getContent());
            JPanel wrapper = new JBPanel<>(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(row, BorderLayout.CENTER);
            wrapper.add(Box.createVerticalStrut(12), BorderLayout.SOUTH);
            return wrapper;
        }
        return null;
    }


    private void showLoadingIndicator() {
        if (loadingIndicator != null) return;
        loadingIndicator = buildLoadingIndicator();
        messagesPanel.add(loadingIndicator, 0);
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private void hideLoadingIndicator() {
        if (loadingIndicator != null) {
            messagesPanel.remove(loadingIndicator);
            loadingIndicator = null;
        }
    }

    private JBPanel<?> buildLoadingIndicator() {
        JBPanel<?> panel = new JBPanel<>(new FlowLayout(FlowLayout.CENTER));
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        JBLabel label = new JBLabel("加载中…", AllIcons.Actions.Refresh, SwingConstants.LEFT);
        label.setFont(JBUI.Fonts.label(12));
        label.setForeground(mutedFg());
        panel.add(label);
        return panel;
    }

    /**
     * 创建新会话：生成会话ID，清空聊天区，输出问候语并入库
     */
    public void createNewSession() {
        // 取消当前正在进行的流
        if (isReceiving) {
            client.cancelCurrentStream();
        }
        isReceiving = false;
        restoreSendButton();
        clearStreamRefs();



        // 创建数据库会话
        ChatSessionEntity session = DBChatHistoryRepository.createSession("新会话");
        currentSessionId = session.getId();

        historyLoadedCount = 0;
        historyTotalCount = 0;
        isLoadingHistory = false;
        loadingIndicator = null;

        messagesPanel.removeAll();
        messagesPanel.revalidate();
        messagesPanel.repaint();

        // 清空对话历史（保留 system）
        ChatMessage systemMsg = conversationHistory.isEmpty() ? null : conversationHistory.get(0);
        conversationHistory.clear();
        if (systemMsg != null) conversationHistory.add(systemMsg);

        // 重置 token 统计
        sessionPromptTokens = 0;
        sessionCompletionTokens = 0;
        sessionCacheHitTokens = 0;
        sessionCacheMissTokens = 0;
        if (tokenStatsLabel != null) tokenStatsLabel.setText("—");

        // 问候语入库并显示
        ChatMessageEntity messageEntity = new ChatMessageEntity(
                DBChatHistoryRepository.getUUID(),
                currentSessionId,
                Constant.ROLE_assistant,
                LoongCSettings.getInstance().getSayHello(),
                (String) null,
                (String) null,
                (String) null,
                (String) null
        );

        ThreadHelper.executeAsync(project,()-> DBChatHistoryRepository.saveMessage(messageEntity),()->{
            addAiMessage(LoongCSettings.getInstance().getSayHello());
        });
    }

    private void refreshModelCombo() {
        // 获取最新模型列表
        List<ModelConfig> models = LoongCSettings.getInstance().getChatModels();
        System.out.println("models:"+models);
        if (models == null || models.isEmpty()) {
            return;
        }

        // 记住当前选中的模型 ID（如果当前有选中项）
        String currentSelectedId = null;
        if (modelCombo.getSelectedIndex() != -1) {
            currentSelectedId = modelCombo.getItemAt(modelCombo.getSelectedIndex());
        }

        // 清空并重新填充
        modelCombo.removeAllItems();
        for (ModelConfig config : models) {
            // 你可以选择显示 config.getModelId() 或 config.getName()
            modelCombo.addItem(config.getName());
        }

        // 恢复选中项（按 ID 匹配）
        if (currentSelectedId != null) {
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                if (modelCombo.getItemAt(i).equals(currentSelectedId)) {
                    modelCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        // 如果未选中（比如之前选项被删了），默认选第一个
        if (modelCombo.getSelectedIndex() == -1 && modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
    }
}
