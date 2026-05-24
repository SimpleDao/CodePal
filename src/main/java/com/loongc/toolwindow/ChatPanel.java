package com.loongc.toolwindow;

import com.loongc.api.DeepSeekClient;
import com.loongc.api.model.ChatMessage;
import com.loongc.settings.LoongCSettings;
import com.loongc.utils.FileReaderUtil;
import com.loongc.utils.MarkdownUtil;
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
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
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

    // 品牌色 / 用户气泡
    private static final Color BRAND_COLOR    = new Color(0x4B8EF0);
    private static final Color USER_BG        = new Color(0x3C3F41);
    private static final Color USER_FG        = Color.WHITE;
    // AI 气泡（跟随主题）
    private static final Color AI_BG_DARK     = new Color(0x3C3F41);
    private static final Color AI_BG_LIGHT    = new Color(0xF0F1F3);
    // 头像背景（AI 用圆圈+字母，用户用颜文字）
    private static final Color AI_AVATAR_BG   = new Color(0x4E7EC8);
    // 上下文条
    private static final Color CTX_BG_DARK    = new Color(0x313438);
    private static final Color CTX_BG_LIGHT   = new Color(0xE8EAF0);
    private static final Color CTX_ACCENT     = new Color(0x4B8EF0);
    // 输入区
    private static final Color INPUT_BG_DARK  = new Color(0x45484B);
    private static final Color DIVIDER_DARK   = new Color(0x4E5157);
    private static final Color SEND_HOVER     = new Color(0x3570D8);

    private static final String USER_KAOMOJI_PICK;
    static {
        USER_KAOMOJI_PICK = "\uD83C\uDF93";
    }

    private final Project project;
    private final DeepSeekClient client;
    private final List<ChatMessage> conversationHistory;

    private final JPanel messagesPanel;
    private final JBScrollPane messagesScrollPane;
    private final JTextArea inputField;
    private final JComboBox<String> modelCombo;
    private final JLabel statusLabel;
    /** 底部 token 用量 + 费用统计标签（显示在 modelCombo 右侧） */
    private JLabel tokenStatsLabel;

    // 文件上下文条组件
    private final JPanel  ctxBar;
    private final JLabel  ctxFileLabel;
    private final JLabel  ctxLineLabel;
    private final JButton ctxInsertBtn;
    private String ctxCurrentFile = null;
    private int    ctxStartLine   = -1;
    private int    ctxEndLine     = -1;

    // 当前正在接收的 AI 消息组件引用
    private boolean isReceiving        = false;
    private JTextArea  currentStreamArea  = null;
    private JPanel     currentBubbleInner = null;
    private String     currentAiRawText   = "";

    // 当前会话累计 token 用量
    private int sessionPromptTokens      = 0;
    private int sessionCompletionTokens  = 0;
    private int sessionCacheHitTokens    = 0;
    private int sessionCacheMissTokens   = 0;

    public ChatPanel(Project project) {
        this.project = project;
        this.client  = new DeepSeekClient();
        this.conversationHistory = new ArrayList<>();

        setLayout(new BorderLayout());
        setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2D30)));

        conversationHistory.add(new ChatMessage("system",
                "你是一个智能编程助手 LoongC。你可以帮助用户编写代码、分析项目文件、解答编程问题。" +
                        "当用户询问项目相关内容时，你会根据提供的文件上下文给出精准回答。" +
                        "回复时请使用 Markdown 格式，代码请放在代码块中。"));

        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2D30)));
        messagesPanel.setBorder(JBUI.Borders.empty(14, 12, 6, 12));
        // 确保 BoxLayout 下子组件能正确展开：在 Java 21 中某些情况下需要显式设置对齐
        messagesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        messagesScrollPane = new JBScrollPane(messagesPanel);
        messagesScrollPane.setBorder(null);
        messagesScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messagesScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(messagesScrollPane, BorderLayout.CENTER);

        // 初始化上下文条（先隐藏，有文件时显示）
        ctxFileLabel  = new JLabel();
        ctxLineLabel  = new JLabel();
        ctxInsertBtn  = new JButton("插入");
        ctxBar        = buildContextBar();
        ctxBar.setVisible(false);

        statusLabel = new JLabel("就绪");
        inputField = new JTextArea(3, 0);
        String[] models = {"deepseek-v4-flash"};
        modelCombo = new JComboBox<>(models);
        add(buildInputPanel(), BorderLayout.SOUTH);

        // 注册编辑器监听器
        if (project != null) {
            registerFileListener();
            registerSelectionListener();
        }

        addAiMessage("你好！我是 **LoongC**，你的智能编程助手。\n\n" +
                "我支持 Markdown 渲染，代码会高亮显示。\n\n" +
                "常用命令：\n" +
                "- `/file` — 读取并分析当前项目文件\n" +
                "- `@文件名` — 引用特定文件作为上下文\n\n" +
                "在编辑器中**打开文件**或**选中代码**，上方会出现快速插入提示。");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 公开接口（供 LoongCToolWindowFactory 的 titleAction 调用）
    // ─────────────────────────────────────────────────────────────────────────

    public void clearChat() {
        SwingUtilities.invokeLater(() -> {
            messagesPanel.removeAll();
            messagesPanel.revalidate();
            messagesPanel.repaint();
            conversationHistory.clear();
            conversationHistory.add(new ChatMessage("system",
                    "你是一个智能编程助手 LoongC。你可以帮助用户编写代码、分析项目文件、解答编程问题。" +
                            "回复时请使用 Markdown 格式，代码请放在代码块中。"));
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
    private JPanel buildContextBar() {
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bg = dark ? CTX_BG_DARK : CTX_BG_LIGHT;

        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBackground(bg);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        JBColor.namedColor("Separator.separatorColor", DIVIDER_DARK)),
                JBUI.Borders.empty(5, 12, 5, 8)
        ));

        // 左侧：文件图标 + 文件名
        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftGroup.setOpaque(false);

        JLabel iconLbl = new JLabel(">");
        iconLbl.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.BOLD, 13));
        iconLbl.setForeground(CTX_ACCENT);
        leftGroup.add(iconLbl);

        ctxFileLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.BOLD, 11));
        ctxFileLabel.setForeground(CTX_ACCENT);
        leftGroup.add(ctxFileLabel);

        ctxLineLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
        ctxLineLabel.setForeground(JBColor.GRAY);
        leftGroup.add(ctxLineLabel);

        bar.add(leftGroup, BorderLayout.CENTER);

        // 右侧：插入按钮
        ctxInsertBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        ctxInsertBtn.setForeground(CTX_ACCENT);
        ctxInsertBtn.setBackground(bg);
        ctxInsertBtn.setOpaque(true);
        ctxInsertBtn.setBorderPainted(true);
        ctxInsertBtn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(CTX_ACCENT, 1, true),
                JBUI.Borders.empty(2, 8)
        ));
        ctxInsertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ctxInsertBtn.setFocusable(false);
        ctxInsertBtn.addActionListener(e -> insertContextIntoInput());
        ctxInsertBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                ctxInsertBtn.setBackground(new Color(CTX_ACCENT.getRed(),
                        CTX_ACCENT.getGreen(), CTX_ACCENT.getBlue(), 30));
            }
            @Override public void mouseExited(MouseEvent e) {
                ctxInsertBtn.setBackground(bg);
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
        SwingUtilities.invokeLater(() -> {
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
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2D30)));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                JBColor.namedColor("Separator.separatorColor", DIVIDER_DARK)));

        // 上下文条（文件+行号引用）
        panel.add(ctxBar, BorderLayout.NORTH);

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.setOpaque(false);
        inner.setBorder(JBUI.Borders.empty(10, 12, 12, 12));


        statusLabel.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
        statusLabel.setForeground(JBColor.GRAY);
        inner.add(statusLabel, BorderLayout.NORTH);


        // "Dialog" 逻辑字体获得更好的 Unicode fallback，支持 emoji 不会乱码
        inputField.setFont(new Font("Dialog", Font.PLAIN, 13));
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setBackground(JBColor.namedColor("TextField.background", INPUT_BG_DARK));
        inputField.setForeground(JBColor.namedColor("TextField.foreground", Color.WHITE));
        inputField.setCaretColor(JBColor.namedColor("TextField.caretForeground", Color.WHITE));
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
        inputScroll.setBorder(new LineBorder(
                JBColor.namedColor("Separator.separatorColor", DIVIDER_DARK), 1, true));
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JButton sendBtn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 按钮背景圆角
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                // 绘制发送图标（纸飞机风格）
                paintSendIcon(g2, getWidth(), getHeight(), isReceiving);
                g2.dispose();
            }
            @Override protected void paintBorder(Graphics g) { /* 不画系统边框 */ }
            @Override public boolean isOpaque() { return false; }
        };
