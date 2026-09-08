package com.codepal.ui;

import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.components.JBTextField;
import com.codepal.db.DataSourceDao;
import com.codepal.db.DataSourceDao.DatabaseConnectionInfo;
import com.codepal.model.DatabaseComboItem;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据源新增/编辑对话框 —— 复用 AddModelDialog 的圆角窗口范式（undecorated + setShape 真圆角、
 * 自绘×、IDEA 蓝实心保存按钮、透明输入框 + focus 蓝边）。
 * 字段：名称 / 类型(MySQL, SQLite) / 主机 / 端口 / 库名 / 用户名 / 密码。密码明文保存。
 */
public class AddDataSourceDialog extends JDialog {

    private boolean saved = false;
    private DatabaseComboItem editingItem = null;

    private JBTextField nameField;
    private JBTextField hostField;
    private JBTextField portField;
    private JBTextField dbField;
    private JBTextField userField;
    private JPasswordField passwordField;
    /** 库名/数据库文件地址行的标签：SQLite 时切到「数据库文件地址」 */
    private JLabel dbLabel;
/** 整个表单容器（GridBagLayout）：SQLite 时整行 rebuild 以正确回收隐藏行的空间 */
private JPanel formContainer;

    private String selectedType = "mysql";
    private JPanel segControl;
    private JRadioButton mysqlRadio;
    private JRadioButton sqliteRadio;

    /** 「测试连接」按钮：异步验证当前表单连接信息 */
    private RoundButton testBtn;
    /** 测试连接内联状态：「✓ success」绿色 / 「✗ 原因」红色 */
    private JLabel testStatusLabel;

    // 设计 Token（与 AddModelDialog 一致，保证视觉统一）
    private static Color bgSurface() { return ComboStyle.popupSurfaceColor(); }
    private static Color surfaceCard() { return ComboStyle.surfaceColor(); }
    private static Color borderDefault() { return new JBColor(new Color(0xD8D8DC), new Color(0x3A3A40)); }
    private static Color borderHover() { return JBColor.namedColor("Component.focusColor", new JBColor(0x2D6BDB, 0x589DF6)); }
    private static Color brandPrimary() { return JBColor.namedColor("Link.activeForeground", new JBColor(0x2D6BDB, 0x589DF6)); }
    private static Color textPrimary() { return new JBColor(new Color(0x111827), new Color(0xF3F4F6)); }
    private static Color textSecondary() { return new JBColor(new Color(0x6B7280), new Color(0x9CA3AF)); }
    private static Color textMuted() { return new JBColor(new Color(0x9CA3AF), new Color(0x6B7280)); }
    private static Color inputBg() { return new JBColor(new Color(0x000000, true), new Color(0x000000, true)); }
    private static final int RADIUS_INPUT = 10;

    public AddDataSourceDialog(Window parent) {
        this(parent, null);
    }

