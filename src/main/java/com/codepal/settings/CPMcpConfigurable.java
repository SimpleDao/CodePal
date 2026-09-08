package com.codepal.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.codepal.mcp.McpService;
import com.codepal.mcp.config.McpServerConfig;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CP → MCP 配置面板
 *
 * <p>对标 auto-dev：使用 JSON 编辑器直接编辑 .mcp.json 配置，
 * 实时校验、自动保存，而非分字段表单。
 *
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "brave-search": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-brave-search"],
 *       "env": { "BRAVE_API_KEY": "your-key" }
 *     }
 *   }
 * }
 * }</pre>
 */
public class CPMcpConfigurable implements Configurable {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON = new Gson();

    private JBPanel<?> mainPanel;
    private JTextArea jsonEditor;
    private JLabel statusLabel;
    private JLabel validationLabel;
    private String originalJson;

    // ── 模板 ──

    private static final String TEMPLATE_BRAVE = """
            {
              "brave-search": {
                "command": "npx",
                "args": ["-y", "@modelcontextprotocol/server-brave-search"],
                "env": { "BRAVE_API_KEY": "your-api-key" }
              }
            }""";

    private static final String TEMPLATE_TAVILY = """
            {
              "tavily-search": {
                "command": "npx",
                "args": ["-y", "tavily-mcp"],
                "env": { "TAVILY_API_KEY": "your-api-key" }
              }
            }""";

    private static final String TEMPLATE_TAVILY_REMOTE = """
            {
              "tavily-remote": {
                "command": "npx",
                "args": ["-y", "mcp-remote", "https://mcp.tavily.com/mcp/?tavilyApiKey=your-api-key"],
                "env": {}
              }
            }""";

    private static final String MERGE_TIP = "\n// ⚠ 如果已有其他 server，请手动合并 JSON，避免覆盖\n";

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "MCP";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JBPanel<>(new BorderLayout(0, 8));
        mainPanel.setBorder(JBUI.Borders.empty(10, 10));

        // ── 顶部说明 + 模板按钮 ──
        JBPanel<?> topPanel = new JBPanel<>(new BorderLayout(5, 5));
        JLabel title = new JBLabel("<html><b style='font-size:14px'>MCP (Model Context Protocol)</b></html>");
        topPanel.add(title, BorderLayout.NORTH);

        JLabel desc = new JBLabel("<html><body style='width:520px'>" +
                "直接编辑 JSON 配置，格式参考 <code>.mcp.json</code>。命令支持本地进程和远程连接：" +
                "<br><code>npx -y mcp-remote https://mcp.tavily.com/mcp/?tavilyApiKey=xxx</code>" +
                "</body></html>");
        desc.setFont(JBUI.Fonts.label(11));
        topPanel.add(desc, BorderLayout.CENTER);

