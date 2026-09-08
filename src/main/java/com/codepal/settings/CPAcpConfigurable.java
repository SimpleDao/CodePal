package com.codepal.settings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.codepal.tools.AcpAgentTool;
import groovyjarjarantlr4.v4.runtime.misc.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CPAcpConfigurable implements Configurable  {

    private List<AcpAgentTool> allAgents = new ArrayList<>();
    private List<AcpAgentTool> displayedAgents = new ArrayList<>();

    private JBPanel<?> agentListPanel;
    private JBTextField searchField;
    private JBCheckBox passCustomMcpCb;
    private JBCheckBox passIntelliJMcpCb;

    public CPAcpConfigurable() {
        initMockData();
    }

    private void initMockData() {
        allAgents.clear();
        displayedAgents.clear();
        allAgents.add(new AcpAgentTool("CodePal Agent", "v0.1.0",
                "CodePal", "水龙吟", true, true, "codepal"));
        allAgents.add(new AcpAgentTool("Claude Code", "v0.42.0",
                "ACP wrapper for Anthropic's Claude", "Anthropic", false, false, "claude"));
        displayedAgents.addAll(allAgents);
    }

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "ACP";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JBPanel<?> root = new JBPanel<>(new BorderLayout(0, 8));
        root.setBorder(JBUI.Borders.empty(0, 0, 8, 0));

        root.add(buildSearchPanel(), BorderLayout.NORTH);
        root.add(buildAgentListPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        return root;
    }

    private JBPanel<?> buildSearchPanel() {
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(8, 12, 4, 12));

        searchField = new JBTextField();
        searchField.setToolTipText("搜索 agents...");
        searchField.putClientProperty("JTextField.Search.Gap", JBUI.scale(6));
        searchField.putClientProperty("JTextField.Search.Icon", AllIcons.Actions.Search);

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filterAgents(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filterAgents(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filterAgents(); }
        });

        panel.add(searchField, BorderLayout.CENTER);
        return panel;
    }

    private JBScrollPane buildAgentListPanel() {
        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout());
        wrapper.setOpaque(false);

        JBLabel featuredLabel = new JBLabel("Available Agents");
        featuredLabel.setFont(JBUI.Fonts.label(11).deriveFont(Font.BOLD));
        featuredLabel.setForeground(JBColor.namedColor("Label.disabledForeground",
                new JBColor(new Color(0x888888), new Color(0x888888))));
        featuredLabel.setBorder(JBUI.Borders.empty(4, 16, 4, 12));
        wrapper.add(featuredLabel, BorderLayout.NORTH);

        agentListPanel = new JBPanel<>(null);
        agentListPanel.setLayout(new BoxLayout(agentListPanel, BoxLayout.Y_AXIS));
        agentListPanel.setOpaque(false);
        refreshAgentList();

        JBPanel<?> listWrapper = new JBPanel<>(new BorderLayout());
        listWrapper.setOpaque(false);
        listWrapper.add(agentListPanel, BorderLayout.NORTH);

        wrapper.add(listWrapper, BorderLayout.CENTER);

        JBScrollPane scrollPane = new JBScrollPane(wrapper);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        return scrollPane;
    }

    private JBPanel<?> buildBottomPanel() {
        JBPanel<?> panel = new JBPanel<>(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(8, 16, 0, 12));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(2, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;

        JSeparator sep = new JSeparator();
        panel.add(sep, gbc);
        gbc.gridy++;
        gbc.insets = JBUI.insets(8, 0, 2, 0);

        passCustomMcpCb = new JBCheckBox("Pass custom MCP servers", true);
        passCustomMcpCb.setFont(JBUI.Fonts.label(12));
        panel.add(passCustomMcpCb, gbc);

        gbc.gridy++;
        gbc.insets = JBUI.insets(2, 0, 0, 0);
        passIntelliJMcpCb = new JBCheckBox("Pass IntelliJ MCP server", false);
        passIntelliJMcpCb.setFont(JBUI.Fonts.label(12));
        panel.add(passIntelliJMcpCb, gbc);

        return panel;
    }

    private void refreshAgentList() {
        agentListPanel.removeAll();
        for (int i = 0; i < displayedAgents.size(); i++) {
            agentListPanel.add(buildAgentRow(displayedAgents.get(i)));
        }
        agentListPanel.add(Box.createVerticalGlue());
        agentListPanel.revalidate();
        agentListPanel.repaint();
    }

    private JBPanel<?> buildAgentRow(AcpAgentTool agent) {
        JBPanel<?> row = new JBPanel<>(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(8, 16, 8, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(56)));
        row.setPreferredSize(new Dimension(0, JBUI.scale(56)));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JBPanel<?> contentRow = new JBPanel<>(new BorderLayout(12, 0));
        contentRow.setOpaque(false);

        JBLabel iconLabel = new JBLabel(createAgentIcon(agent));
        iconLabel.setPreferredSize(new Dimension(JBUI.scale(40), JBUI.scale(40)));
        iconLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        contentRow.add(iconLabel, BorderLayout.WEST);

        JBPanel<?> infoPanel = new JBPanel<>();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);
        infoPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        JBLabel nameLabel = new JBLabel(agent.getName());
        nameLabel.setFont(JBUI.Fonts.label(13).deriveFont(Font.BOLD));
        nameLabel.setForeground(JBColor.namedColor("Label.foreground",
                new JBColor(new Color(0x000000), new Color(0xBBBBBB))));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoPanel.add(nameLabel);
        infoPanel.add(Box.createVerticalStrut(2));

        JBPanel<?> metaRow = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 6, 0));
        metaRow.setOpaque(false);
        metaRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JBLabel versionLabel = new JBLabel(agent.getVersion());
        versionLabel.setFont(JBUI.Fonts.label(11));
        versionLabel.setForeground(JBColor.namedColor("Label.disabledForeground", JBColor.GRAY));
        metaRow.add(versionLabel);

        JBLabel dot1 = new JBLabel("·");
        dot1.setFont(JBUI.Fonts.label(11));
        dot1.setForeground(JBColor.namedColor("Label.disabledForeground", JBColor.GRAY));
        metaRow.add(dot1);

        JBLabel authorLabel = new JBLabel(agent.getAuthor());
        authorLabel.setFont(JBUI.Fonts.label(11));
        authorLabel.setForeground(JBColor.namedColor("Label.disabledForeground", JBColor.GRAY));
        metaRow.add(authorLabel);

        if (agent.isBundled()) {
            JBLabel dot2 = new JBLabel("·");
            dot2.setFont(JBUI.Fonts.label(11));
            dot2.setForeground(JBColor.namedColor("Label.disabledForeground", JBColor.GRAY));
            metaRow.add(dot2);

            JBLabel bundledLabel = new JBLabel("Bundled");
            bundledLabel.setFont(JBUI.Fonts.label(11));
            bundledLabel.setForeground(JBColor.namedColor("Link.activeForeground",
                    new JBColor(new Color(0x4B8EF0), new Color(0x58A6FF))));
            metaRow.add(bundledLabel);
        }

        infoPanel.add(metaRow);

        contentRow.add(infoPanel, BorderLayout.CENTER);

        JButton actionBtn = createActionButton(agent);
        JBPanel<?> btnPanel = new JBPanel<>(new BorderLayout());
        btnPanel.setOpaque(false);
        btnPanel.add(actionBtn, BorderLayout.CENTER);
        contentRow.add(btnPanel, BorderLayout.EAST);

        row.add(contentRow, BorderLayout.CENTER);

        final boolean[] hover = {false};
        row.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                hover[0] = true;
                row.setOpaque(true);
                row.setBackground(JBColor.namedColor("ActionButton.hoverBackground",
                        new JBColor(new Color(0xF0F2F5), new Color(0x323232))));
            }
            @Override public void mouseExited(MouseEvent e) {
                hover[0] = false;
                row.setOpaque(false);
                row.setBackground(null);
            }
        });

        return row;
    }

    private JButton createActionButton(AcpAgentTool agent) {
        final String text = agent.isInstalled() ? "Uninstall" : "Install";
        final JButton btn = new JButton(text);
        btn.setFocusable(false);
        btn.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        btn.setPreferredSize(new Dimension(JBUI.scale(82), JBUI.scale(28)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        final JBColor greenColor = JBColor.namedColor("Button.default.startBackground",
                new JBColor(new Color(0x10B981), new Color(0x34D399)));
        final JBColor redColor = JBColor.namedColor("Label.errorForeground",
                new JBColor(new Color(0xE53935), new Color(0xEF5350)));

        if (agent.isInstalled()) {
            btn.setForeground(redColor);
            btn.setBorder(JBUI.Borders.customLine(redColor, 1));
        } else {
            btn.setForeground(greenColor);
            btn.setBorder(JBUI.Borders.customLine(greenColor, 1));
        }
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);

        btn.addActionListener(e -> {
            agent.setInstalled(!agent.isInstalled());
            refreshAgentList();
        });

        return btn;
    }

    private Icon createAgentIcon(AcpAgentTool agent) {
        final int size = JBUI.scale(40);
        final int arc = JBUI.scale(8);

        Color bgColor;
        String iconType = agent.getIconType();
        switch (iconType) {
            case "claude":
                bgColor = JBColor.namedColor("PluginIcon.claude",
                        new JBColor(new Color(0x7C3AED), new Color(0x7C3AED)));
                break;
            case "codepal":
                bgColor = JBColor.namedColor("PluginIcon.codepal",
                        new JBColor(new Color(0x2563EB), new Color(0x2563EB)));
                break;
            default:
                bgColor = JBColor.namedColor("PluginIcon.default",
                        new JBColor(new Color(0x6B7280), new Color(0x6B7280)));
                break;
        }

        final Color finalBgColor = bgColor;
        final String labelText = agent.getName().substring(0, Math.min(2, agent.getName().length())).toUpperCase();

        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    RoundRectangle2D rect = new RoundRectangle2D.Float(x, y, size, size, arc, arc);
                    g2.setColor(finalBgColor);
                    g2.fill(rect);

                    g2.setFont(JBUI.Fonts.label(14).deriveFont(Font.BOLD));
                    g2.setColor(Color.WHITE);
                    FontMetrics fm = g2.getFontMetrics();
                    int textWidth = fm.stringWidth(labelText);
                    int textHeight = fm.getAscent();
                    g2.drawString(labelText,
                            x + (size - textWidth) / 2,
                            y + (size + textHeight) / 2 - fm.getDescent());
                } finally {
                    g2.dispose();
                }
            }

            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    private void filterAgents() {
        String query = searchField.getText().trim().toLowerCase();
        if (query.isEmpty()) {
            displayedAgents = new ArrayList<>(allAgents);
        } else {
            displayedAgents = allAgents.stream()
                    .filter(a -> a.getName().toLowerCase().contains(query)
                            || a.getDescription().toLowerCase().contains(query)
                            || a.getAuthor().toLowerCase().contains(query))
                    .collect(Collectors.toList());
        }
        refreshAgentList();
    }

    private List<String> getCurrentInstalledNames() {
        List<String> installed = new ArrayList<>();
        for (AcpAgentTool agent : allAgents) {
            if (agent.isInstalled()) installed.add(agent.getName());
        }
        return installed;
    }

    private void syncFromSettings() {
        List<String> installed = CPSettings.getInstance().getInstalledAgentNames();
        if (installed.isEmpty()) {
            return;
        }
        for (AcpAgentTool agent : allAgents) {
            agent.setInstalled(installed.contains(agent.getName()));
        }
    }

    @Override
    public boolean isModified() {
        List<String> currentInstalled = getCurrentInstalledNames();
        List<String> savedInstalled = CPSettings.getInstance().getInstalledAgentNames();
        if (currentInstalled.size() != savedInstalled.size()) return true;
        for (int i = 0; i < currentInstalled.size(); i++) {
            if (!currentInstalled.get(i).equals(savedInstalled.get(i))) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        List<String> installed = new ArrayList<>();
        for (AcpAgentTool agent : allAgents) {
            if (agent.isInstalled()) {
                installed.add(agent.getName());
            }
        }
        CPSettings.getInstance().setInstalledAgentNames(installed);
    }

    @Override
    public void reset() {
        syncFromSettings();
        if (searchField != null) {
            searchField.setText("");
            filterAgents();
        }
        if (passCustomMcpCb != null) passCustomMcpCb.setSelected(true);
        if (passIntelliJMcpCb != null) passIntelliJMcpCb.setSelected(false);
    }
}
