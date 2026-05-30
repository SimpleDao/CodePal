package com.loongc.toolwindow;

import com.loongc.api.DeepSeekClient;
import com.loongc.model.ChatMessage;
import com.loongc.settings.CodeBuddySettings;
import com.loongc.utils.FileReaderUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeBuddy 聊天面板 - 侧边栏主界面
 */
public class ChatPanel extends JPanel {

    private final Project project;
    private final DeepSeekClient client;
    private final List<ChatMessage> conversationHistory;
    private final JTextPane chatArea;
    private final JTextField inputField;
    private final JComboBox<String> modelCombo;
    private final JLabel statusLabel;
    private final StyledDocument chatDocument;

    private boolean isReceiving = false;
    private int assistantMessageStart = -1;

    public ChatPanel(Project project) {
        this.project = project;
        this.client = new DeepSeekClient();
        this.conversationHistory = new ArrayList<>();

        setLayout(new BorderLayout());
        setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2B2B)));

        // 初始化系统消息
        conversationHistory.add(new ChatMessage("system",
            "你是一个智能编程助手 CodeBuddy。你可以帮助用户编写代码、分析项目文件、解答编程问题。" +
            "当用户询问项目相关内容时，你会根据提供的文件上下文给出精准回答。"));

        // 顶部标题栏
        add(createHeaderPanel(), BorderLayout.NORTH);

        // 聊天区域
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2B2B)));
        chatArea.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        chatDocument = chatArea.getStyledDocument();
        setupStyles();

        JBScrollPane scrollPane = new JBScrollPane(chatArea);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);

        // 底部输入区域
        add(createInputPanel(), BorderLayout.SOUTH);

        // 欢迎消息
        appendMessage("assistant", "👋 你好！我是 CodeBuddy，你的智能编程助手。\n\n" +
            "我可以帮你：\n" +
            "• 💡 自动补全和生成代码\n" +
            "• 📁 分析项目文件内容\n" +
            "• ❓ 解答编程问题\n\n" +
            "在输入框中输入问题，或输入 `/file` 让我读取当前项目文件。");
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2B2B)));
        header.setBorder(JBUI.Borders.empty(8, 12));

        JLabel titleLabel = new JLabel("CodeBuddy");
        titleLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 14));
        titleLabel.setForeground(JBColor.namedColor("Label.foreground", Color.WHITE));
        header.add(titleLabel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightPanel.setOpaque(false);

        // 设置按钮
        JButton settingsBtn = new JButton("⚙");
        settingsBtn.setToolTipText("打开设置");
        settingsBtn.setFocusable(false);
        settingsBtn.setBorderPainted(false);
        settingsBtn.setContentAreaFilled(false);
        settingsBtn.addActionListener(e -> openSettings());
        rightPanel.add(settingsBtn);

        // 清空按钮
        JButton clearBtn = new JButton("🗑");
        clearBtn.setToolTipText("清空对话");
        clearBtn.setFocusable(false);
        clearBtn.setBorderPainted(false);
        clearBtn.setContentAreaFilled(false);
        clearBtn.addActionListener(e -> clearChat());
        rightPanel.add(clearBtn);

        header.add(rightPanel, BorderLayout.EAST);
        return header;
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(JBColor.namedColor("Panel.background", new Color(0x2B2B2B)));
        panel.setBorder(JBUI.Borders.empty(8, 12, 12, 12));

        // 状态栏
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
        statusLabel.setForeground(JBColor.GRAY);
        panel.add(statusLabel, BorderLayout.NORTH);

        // 输入框和发送按钮
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setOpaque(false);

        inputField = new JTextField();
        inputField.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        inputField.setBackground(JBColor.namedColor("TextField.background", new Color(0x3C3F41)));
        inputField.setForeground(JBColor.namedColor("TextField.foreground", Color.WHITE));
        inputField.setCaretColor(JBColor.namedColor("TextField.caretForeground", Color.WHITE));
        inputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.namedColor("Component.borderColor", new Color(0x5A5A5A))),
            new EmptyBorder(8, 10, 8, 10)
        ));
        inputField.setToolTipText("输入问题，按 Enter 发送；输入 /file 读取项目文件");
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });
        inputRow.add(inputField, BorderLayout.CENTER);

        // 发送按钮
        JButton sendBtn = new JButton("➤");
        sendBtn.setFont(new Font("JetBrains Mono", Font.BOLD, 14));
        sendBtn.setFocusable(false);
        sendBtn.setBackground(JBColor.namedColor("Button.default.background", new Color(0x4B6EAF)));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.addActionListener(e -> sendMessage());
        inputRow.add(sendBtn, BorderLayout.EAST);

        panel.add(inputRow, BorderLayout.CENTER);

        // 底部工具栏
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.setBorder(JBUI.Borders.empty(4, 0, 0, 0));

        // 模型选择
        String[] models = {"deepseek-chat", "deepseek-reasoner", "deepseek-coder"};
        modelCombo = new JComboBox<>(models);
        modelCombo.setSelectedItem(CodeBuddySettings.getInstance().getModel());
        modelCombo.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
        modelCombo.addActionListener(e -> {
            String selected = (String) modelCombo.getSelectedItem();
            if (selected != null) {
                CodeBuddySettings.getInstance().setModel(selected);
            }
        });
        bottomBar.add(modelCombo, BorderLayout.WEST);

        // 快捷提示
        JLabel hintLabel = new JLabel("提示: /file 读取项目  |  @文件名 引用");
        hintLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 10));
        hintLabel.setForeground(JBColor.GRAY);
        hintLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        bottomBar.add(hintLabel, BorderLayout.EAST);

        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void setupStyles() {
        Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);

        Style userStyle = chatDocument.addStyle("user", def);
        StyleConstants.setForeground(userStyle, JBColor.namedColor("Label.foreground", new Color(0xBBBBBB)));
        StyleConstants.setBold(userStyle, true);
        StyleConstants.setFontSize(userStyle, 13);

        Style assistantStyle = chatDocument.addStyle("assistant", def);
        StyleConstants.setForeground(assistantStyle, JBColor.namedColor("Label.foreground", new Color(0xBBBBBB)));
        StyleConstants.setFontSize(assistantStyle, 13);

        Style codeStyle = chatDocument.addStyle("code", def);
        StyleConstants.setForeground(codeStyle, new Color(0xCC7832));
        StyleConstants.setFontFamily(codeStyle, "JetBrains Mono");
        StyleConstants.setFontSize(codeStyle, 12);
        StyleConstants.setBackground(codeStyle, new Color(0x2B2B2B));

        Style thinkingStyle = chatDocument.addStyle("thinking", def);
        StyleConstants.setForeground(thinkingStyle, JBColor.GRAY);
        StyleConstants.setItalic(thinkingStyle, true);
        StyleConstants.setFontSize(thinkingStyle, 12);
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || isReceiving) {
            return;
        }

        inputField.setText("");

        // 处理特殊命令
        if ("/file".equals(text) || "/files".equals(text)) {
            appendMessage("user", text);
            readProjectFilesAndRespond();
            return;
        }

        appendMessage("user", text);

        // 检查是否需要读取文件上下文
        String fileContext = "";
        if (text.contains("@")) {
            fileContext = extractFileReferences(text);
        }

        // 获取当前编辑器上下文
        String editorContext = getEditorContext();

        StringBuilder fullPrompt = new StringBuilder(text);
        if (!fileContext.isEmpty()) {
            fullPrompt.append("\n\n").append(fileContext);
        }
        if (!editorContext.isEmpty()) {
            fullPrompt.append("\n\n当前编辑的文件内容：\n").append(editorContext);
        }

        conversationHistory.add(new ChatMessage("user", fullPrompt.toString()));
        sendToApi();
    }

    private String extractFileReferences(String text) {
        StringBuilder context = new StringBuilder();
        Pattern pattern = Pattern.compile("@([\\w.\\-\\/]+)");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String fileName = matcher.group(1);
            String content = FileReaderUtil.findAndReadFile(fileName);
            if (content != null) {
                context.append("--- 文件: ").append(fileName).append(" ---\n");
                context.append(content).append("\n\n");
            }
        }
        return context.toString();
    }

    private String getEditorContext() {
        if (project == null) return "";
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";

        String text = editor.getDocument().getText();
        if (text.length() > 5000) {
            text = text.substring(0, 5000) + "\n... (文件过长，已截断)";
        }
        return text;
    }

    private void readProjectFilesAndRespond() {
        if (project == null) {
            appendMessage("assistant", "错误：未找到打开的项目。");
            return;
        }

        statusLabel.setText("正在读取项目文件...");
        statusLabel.setForeground(JBColor.ORANGE);

        SwingUtilities.invokeLater(() -> {
            List<FileReaderUtil.FileContent> files = FileReaderUtil.readProjectFiles(project);
            if (files.isEmpty()) {
                appendMessage("assistant", "未在项目中找到代码文件。");
                statusLabel.setText("就绪");
                statusLabel.setForeground(JBColor.GRAY);
                return;
            }

            String fileContext = FileReaderUtil.buildFileContext(files);
            String prompt = "请分析以下项目文件，总结项目结构、主要功能和潜在问题：\n\n" + fileContext;

            conversationHistory.add(new ChatMessage("user", prompt));
            appendMessage("assistant", "🤔 正在分析 " + files.size() + " 个文件...\n\n");
            sendToApi();
        });
    }

    private void sendToApi() {
        isReceiving = true;
        statusLabel.setText("CodeBuddy 思考中...");
        statusLabel.setForeground(JBColor.GREEN);
        assistantMessageStart = chatDocument.getLength();

        client.streamChat(conversationHistory, new DeepSeekClient.StreamCallback() {
            @Override
            public void onMessage(String content) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        chatDocument.insertString(chatDocument.getLength(), content, chatDocument.getStyle("assistant"));
                        chatArea.setCaretPosition(chatDocument.getLength());
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }

            @Override
            public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    isReceiving = false;
                    statusLabel.setText("就绪");
                    statusLabel.setForeground(JBColor.GRAY);
                    try {
                        String fullResponse = chatDocument.getText(assistantMessageStart,
                            chatDocument.getLength() - assistantMessageStart);
                        conversationHistory.add(new ChatMessage("assistant", fullResponse));
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    isReceiving = false;
                    statusLabel.setText("错误");
                    statusLabel.setForeground(JBColor.RED);
                    appendMessage("assistant", "\n\n❌ 错误: " + error.getMessage());
                });
            }
        });
    }

    private void appendMessage(String role, String content) {
        SwingUtilities.invokeLater(() -> {
            try {
                String prefix = "user".equals(role) ? "\n👤 你\n" : "\n🤖 CodeBuddy\n";
                Style style = chatDocument.getStyle(role);
                chatDocument.insertString(chatDocument.getLength(), prefix, chatDocument.getStyle("user"));
                chatDocument.insertString(chatDocument.getLength(), content + "\n", style);
                chatArea.setCaretPosition(chatDocument.getLength());
            } catch (Exception e) {
                // ignore
            }
        });
    }

    private void clearChat() {
        try {
            chatDocument.remove(0, chatDocument.getLength());
            conversationHistory.clear();
            conversationHistory.add(new ChatMessage("system",
                "你是一个智能编程助手 CodeBuddy。你可以帮助用户编写代码、分析项目文件、解答编程问题。"));
            appendMessage("assistant", "对话已清空。有什么我可以帮你的吗？");
        } catch (Exception e) {
            // ignore
        }
    }

    private void openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "CodeBuddy");
    }
}