        JBPanel<?> templatePanel = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 4, 0));
        templatePanel.add(new JBLabel("快速插入:"));
        JButton btnBrave = new JButton("Brave Search");
        btnBrave.setFont(JBUI.Fonts.label(11));
        btnBrave.addActionListener(e -> insertTemplate(TEMPLATE_BRAVE));
        templatePanel.add(btnBrave);
        JButton btnTavily = new JButton("Tavily (本地)");
        btnTavily.setFont(JBUI.Fonts.label(11));
        btnTavily.addActionListener(e -> insertTemplate(TEMPLATE_TAVILY));
        templatePanel.add(btnTavily);
        JButton btnTavilyRemote = new JButton("Tavily (远程)");
        btnTavilyRemote.setFont(JBUI.Fonts.label(11));
        btnTavilyRemote.addActionListener(e -> insertTemplate(TEMPLATE_TAVILY_REMOTE));
        templatePanel.add(btnTavilyRemote);
        topPanel.add(templatePanel, BorderLayout.SOUTH);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // ── JSON 编辑器 ──
        jsonEditor = new JBTextArea();
        jsonEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        jsonEditor.setTabSize(2);
        jsonEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validateJson(); }
            @Override public void removeUpdate(DocumentEvent e) { validateJson(); }
            @Override public void changedUpdate(DocumentEvent e) { validateJson(); }
        });
        JBScrollPane scrollPane = new JBScrollPane(jsonEditor);
        scrollPane.setPreferredSize(new Dimension(600, 350));
        scrollPane.setBorder(JBUI.Borders.customLine(Color.GRAY, 1));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // ── 底部状态栏 ──
        JBPanel<?> bottomPanel = new JBPanel<>(new BorderLayout());
        validationLabel = new JBLabel(" ");
        validationLabel.setFont(JBUI.Fonts.label(11));
        bottomPanel.add(validationLabel, BorderLayout.WEST);
        statusLabel = new JBLabel("");
        statusLabel.setFont(JBUI.Fonts.label(11));
        bottomPanel.add(statusLabel, BorderLayout.EAST);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        return mainPanel;
    }

    private void insertTemplate(String template) {
        String current = jsonEditor.getText().trim();
        if (current.isEmpty()) {
            jsonEditor.setText("{\n  \"mcpServers\": " + template + "\n}");
        } else {
            jsonEditor.append("\n" + MERGE_TIP + "// 要添加的新 server：\n// " +
                    template.replace("\n", "\n// "));
        }
    }

    private void validateJson() {
        String text = jsonEditor.getText().trim();
        if (text.isEmpty()) {
            validationLabel.setForeground(Color.GRAY);
            validationLabel.setText("JSON 为空");
            return;
        }
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) throw new RuntimeException("Root must be an object");
            JsonObject root = el.getAsJsonObject();

            // 支持 {"mcpServers": {...}} 或直接 {...} 格式
            JsonObject servers;
            if (root.has("mcpServers") && root.get("mcpServers").isJsonObject()) {
                servers = root.getAsJsonObject("mcpServers");
            } else {
                servers = root;
            }

            int count = 0;
            List<String> names = new ArrayList<>();
            for (String key : servers.keySet()) {
                JsonElement v = servers.get(key);
                if (v.isJsonObject()) {
                    JsonObject srv = v.getAsJsonObject();
                    if (srv.has("command") || srv.has("url")) {
                        count++;
                        names.add(key);
                    }
                }
            }
            validationLabel.setForeground(new Color(0, 128, 0));
            validationLabel.setText("✅ 有效 JSON — " + count + " 个 MCP Server: " + String.join(", ", names));
        } catch (Exception e) {
            validationLabel.setForeground(new Color(200, 0, 0));
            validationLabel.setText("❌ JSON 格式错误: " + e.getMessage());
        }
    }

    @Override
    public boolean isModified() {
        return !jsonEditor.getText().trim().equals(
                originalJson != null ? originalJson.trim() : "");
    }

    @Override
    public void apply() {
        String text = jsonEditor.getText().trim();
        if (text.isEmpty()) {
            CPSettings.getInstance().setMcpServerJsons(new ArrayList<>());
            originalJson = "";
            return;
        }
        try {
            JsonElement el = JsonParser.parseString(text);
            JsonObject root = el.getAsJsonObject();

            JsonObject servers;
            if (root.has("mcpServers") && root.get("mcpServers").isJsonObject()) {
                servers = root.getAsJsonObject("mcpServers");
            } else {
                servers = root;
            }

            List<String> jsons = new ArrayList<>();
            for (String key : servers.keySet()) {
                JsonElement v = servers.get(key);
                if (v.isJsonObject()) {
                    JsonObject srv = v.getAsJsonObject();
                    if (!srv.has("name")) srv.addProperty("name", key);
                    jsons.add(PRETTY_GSON.toJson(srv));
                }
            }

            // 保存 JSON 字符串列表
            CPSettings.getInstance().setMcpServerJsons(jsons);
            originalJson = text;

            // 自动启用所有配置的 Server（异步）
            SwingUtilities.invokeLater(() -> {
                McpService.shutdown();
                McpService.startAllEnabled();
            });

            int count = jsons.size();
            statusLabel.setText("已保存 " + count + " 个 MCP Server，正在启动...");
        } catch (Exception e) {
            Messages.showErrorDialog(mainPanel,
                    "JSON 解析失败: " + e.getMessage(), "保存失败");
        }
    }

    @Override
    public void reset() {
        List<String> jsons = CPSettings.getInstance().getMcpServerJsons();
        if (jsons.isEmpty()) {
            originalJson = "";
            jsonEditor.setText("");
        } else {
            // 重建完整的 mcpServers JSON
            StringBuilder sb = new StringBuilder("{\n  \"mcpServers\": {\n");
            for (int i = 0; i < jsons.size(); i++) {
                try {
                    JsonObject srv = JsonParser.parseString(jsons.get(i)).getAsJsonObject();
                    String name = srv.has("name") ? srv.get("name").getAsString() : "server-" + i;
                    sb.append("    \"").append(name).append("\": ");
                    sb.append(PRETTY_GSON.toJson(srv).replace("\n", "\n    "));
                    if (i < jsons.size() - 1) sb.append(",");
                    sb.append("\n");
                } catch (Exception ignored) {}
            }
            sb.append("  }\n}");
            originalJson = sb.toString();
            jsonEditor.setText(originalJson);
        }
        jsonEditor.setCaretPosition(0);
        validateJson();
        statusLabel.setText("");
    }
}