    public AddDataSourceDialog(Window parent, DatabaseComboItem existing) {
        super(parent, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        Color backdrop = bgSurface();
        getRootPane().setBackground(backdrop);
        setBackground(backdrop);
        this.editingItem = existing;
        initComponents();
        if (existing != null) {
            prefill(existing);
        }
        pack();
        setResizable(false);
        applyRoundShape();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { applyRoundShape(); }
        });
        setLocationRelativeTo(parent);
        getRootPane().setDefaultButton(null);
    }

    private void applyRoundShape() {
        int r = JBUI.scale(14);
        setShape(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), r, r));
    }

    public boolean isSaved() { return saved; }

    private void attachDragListener(JComponent target) {
        final Point[] dragOffset = {null};
        target.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        target.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.getY() > 36) return;
                dragOffset[0] = e.getPoint();
            }
        });
        target.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset[0] == null) return;
                Point loc = getLocation();
                setLocation(loc.x + e.getX() - dragOffset[0].x, loc.y + e.getY() - dragOffset[0].y);
            }
        });
    }

    private void prefill(DatabaseComboItem item) {
        DataSourceDao.DatabaseConnectionInfo info = DataSourceDao.getByName(item.name);
        if (info == null) return;
        nameField.setText(info.name);
        selectedType = "sqlite".equalsIgnoreCase(info.type) ? "sqlite" : "mysql";
        hostField.setText(info.host == null ? "" : info.host);
        portField.setText(info.port > 0 ? String.valueOf(info.port) : "");
        dbField.setText(info.dbName == null ? "" : info.dbName);
        userField.setText(info.user == null ? "" : info.user);
        passwordField.setText(info.password == null ? "" : info.password);
        updateTypeSelection();
        applyTypeConstraints();
    }

    // ═══════════════════════════════════════════
    //  UI 构建
    // ═══════════════════════════════════════════
    private void initComponents() {
        final int cornerRadius = 14;
        JPanel root = new JPanel(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(bgSurface());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, cornerRadius, cornerRadius));
                g2.setColor(borderDefault());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, cornerRadius, cornerRadius));
                g2.dispose();
            }
        };
        root.setOpaque(true);
        root.setBorder(JBUI.Borders.empty(32, 40, 22, 40));

        JPanel header = buildHeader();
        root.add(header, BorderLayout.NORTH);
        attachDragListener(header);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty(20, 0, 0, 0));

        body.add(buildTypeSection());
        body.add(Box.createVerticalStrut(20));
        body.add(buildFormSection());
        body.add(Box.createVerticalStrut(24));
        body.add(buildFooter());

        root.add(body, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setOpaque(false);

        JLabel title = new JLabel(editingItem != null ? "编辑数据源" : "添加数据源");
        title.setFont(JBUI.Fonts.label(16).deriveFont(Font.BOLD));
        title.setForeground(textPrimary());
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(Box.createHorizontalStrut(6));

        JButton closeBtn = new JButton("×") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    if (getModel().isArmed() || getModel().isRollover()) {
                        com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                        g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                        g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 6, 6);
                    }
                    g2.setFont(JBUI.Fonts.label(16).deriveFont(Font.PLAIN));
                    g2.setColor(textSecondary());
                    FontMetrics fm = g2.getFontMetrics();
                    String t = "×";
                    int tx = (getWidth() - fm.stringWidth(t)) / 2;
                    int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(t, tx, ty);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        closeBtn.setPreferredSize(JBUI.size(28, 28));
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusable(false);
        closeBtn.setOpaque(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> dispose());
        header.add(closeBtn);
        return header;
    }

    private JPanel buildTypeSection() {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setOpaque(false);

        JLabel label = new JLabel("数据库类型");
        label.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        label.setForeground(textPrimary());
        section.add(label, BorderLayout.NORTH);

        segControl = new JPanel(new GridLayout(1, 2, 0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(borderDefault());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                g2.setColor(surfaceCard());
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 11, 11));
                g2.dispose();
            }
        };
        segControl.setOpaque(false);
        segControl.setBorder(JBUI.Borders.empty(3));
        segControl.setPreferredSize(new Dimension(0, 40));

        ButtonGroup group = new ButtonGroup();
        mysqlRadio = new SegRadio("MySQL", true);
        sqliteRadio = new SegRadio("SQLite", false);
        for (JRadioButton rb : new JRadioButton[]{mysqlRadio, sqliteRadio}) {
            group.add(rb);
            segControl.add(rb);
            rb.addActionListener(e -> onTypeChanged());
        }
        section.add(segControl, BorderLayout.CENTER);
        return section;
    }

    private void onTypeChanged() {
        selectedType = mysqlRadio.isSelected() ? "mysql" : "sqlite";
        applyTypeConstraints();
    }

    private void updateTypeSelection() {
        mysqlRadio.setSelected("mysql".equals(selectedType));
        sqliteRadio.setSelected("sqlite".equals(selectedType));
    }

    /** SQLite 类型：主机/端口整行隐藏；其他字段（名称/库名/用户名/密码）保留并支持填写。 */
    private void applyTypeConstraints() {
        boolean isMysql = "mysql".equals(selectedType);
        // 「库名」标签/placeholder 切换
        if (dbLabel != null) {
            dbLabel.setText(isMysql ? "库名" : "数据库文件地址");
        }
        if (dbField != null) {
            dbField.putClientProperty("JTextField.placeholderText",
                    isMysql ? "例如 mydb" : "例如 /path/to/app.db 或 :memory:");
        }
        // 重建表单行：GridBagLayout 不擅长隐藏占位空间，整体重建最稳
        rebuildFormRows();
        // 刷新对话框尺寸以匹配新的行数
        if (getRootPane() != null) {
            pack();
            // setShape 圆角在 dialog 尺寸变化后需要重新裁剪
            java.awt.geom.RoundRectangle2D.Float shape =
                    new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14);
            setShape(shape);
        }
    }

    private JPanel buildFormSection() {
        formContainer = new JPanel(new GridBagLayout());
        formContainer.setOpaque(false);
        rebuildFormRows();
        return formContainer;
    }

    /**
     * 按当前 selectedType 重建表单行（GridBagLayout 不擅长隐藏占位空间，重建最稳）。
     * SQLite 时跳过主机/端口；其它字段（名称/库名/用户名/密码）保留并可填写。
     */
    private void rebuildFormRows() {
        if (formContainer == null) return;
        formContainer.removeAll();
        boolean isMysql = "mysql".equals(selectedType);

        // 字段是创建一次的（首次 buildFormSection 时），这里只调整可见性与文本
        if (nameField == null) {
            nameField = createTextField("数据源显示名称");
        }
        if (hostField == null) {
            hostField = createTextField("例如 127.0.0.1");
        }
        if (portField == null) {
            portField = createTextField("例如 3306");
        }
        if (dbField == null) {
            dbField = createTextField(isMysql ? "例如 mydb" : "例如 /path/to/app.db 或 :memory:");
            dbLabel = formLabel(isMysql ? "库名" : "数据库文件地址");
        }
        if (userField == null) {
            userField = createTextField("数据库用户名");
        }
        if (passwordField == null) {
            passwordField = new JPasswordField();
            styleTextField(passwordField);
            passwordField.putClientProperty("JTextField.placeholderText", "数据库密码（明文存储）");
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = JBUI.insets(8, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        int row = 0;

        gbc.gridy = row++;
        formContainer.add(formRow("名称", nameField), gbc);

        if (isMysql) {
            gbc.gridy = row++;
            formContainer.add(formRow("主机", hostField), gbc);
            gbc.gridy = row++;
            formContainer.add(formRow("端口", portField), gbc);
        }

        gbc.gridy = row++;
        formContainer.add(formRow(dbLabel, dbField), gbc);

        gbc.gridy = row++;
        formContainer.add(formRow("用户名", userField), gbc);

        gbc.gridy = row++;
        formContainer.add(formRow("密码", passwordField), gbc);

        formContainer.revalidate();
        formContainer.repaint();
    }

    private JPanel buildFooter() {
        // footer 两行布局：
        //  NORTH = 测试连接行（独立一行，左对齐）—— 放在主按钮上方
        //  CENTER = 主按钮行（取消 / 添加）—— 保持右下角
        //  不再显示 tip「可随时通过数据源下拉框管理」（太啰嗦且破坏视觉）
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setOpaque(false);

        // ── 测试连接行（NORTH）—— 独立一行、左对齐 ──
        testBtn = new RoundButton("测试连接", 6);
        testBtn.setFont(JBUI.Fonts.label(11));
        testBtn.setForeground(textSecondary());
        testBtn.setBackground(new Color(0x000000, true));
        testBtn.putClientProperty("CP.outline", borderDefault());
        testBtn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        testBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                testBtn.setForeground(brandPrimary());
                testBtn.putClientProperty("CP.outline", brandPrimary());
            }
            @Override public void mouseExited(MouseEvent e) {
                testBtn.setForeground(textSecondary());
                testBtn.putClientProperty("CP.outline", borderDefault());
            }
        });
        testBtn.addActionListener(e -> onTestConnection());

        testStatusLabel = new JLabel(" ");
        testStatusLabel.setFont(JBUI.Fonts.label(11));
        testStatusLabel.setForeground(textMuted());

        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testRow.setOpaque(false);
        testRow.add(testBtn);
        testRow.add(testStatusLabel);
        footer.add(testRow, BorderLayout.NORTH);

        // ── 主按钮行（CENTER）—— 保持右下角 ──
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);

        RoundButton cancelBtn = new RoundButton("取消", 8);
        cancelBtn.setFont(JBUI.Fonts.label(13));
        cancelBtn.setForeground(textSecondary());
        cancelBtn.setBackground(new Color(0x000000, true));
        cancelBtn.putClientProperty("CP.outline", borderDefault());
        cancelBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                cancelBtn.setForeground(brandPrimary());
                cancelBtn.putClientProperty("CP.outline", brandPrimary());
            }
            @Override public void mouseExited(MouseEvent e) {
                cancelBtn.setForeground(textSecondary());
                cancelBtn.putClientProperty("CP.outline", borderDefault());
            }
        });
        cancelBtn.addActionListener(e -> dispose());

        RoundButton saveBtn = new RoundButton(editingItem != null ? "保存修改" : "添加数据源", 8);
        saveBtn.setFont(JBUI.Fonts.label(13).deriveFont(Font.BOLD));
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setBackground(brandPrimary());
        saveBtn.addActionListener(e -> onSave());

        btnRow.add(cancelBtn);
        btnRow.add(saveBtn);
        footer.add(btnRow, BorderLayout.CENTER);
        return footer;
    }

    private void onSave() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Messages.showWarningDialog(this, "请填写数据源名称", "校验失败");
            return;
        }
        String host = hostField.getText().trim();
        int port = 0;
        try {
            String p = portField.getText().trim();
            if (!p.isEmpty()) port = Integer.parseInt(p);
        } catch (NumberFormatException ex) {
            Messages.showWarningDialog(this, "端口必须为数字", "校验失败");
            return;
        }
        String dbName = dbField.getText().trim();
        String user = userField.getText();
        String password = new String(passwordField.getPassword());

        String id = (editingItem != null) ? editingItem.id : "";
        DataSourceDao.save(id, name, selectedType, host, port, dbName, user, password, 0);
        saved = true;
        dispose();
    }

    /**
     * 测试当前表单的连接配置（不保存到数据库，仅校验）。
     * 成功：右侧显示绿色「✓ success」；失败：显示红色「✗ 原因」。
     * 异步执行，避免阻塞 EDT；测试期间按钮置灰防重复点击。
     */
    private void onTestConnection() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            setTestStatus(false, "请先填写数据源名称");
            return;
        }
        String host = hostField.getText().trim();
        int port = 0;
        try {
            String p = portField.getText().trim();
            if (!p.isEmpty()) port = Integer.parseInt(p);
        } catch (NumberFormatException ex) {
            setTestStatus(false, "端口必须为数字");
            return;
        }
        String dbName = dbField.getText().trim();
        String user = userField.getText();
        String password = new String(passwordField.getPassword());

        // 临时构建连接信息（不落库）
        DatabaseConnectionInfo info = new DatabaseConnectionInfo();
        info.name = name;
        info.type = selectedType;
        info.host = host;
        info.port = port;
        info.dbName = dbName;
        info.user = user;
        info.password = password;

        // 锁定按钮 + 显示「测试中…」
        testBtn.setEnabled(false);
        testBtn.setText("测试中…");
        setTestStatus(null, "正在连接…");

        final String url = info.buildJdbcUrl();
        final String userFinal = (info.user == null || info.user.isEmpty()) ? null : info.user;
        final String pwdFinal = info.password;
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    if ("sqlite".equals(info.type)) {
                        Class.forName("org.sqlite.JDBC");
                    } else {
                        Class.forName("com.mysql.cj.jdbc.Driver");
                    }
                } catch (ClassNotFoundException e) {
                    return false; // 驱动缺失
                }
                try (Connection conn = DriverManager.getConnection(url, userFinal, pwdFinal)) {
                    return conn != null && !conn.isClosed();
                } catch (SQLException e) {
                    return false;
                }
            }

            @Override
            protected void done() {
                testBtn.setEnabled(true);
                testBtn.setText("测试连接");
                boolean ok;
                try {
                    ok = get();
                } catch (Exception ex) {
                    ok = false;
                }
                if (ok) {
                    setTestStatus(true, "success");
                } else {
                    // 重连获取真实错误信息（更友好）
                    String reason = probeFailureReason(info);
                    setTestStatus(false, reason);
                }
            }
        }.execute();
    }

    /**
     * 在 doInBackground 拿到失败布尔后，再发起一次连接以提取真实异常文案（doInBackground 已吞掉）。
     * 仍然失败则返回 Throwable 的简短描述。
     */
    private String probeFailureReason(DatabaseConnectionInfo info) {
        String url = info.buildJdbcUrl();
        String user = (info.user == null || info.user.isEmpty()) ? null : info.user;
        try {
            Class.forName("sqlite".equals(info.type) ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            return "缺少 JDBC 驱动";
        }
        try (Connection conn = DriverManager.getConnection(url, user, info.password)) {
            return "未知原因";
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
            // 截断过长堆栈
            if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
            return msg;
        }
    }

    /**
     * 更新内联测试状态。ok=null=中性（如「正在连接…」）。
     */
    private void setTestStatus(Boolean ok, String text) {
        if (ok == null) {
            testStatusLabel.setForeground(textMuted());
            testStatusLabel.setText("⟳ " + text);
        } else if (ok) {
            testStatusLabel.setForeground(new JBColor(new Color(0x16A34A), new Color(0x22C55E)));
            testStatusLabel.setText("✓ " + text);
        } else {
            testStatusLabel.setForeground(new JBColor(new Color(0xDC2626), new Color(0xF87171)));
            testStatusLabel.setText("✗ " + text);
        }
    }

    // 分段单选按钮（自绘 pill 选中态）
    private class SegRadio extends JRadioButton {
        SegRadio(String text, boolean selected) {
            super(text, selected);
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (isSelected()) {
                g2.setColor(brandPrimary());
                g2.fill(new RoundRectangle2D.Float(3, 3, w - 6, h - 6, 8, 8));
                g2.setColor(Color.WHITE);
            } else {
                g2.setColor(textSecondary());
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(getText())) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(getText(), tx, ty);
            g2.dispose();
        }
    }

    private class RoundButton extends JButton {
        private final int radius;
        RoundButton(String text, int radius) {
            super(text);
            this.radius = radius;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder(10, 24, 10, 24));
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color bg = getBackground();
            if (bg != null && bg.getAlpha() > 0) {
                g2.setColor(getModel().isArmed() && isEnabled() ? darken(bg, 0.88f) : bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
            }
            Object outline = getClientProperty("CP.outline");
            if (outline instanceof Color) {
                g2.setColor((Color) outline);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, radius, radius));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private JLabel formLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(JBUI.Fonts.label(12));
        l.setForeground(textSecondary());
        l.setPreferredSize(new Dimension(84, 22));
        return l;
    }

    private JBTextField createTextField(String placeholder) {
        JBTextField f = new JBTextField();
        styleTextField(f);
        f.putClientProperty("JTextField.placeholderText", placeholder);
        return f;
    }

    private void styleTextField(JComponent field) {
        field.setFont(JBUI.Fonts.label(13));
        field.setOpaque(false);
        field.setBackground(inputBg());
        field.setBorder(new RoundBorder(RADIUS_INPUT, borderDefault(), borderDefault(), 1) {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean focus = (c instanceof JComponent) && Boolean.TRUE.equals(
                        ((JComponent) c).getClientProperty("CP.fieldFocused"));
                Color line = focus ? brandPrimary() : borderDefault();
                float lw = focus ? 1.5f : 1f;
                g2.setColor(line);
                g2.setStroke(new BasicStroke(lw));
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, RADIUS_INPUT, RADIUS_INPUT));
                g2.dispose();
            }
        });
        if (field instanceof JTextField) {
            JTextField tf = (JTextField) field;
            tf.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    tf.putClientProperty("CP.fieldFocused", Boolean.TRUE);
                    tf.repaint();
                }
                @Override public void focusLost(FocusEvent e) {
                    tf.putClientProperty("CP.fieldFocused", Boolean.FALSE);
                    tf.repaint();
                }
            });
            tf.setColumns(24);
        }
    }

    private JPanel formRow(String labelText, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(formLabel(labelText), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private JPanel formRow(JLabel label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private static Color darken(Color c, float factor) {
        return new Color(
                Math.max(0, (int)(c.getRed() * factor)),
                Math.max(0, (int)(c.getGreen() * factor)),
                Math.max(0, (int)(c.getBlue() * factor)),
                c.getAlpha());
    }

    /** 圆角输入框边框（复用 AddModelDialog 的 RoundBorder 逻辑）。 */
    private static class RoundBorder extends AbstractBorder {
        private final int radius;
        private final Color focus;
        private final Color normal;
        private final int thickness;
        RoundBorder(int radius, Color focus, Color normal, int thickness) {
            this.radius = radius;
            this.focus = focus;
            this.normal = normal;
            this.thickness = thickness;
        }
        @Override
        public Insets getBorderInsets(Component c) { return JBUI.insets(6); }
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(6, 6, 6, 6);
            return insets;
        }
    }
}