//        sendBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        sendBtn.setToolTipText("发送 (Enter)");
        sendBtn.setFocusable(false);
//        sendBtn.setForeground(Color.WHITE);
        sendBtn.setBackground(BRAND_COLOR);
        sendBtn.setOpaque(true);
        sendBtn.setBorderPainted(false);
        //sendBtn.setPreferredSize(new Dimension(72, 0));
        sendBtn.setPreferredSize(new Dimension(52, 0));
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendBtn.addActionListener(e -> sendMessage());
        sendBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { sendBtn.setBackground(SEND_HOVER); }
            @Override public void mouseExited(MouseEvent e)  { sendBtn.setBackground(BRAND_COLOR); }
        });

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setOpaque(false);
        inputRow.add(inputScroll, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);
        inner.add(inputRow, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout(0, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(JBUI.Borders.emptyTop(6));


        modelCombo.setSelectedItem(LoongCSettings.getInstance().getModel());
        modelCombo.setFont(new Font(LoongCSettings.getInstance().getFontStyle(), Font.PLAIN, 11));
        modelCombo.addActionListener(e -> {
            String sel = (String) modelCombo.getSelectedItem();
            if (sel != null) LoongCSettings.getInstance().setModel(sel);
        });
        bottomBar.add(modelCombo, BorderLayout.WEST);

        // Token 用量统计区（modelCombo 右侧，hint 左侧）
        tokenStatsLabel = new JLabel("Token消耗实况");
        tokenStatsLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        tokenStatsLabel.setForeground(JBColor.GRAY);
        tokenStatsLabel.setToolTipText("当前会话累计：输入 token / 输出 token | 缓存命中 / 未命中 | 预估费用");
        tokenStatsLabel.setBorder(JBUI.Borders.emptyLeft(8));
        bottomBar.add(tokenStatsLabel, BorderLayout.CENTER);

        JLabel hint = new JLabel("Enter 发送  |  Shift+Enter 换行");
        hint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        hint.setForeground(JBColor.GRAY);
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
        menu.setBorder(new LineBorder(
                JBColor.namedColor("Separator.separatorColor", DIVIDER_DARK), 1, true));
        menu.setBackground(JBColor.namedColor("PopupMenu.background",
                new Color(0x3C3F41)));

        // 小标题（不可点击，起分组说明作用）
        JLabel header = new JLabel("  " + title);
        header.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        header.setForeground(JBColor.GRAY);
        header.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        menu.add(header);
        menu.addSeparator();

        // 复制选中内容
        JMenuItem copyItem = new JMenuItem("复制选中");
        copyItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
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
        copyAllItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
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
        selectAllItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
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
        menu.setBorder(new LineBorder(
                JBColor.namedColor("Separator.separatorColor", DIVIDER_DARK), 1, true));
        menu.setBackground(JBColor.namedColor("PopupMenu.background",
                new Color(0x3C3F41)));

        JLabel header = new JLabel("  LoongC 回复");
        header.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        header.setForeground(JBColor.GRAY);
        header.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        menu.add(header);
        menu.addSeparator();

        JMenuItem copySelItem = new JMenuItem("复制选中");
        copySelItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        copySelItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copySelItem.addActionListener(e -> {
            String sel = pane.getSelectedText();
            if (sel != null && !sel.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sel), null);
            }
        });
        menu.add(copySelItem);

        JMenuItem copyMdItem = new JMenuItem("复制原始内容（Markdown）");
        copyMdItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        copyMdItem.addActionListener(e ->
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(rawMarkdown), null));
        menu.add(copyMdItem);

        JMenuItem selectAllItem = new JMenuItem("全选");
        selectAllItem.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        selectAllItem.addActionListener(e -> pane.selectAll());
        menu.add(selectAllItem);

        pane.setComponentPopupMenu(menu);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 发送逻辑
    // ─────────────────────────────────────────────────────────────────────────

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || isReceiving) return;

        inputField.setText("");
        addUserMessage(text);

        if ("/file".equals(text) || "/files".equals(text)) {
            readProjectFilesAndRespond();
            return;
        }

        StringBuilder prompt = new StringBuilder(text);
        if (text.contains("@")) {
            String fc = extractFileReferences(text);
            if (!fc.isEmpty()) prompt.append("\n\n").append(fc);
        }
        String ec = getEditorContext();
        if (!ec.isEmpty()) prompt.append("\n\n当前编辑的文件内容：\n").append(ec);

        conversationHistory.add(new ChatMessage("user", prompt.toString()));
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
        SwingUtilities.invokeLater(() -> {
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

    private void sendToApi() {
        isReceiving = true;
        statusLabel.setText("思考中...");
        statusLabel.setForeground(new Color(0x4CAF50));

        client.streamChat(conversationHistory, new DeepSeekClient.StreamCallback() {
            @Override
            public void onMessage(String chunk) {
                SwingUtilities.invokeLater(() -> appendStreamChunk(chunk));
            }

            @Override
            public void onUsage(com.loongc.api.model.ChatResponse.Usage usage) {
                // SSE 最后一帧收到 usage，累加到会话统计并更新标签
                System.out.println("onUsage :" + usage.toString());
                SwingUtilities.invokeLater(() -> updateTokenStats(usage));
            }

            @Override
            public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    isReceiving = false;
                    statusLabel.setText("就绪");
                    statusLabel.setForeground(JBColor.GRAY);
                    if (!currentAiRawText.isEmpty()) {
                        conversationHistory.add(new ChatMessage("assistant", currentAiRawText));
                        finalizeAiMessage();
                    }
                    currentStreamArea  = null;
                    currentBubbleInner = null;
                    currentAiRawText   = "";
                });
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    isReceiving = false;
                    statusLabel.setText("出错");
                    statusLabel.setForeground(JBColor.RED);
                    appendStreamChunk("\n\n**[错误]** " + error.getMessage());
                    finalizeAiMessage();
                    currentStreamArea  = null;
                    currentBubbleInner = null;
                    currentAiRawText   = "";
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
    private void updateTokenStats(com.loongc.api.model.ChatResponse.Usage usage) {
        if (usage == null) return;

        sessionPromptTokens     += usage.getPromptTokens();
        sessionCompletionTokens += usage.getCompletionTokens();
        sessionCacheHitTokens   += usage.getPromptCacheHitTokens();
        sessionCacheMissTokens  += usage.getPromptCacheMissTokens();

        // 根据当前选择的模型确定单价（元 / token）
        String model = (String) modelCombo.getSelectedItem();
        double hitPrice, missPrice, outPrice;
        if ("deepseek-V4-Pro".equals(model)) {
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

        String text = String.format(
                "↑%d ↓%d  |  💾命中:%d(%s) 未命中:%d  |  %s",
                sessionPromptTokens,
                sessionCompletionTokens,
                sessionCacheHitTokens, hitRateStr,
                sessionCacheMissTokens,
                costStr
        );

        tokenStatsLabel.setText(text);
        tokenStatsLabel.setForeground(JBColor.GRAY);
        System.out.println(String.format(
                "<html>当前会话累计<br>"
                        + "输入 tokens：%d（缓存命中 %d + 未命中 %d）<br>"
                        + "输出 tokens：%d<br>"
                        + "缓存命中率：%s<br>"
                        + "预估费用：%s 元</html>",
                sessionPromptTokens,
                sessionCacheHitTokens, sessionCacheMissTokens,
                sessionCompletionTokens,
                hitRateStr, costStr
        ));
        tokenStatsLabel.setToolTipText(String.format(
                "<html>当前会话累计<br>"
                        + "输入 tokens：%d（缓存命中 %d + 未命中 %d）<br>"
                        + "输出 tokens：%d<br>"
                        + "缓存命中率：%s<br>"
                        + "预估费用：%s 元</html>",
                sessionPromptTokens,
                sessionCacheHitTokens, sessionCacheMissTokens,
                sessionCompletionTokens,
                hitRateStr, costStr
        ));
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
     * 流式完成：将气泡内 JTextArea 替换为 JEditorPane 渲染 Markdown，并安装右键菜单
     */
    private void finalizeAiMessage() {
        if (currentBubbleInner == null || currentAiRawText.isEmpty()) return;
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = dark ? AI_BG_DARK : AI_BG_LIGHT;
        String rawMd = currentAiRawText;  // 保留原始 MD 供右键"复制 Markdown"使用
        String html  = MarkdownUtil.toHtml(rawMd, dark, bubbleBg);

        JEditorPane mdPane = new JEditorPane("text/html", html);
        mdPane.setEditable(false);
        mdPane.setOpaque(true);
        mdPane.setBackground(bubbleBg);
        mdPane.setForeground(JBColor.namedColor("Label.foreground",
                dark ? Color.WHITE : Color.BLACK));
        mdPane.setBorder(JBUI.Borders.empty(10, 14));
        mdPane.setCaretPosition(0);
        installPopupMenuForAi(mdPane, rawMd);

        JPanel bubbleInner = currentBubbleInner;
        mdPane.addPropertyChangeListener("preferredSize", e ->
                SwingUtilities.invokeLater(() -> {
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
        SwingUtilities.invokeLater(() -> {
            messagesPanel.add(buildUserRow(content));
            messagesPanel.add(Box.createVerticalStrut(12));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        });
    }

    private void addAiMessage(String content) {
        SwingUtilities.invokeLater(() -> {
            messagesPanel.add(buildAiRow(content));
            messagesPanel.add(Box.createVerticalStrut(12));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            scrollToBottom();
        });
    }

    /** 流式 AI 消息：先用 JTextArea 占位，并安装右键菜单 */
    private void addAiStreamRow() {
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = dark ? AI_BG_DARK : AI_BG_LIGHT;

        JTextArea streamArea = new JTextArea();
        streamArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        streamArea.setLineWrap(true);
        streamArea.setWrapStyleWord(true);
        streamArea.setEditable(false);
        streamArea.setBackground(bubbleBg);
        streamArea.setForeground(JBColor.namedColor("Label.foreground",
                dark ? Color.WHITE : Color.BLACK));
        streamArea.setBorder(JBUI.Borders.empty(10, 14));
        installPopupMenu(streamArea, "LoongC 回复（接收中）");

        JPanel bubbleInner = new JPanel(new BorderLayout());
        bubbleInner.setOpaque(false);
        bubbleInner.add(streamArea, BorderLayout.CENTER);

        RoundedPanel bubble = new RoundedPanel(bubbleBg, 10);
        bubble.setLayout(new BorderLayout());
        bubble.add(bubbleInner, BorderLayout.CENTER);

        currentStreamArea  = streamArea;
        currentBubbleInner = bubbleInner;

        JPanel row = wrapAiRow("LoongC", bubble);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        messagesPanel.add(row);
        messagesPanel.add(Box.createVerticalStrut(12));
        messagesPanel.revalidate();
        messagesPanel.repaint();
        scrollToBottom();
    }

    /** 非流式 AI 消息行（欢迎语等）：直接渲染 Markdown，安装右键菜单 */
    private JPanel buildAiRow(String mdContent) {
        boolean dark = MarkdownUtil.isDarkTheme();
        Color bubbleBg = dark ? AI_BG_DARK : AI_BG_LIGHT;
        String html = MarkdownUtil.toHtml(mdContent, dark, bubbleBg);

        JEditorPane mdPane = new JEditorPane("text/html", html);
        mdPane.setEditable(false);
        mdPane.setOpaque(true);
        mdPane.setBackground(bubbleBg);
        mdPane.setForeground(JBColor.namedColor("Label.foreground",
                dark ? Color.WHITE : Color.BLACK));
        mdPane.setBorder(JBUI.Borders.empty(10, 14));
        mdPane.setCaretPosition(0);
        installPopupMenuForAi(mdPane, mdContent);

        JPanel bubbleInner = new JPanel(new BorderLayout());
        bubbleInner.setOpaque(false);
        bubbleInner.add(mdPane, BorderLayout.CENTER);

        RoundedPanel bubble = new RoundedPanel(bubbleBg, 10);
        bubble.setLayout(new BorderLayout());
        bubble.add(bubbleInner, BorderLayout.CENTER);

        JPanel row = wrapAiRow("LoongC", bubble);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    /**
     * 用户消息行：颜文字头像 + 圆角蓝色气泡靠右，带右键菜单
     */
    private JPanel buildUserRow(String content) {
        Color bubbleBg = USER_BG;

        JTextArea textArea = new JTextArea(content);
        textArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setEditable(false);
        textArea.setBackground(bubbleBg);
        textArea.setForeground(USER_FG);
        textArea.setBorder(JBUI.Borders.empty(10, 14));
        installPopupMenu(textArea, "你的消息");

        RoundedPanel bubble = new RoundedPanel(bubbleBg, 12);
        bubble.setLayout(new BorderLayout());
        bubble.add(textArea, BorderLayout.CENTER);

        JLabel nameLabel = new JLabel("你");
        nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        nameLabel.setForeground(JBColor.GRAY);
        nameLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        nameLabel.setBorder(JBUI.Borders.emptyBottom(2));

        JPanel contentArea = new JPanel();
        contentArea.setLayout(new BoxLayout(contentArea, BoxLayout.Y_AXIS));
        contentArea.setOpaque(false);
        nameLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bubble.setAlignmentX(Component.RIGHT_ALIGNMENT);
        contentArea.add(nameLabel);
        contentArea.add(bubble);

        // 颜文字头像
        JPanel avatar = buildKaomojiAvatar();

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);

        JPanel leftSpacer = new JPanel();
        leftSpacer.setOpaque(false);
        row.add(leftSpacer, BorderLayout.CENTER);

        JPanel rightGroup = new JPanel(new BorderLayout(8, 0));
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
    private JPanel wrapAiRow(String name, JPanel bubble) {
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        nameLabel.setForeground(JBColor.GRAY);
        nameLabel.setBorder(JBUI.Borders.emptyBottom(2));

        JPanel contentArea = new JPanel();
        contentArea.setLayout(new BoxLayout(contentArea, BoxLayout.Y_AXIS));
        contentArea.setOpaque(false);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentArea.add(nameLabel);
        contentArea.add(bubble);

        JPanel avatar = buildLetterAvatar("L", AI_AVATAR_BG);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JPanel avatarWrapper = new JPanel(new BorderLayout());
        avatarWrapper.setOpaque(false);
        avatarWrapper.add(avatar, BorderLayout.NORTH);
        row.add(avatarWrapper, BorderLayout.WEST);
        row.add(contentArea, BorderLayout.CENTER);

        JPanel rightSpacer = new JPanel();
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
        SwingUtilities.invokeLater(() -> {
            JScrollBar v = messagesScrollPane.getVerticalScrollBar();
            v.setValue(v.getMaximum());
        });
    }

    /**
     * 在发送按钮中心绘制"向上发送"图标（纸飞机风格）。
     * receiving=true 时绘制停止方块，提示可点击中止（视觉反馈）。
     */
    private void paintSendIcon(Graphics2D g2, int w, int h, boolean receiving) {
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (receiving) {
            // 正在接收时：绘制圆角停止方块
            int s = Math.min(w, h) / 3;
            int x = (w - s) / 2;
            int y = (h - s) / 2;
            g2.fillRoundRect(x, y, s, s, 3, 3);
        } else {
            // 纸飞机图标：主三角形 + 折叠尾翼线
            int cx = w / 2;
            int cy = h / 2;
            int r  = Math.min(w, h) / 2 - 6;  // 图标半径

            // 主体：向右上的三角（机身）
            Path2D.Float plane = new Path2D.Float();
            plane.moveTo(cx - r,       cy + r * 0.5f);   // 尾部左下
            plane.lineTo(cx + r,       cy);               // 机头（右侧中心）
            plane.lineTo(cx - r,       cy - r * 0.5f);   // 尾部左上
            plane.lineTo(cx - r * 0.3f, cy);              // 折叠中心点
            plane.closePath();
            g2.fill(plane);

            // 尾翼折叠线（从折叠点到尾部右下角，使图标更立体）
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(
                    (int)(cx - r * 0.3f), cy,
                    (int)(cx - r),        (int)(cy + r * 0.5f)
            );
        }
    }
}
