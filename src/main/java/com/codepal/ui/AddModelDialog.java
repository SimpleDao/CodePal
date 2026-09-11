package com.codepal.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.codepal.ui.ComboStyle;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.codepal.model.ModelConfig;
import com.codepal.settings.CPSettings;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 添加/编辑模型对话框 —— Apple HIG + Material 3 融合设计，大厂级视觉体验。
 */
public class AddModelDialog extends JDialog {

    public static final int MODE_BOTH = -1;
    public static final int MODE_CHAT = 0;
    public static final int MODE_COMPLETION = 1;
    public static final int MODE_VISION = 2;

    private final Project project;
    private final int forceMode;
    private boolean saved = false;
    private ModelConfig savedConfig = null;

    private ModelConfig editingConfig = null;
    private int editingIndex = -1;

    // ── 表单字段 ──
    private ProviderCard[] providerCards;
    private JBTextField baseurlField;
    private JPasswordField apiKeyField;
    private JBTextField modelNameField;
    private JBTextField maxContextField;
    private JBTextField maxOutputField;
    private JSlider temperatureSlider;
    private JLabel temperatureValueLabel;
    private JRadioButton modeChatRadio;
    private JRadioButton modeCompletionRadio;
    private boolean completionMode = false;
    private boolean visionMode = false;
    private JCheckBox supportsVisionCheck;
    private JPanel supportsVisionRow; // 视觉能力整行（勾选框+提示文本），补全模式下整体隐藏
    private int selectedProvider = 0;
    private JCheckBox inlineEnableCheck;
    private JSlider delaySlider;
    private JLabel delayValueLabel;
    private JPanel inlinePanel;
    private JPanel segControl;
    private JPanel modeLabelRow;
    private JPanel modeSectionContainer;

    // ── 接口数据格式（只保留两种）──
    private static final String[] PROVIDERS = {"兼容 OpenAI", "兼容 Anthropic"};
    private static final String[] P_FORMATS = {
            ModelConfig.FORMAT_OPENAI,
            ModelConfig.FORMAT_ANTHROPIC
    };
    private static final String[] P_ICONS = {"O", "A"};
    private static final Color[] P_COLORS = {
            new Color(0x10A37F),
            new Color(0xD97757)
    };
    private static final String[] P_BASEURL = {
            "https://api.openai.com/v1/chat/completions",
            "https://api.anthropic.com/v1/messages"
    };
    private static final String[] P_MODEL = {
            "gpt-4o",
            "claude-sonnet-4-20250514"
    };
    private static final int[] P_MAXCTX = {128_000, 200_000};
    private static final int[] P_MAXOUT = {16_384, 8_192};
    private static final double[] P_TEMP = {0.7, 0.7};

    private static final int DS_MAX_CTX = 1_048_576;
    private static final int DS_MAX_OUT = 393_216;
    private static final int COMPLETION_MAX_CTX = 1_048_576;
    private static final int COMPLETION_MAX_OUT = 8192;
    private static final double COMPLETION_TEMP = 0;

    // ── 设计Token（贴合参考图：纯黑背景 / 透明输入框 / 实心蓝按钮）──
    // 复用 ComboStyle 的弹窗背景色，确保与模型下拉框背景完全一致
    private static Color bgSurface() { return ComboStyle.popupSurfaceColor(); }
    // 复用 ComboStyle 的表面色（下拉列表项背景），与下拉框一致
    private static Color surfaceCard() { return ComboStyle.surfaceColor(); }
    private static Color borderDefault() { return new JBColor(
            new Color(0xD8D8DC), new Color(0x3A3A40)); } // 细灰边（在深灰背景上可见）
    private static Color borderHover() { return JBColor.namedColor("Component.focusColor",
            new JBColor(0x2D6BDB, 0x589DF6)); }
    private static Color brandPrimary() { return JBColor.namedColor("Link.activeForeground",
            new JBColor(0x2D6BDB, 0x589DF6)); }
    private static Color brandLightBg() { return new JBColor(
            new Color(0xE8F0FE), new Color(0x16243B)); } // 选中卡片淡蓝底（暗色更纯黑）
    private static Color textPrimary() { return new JBColor(
            new Color(0x111827), new Color(0xF3F4F6)); }
    private static Color textSecondary() { return new JBColor(
            new Color(0x6B7280), new Color(0x9CA3AF)); }
    private static Color textMuted() { return new JBColor(
            new Color(0x9CA3AF), new Color(0x6B7280)); }
    private static Color inputBg() { return new JBColor(
            new Color(0x000000, true), new Color(0x000000, true)); } // 输入框透明（参考图）
    private static final int RADIUS_CARD = 12;
    private static final int RADIUS_INPUT = 10;
    private static final int RADIUS_BTN = 10;

    public AddModelDialog(Project project, Window parent) {
        this(project, parent, MODE_BOTH, null);
    }

    public AddModelDialog(Project project, Window parent, int forceMode) {
        this(project, parent, forceMode, null);
    }

    public AddModelDialog(Project project, Window parent, ModelConfig existing) {
        this(project, parent, MODE_BOTH, existing);
    }

    private AddModelDialog(Project project, Window parent, int forceMode, ModelConfig existing) {
        super(parent, ModalityType.APPLICATION_MODAL);
        // 隐藏 OS 标题栏（由自绘头部 + 右上 X 按钮替代，避免双 ×）
        setUndecorated(true);
        // 关键修正：窗口保持【不透明】。圆角直接用 setShape(圆角矩形) 让 OS 把窗口整体裁成
        // 圆角（SetWindowRgn，不影响文字清晰度，也不触发分层窗口糊字）。这样无需在面板里再画
        // 一层“卡片”，四角死区直接消失，整个弹窗就是单一底色 + 真圆角。
        // 窗口底与卡片同色，保证任何边角像素都一致，绝不会出现“两种颜色”。
        Color backdrop = bgSurface();
        getRootPane().setBackground(backdrop);
        setBackground(backdrop);
        this.project = project;
        this.forceMode = forceMode;
        this.visionMode = (forceMode == MODE_VISION);
        this.editingConfig = existing;
        initComponents();
        if (existing != null) {
            prefillEditing(existing);
        } else if (forceMode == MODE_COMPLETION) {
            completionMode = true;
            modeCompletionRadio.setSelected(true);
            applyCompletionDefaults();
            lockModeForEditing();
            onModeChanged();
        } else if (forceMode == MODE_CHAT) {
            completionMode = false;
            modeChatRadio.setSelected(true);
            lockModeForEditing();
            onModeChanged();
        } else if (forceMode == MODE_VISION) {
            prefillVision();
            lockModeForEditing();
            onModeChanged();
        }
        updateInlinePanelVisibility();
        pack();
        setResizable(false);
        // 把整个窗口裁成圆角（OS 级 SetWindowRgn）：单一面板、文字清晰、四角一致，
        // DWM 会沿圆角区域自动绘制窗口阴影，无需自绘假阴影。
        applyRoundShape();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { applyRoundShape(); }
        });
        setLocationRelativeTo(parent);
        getRootPane().setDefaultButton(null);
    }

    /** 用圆角矩形裁剪窗口外形，使 undecorated 弹框呈现真圆角 */
    private void applyRoundShape() {
        int r = JBUI.scale(14);
        setShape(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), r, r));
    }

    public boolean isSaved() { return saved; }

    /** 给 undecorated 弹框的标题区挂鼠标拖动监听 */
    private void attachDragListener(JComponent target) {
        final Point[] dragOffset = {null};
        target.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        target.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.getY() > 36) return; // 只在标题行允许拖动（避免误触 X 按钮）
                dragOffset[0] = e.getPoint();
            }
        });
        target.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset[0] == null) return;
                Point loc = getLocation();
                setLocation(loc.x + e.getX() - dragOffset[0].x,
                            loc.y + e.getY() - dragOffset[0].y);
            }
        });
    }

    public ModelConfig getModelConfig() {
        return savedConfig;
    }

    private void prefillEditing(ModelConfig cfg) {
        baseurlField.setText(cfg.getApiBase());
        apiKeyField.setText(cfg.getApiKey());
        modelNameField.setText(cfg.getName());
        maxContextField.setText(String.valueOf(cfg.getMaxTokens()));
        maxOutputField.setText(String.valueOf(cfg.getMaxOutput()));
        temperatureSlider.setValue((int) (cfg.getTemperature() * 100));

        CPSettings s = CPSettings.getInstance();
        int chatIdx = s.findChatModelIndexById(cfg.getId());
        int compIdx = s.findCompletionModelIndexById(cfg.getId());

        if (compIdx >= 0 && chatIdx < 0) {
            modeCompletionRadio.setSelected(true);
            completionMode = true;
            editingIndex = compIdx;
        } else {
            modeChatRadio.setSelected(true);
            completionMode = false;
            editingIndex = chatIdx;
        }

        String fmt = cfg.getApiFormat();
        int matched = 0;
        for (int i = 0; i < P_FORMATS.length; i++) {
            if (P_FORMATS[i].equals(fmt)) {
                matched = i;
                break;
            }
        }
        selectedProvider = matched;
        updateProviderCards();
        if (supportsVisionCheck != null) supportsVisionCheck.setSelected(cfg.isSupportsVision());
        lockModeForEditing();
        updateInlinePanelVisibility();
    }

    /** 视觉模型模式：从 CPSettings 的 visionModel 预填字段，并选中对应接口格式 */
    private void prefillVision() {
        CPSettings s = CPSettings.getInstance();
        ModelConfig vm = s.getVisionModel();
        if (vm == null) vm = new ModelConfig();
        baseurlField.setText(vm.getApiBase());
        apiKeyField.setText(vm.getApiKey());
        modelNameField.setText(vm.getName());
        maxContextField.setText(String.valueOf(vm.getMaxTokens()));
        maxOutputField.setText(String.valueOf(vm.getMaxOutput()));
        temperatureSlider.setValue((int) (vm.getTemperature() * 100));

        String fmt = vm.getApiFormat();
        int matched = 0;
        for (int i = 0; i < P_FORMATS.length; i++) {
            if (P_FORMATS[i].equals(fmt)) { matched = i; break; }
        }
        selectedProvider = matched;
        updateProviderCards();
    }

    private int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim().replace(",", "").replace(" ", "")); }
        catch (Exception e) { return fallback; }
    }

    // ═══════════════════════════════════════════
    //  UI 构建
    // ═══════════════════════════════════════════

    private void initComponents() {
        // undecorated 后弹框是直角方块。窗口本身不透明（见构造），圆角由 setShape 交给 OS 裁切，
        // 这里只需把 root 填成圆角矩形底色 + 1px 描边即可，四角死区已被窗口形状裁掉，不存在双色。
        final int cornerRadius = 14;
        JPanel root = new JPanel(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                int r = cornerRadius;
                // 主体填色（与窗口 backdrop 同色，单一底色）
                g2.setColor(bgSurface());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                // 1px 圆角描边：定义面板边缘（四角同样绘制）
                g2.setColor(borderDefault());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, r, r));
                g2.dispose();
            }
        };
        root.setOpaque(true);
        // 左右 40px 内边距：留出与 14px 圆角的安全距离，避免标题/底部文字贴在圆角边缘发紧
        root.setBorder(JBUI.Borders.empty(32, 40, 22, 40));

        // ── 头部：图标 + 标题 + 描述 ──
        JPanel header = buildHeader();
        root.add(header, BorderLayout.NORTH);
        // undecorated 下：标题区可拖动弹框
        attachDragListener(header);

        // ── 主体 ──
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty(20, 0, 0, 0));

        body.add(buildProviderSection());
        body.add(Box.createVerticalStrut(20));
        body.add(buildModeSection());
        body.add(Box.createVerticalStrut(20));
        body.add(buildFormSection());
        // 视觉模式下：温度下方放提示文字（无勾选框，进入该模式即表示要配置视觉模型）
        if (visionMode) {
            body.add(Box.createVerticalStrut(16));
            body.add(buildVisionEnableRow());
        }
        // 自定义/编辑模型时：部分主模型自带看图能力，勾选后无需依赖独立视觉子智能体。
        // 放在温度字段下方、底部按钮上方。补全模式下不显示（由「是否启用补全模型」替代）。
        if (!visionMode && !completionMode) {
            body.add(Box.createVerticalStrut(20));
            body.add(buildSupportsVisionRow());
        }
        // 内联补全设置（启用开关 + 延迟滑块）放在最下方、按钮上方，
        // 编辑模式下隐藏模式选择器时仍可显示。
        if (inlinePanel != null) {
            body.add(inlinePanel);
        }
        body.add(Box.createVerticalStrut(24));
        body.add(buildFooter());

        root.add(body, BorderLayout.CENTER);
        setContentPane(root);

        selectedProvider = 0;
        updateProviderCards();
        if (!visionMode) applyProviderDefaults(0);
    }

    private JPanel buildHeader() {
        // 极简顶部：左侧标题 + 右上角 X 按钮（去掉品牌图标方块和描述行，贴近 CodeBuddy 原生风）
        // 用 BoxLayout + 水平 glue，保证标题贴左、关闭按钮贴右，标题永不重叠/截断
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setOpaque(false);

        JLabel title = new JLabel(visionMode ? "视觉子智能体配置"
                : (editingConfig != null ? "编辑模型" : "添加自定义模型"));
        title.setFont(JBUI.Fonts.label(16).deriveFont(Font.BOLD));
        title.setForeground(textPrimary());
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(Box.createHorizontalStrut(6));

        // 右上角 X 关闭按钮（无描边、hover 淡灰圆角底，与圆环/+号一致）
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

    private JPanel buildProviderSection() {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setOpaque(false);

        JLabel label = sectionLabel("服务提供商");
        section.add(label, BorderLayout.NORTH);

        // 改用 BoxLayout 横向 + 固定 gap：避免 GridLayout 在某些 L&F 下压缩行高导致卡片被压扁
        JPanel cardsRow = new JPanel();
        cardsRow.setLayout(new BoxLayout(cardsRow, BoxLayout.X_AXIS));
        cardsRow.setOpaque(false);
        cardsRow.setPreferredSize(new Dimension(0, 72));
        cardsRow.setMinimumSize(new Dimension(0, 72));
        providerCards = new ProviderCard[PROVIDERS.length];
        for (int i = 0; i < PROVIDERS.length; i++) {
            final int idx = i;
            ProviderCard card = new ProviderCard(PROVIDERS[i], P_ICONS[i], P_COLORS[i]);
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            card.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    selectedProvider = idx;
                    updateProviderCards();
                    applyProviderDefaults(idx);
                }
            });
            providerCards[i] = card;
            // 关键：让 BoxLayout 下卡片横向撑满（默认 preferredSize=100 太窄）
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
            card.setAlignmentY(Component.TOP_ALIGNMENT);
            cardsRow.add(card);
            if (i < PROVIDERS.length - 1) {
                cardsRow.add(Box.createHorizontalStrut(10));
            }
        }
        section.add(cardsRow, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildModeSection() {
        modeSectionContainer = new JPanel(new BorderLayout(0, 10));
        modeSectionContainer.setOpaque(false);
        JPanel section = modeSectionContainer;

        modeLabelRow = new JPanel(new BorderLayout());
        modeLabelRow.setOpaque(false);
        modeLabelRow.add(sectionLabel("配置用途"), BorderLayout.WEST);
        JLabel hint = new JLabel("选择此模型用于聊天对话还是代码补全");
        hint.setFont(JBUI.Fonts.label(11));
        hint.setForeground(textMuted());
        modeLabelRow.add(hint, BorderLayout.EAST);
        section.add(modeLabelRow, BorderLayout.NORTH);

        // Segmented Control
        segControl = new JPanel(new GridLayout(1, 2, 0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(borderDefault());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, RADIUS_CARD, RADIUS_CARD));
                g2.setColor(surfaceCard());
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, RADIUS_CARD - 1, RADIUS_CARD - 1));
                g2.dispose();
            }
        };
        segControl.setOpaque(false);
        segControl.setBorder(JBUI.Borders.empty(3));

        ButtonGroup group = new ButtonGroup();
        modeChatRadio = new SegRadio("聊天对话", true);
        modeCompletionRadio = new SegRadio("代码补全", false);

        for (SegRadio rb : new SegRadio[]{(SegRadio) modeChatRadio, (SegRadio) modeCompletionRadio}) {
            group.add(rb);
            segControl.add(rb);
            rb.addActionListener(e -> onModeChanged());
        }

        // 内联补全设置面板（启用开关 + 延迟滑块）——仅在补全模式下显示
        inlinePanel = new JPanel();
        inlinePanel.setLayout(new BoxLayout(inlinePanel, BoxLayout.Y_AXIS));
        inlinePanel.setOpaque(false);
        inlinePanel.setBorder(JBUI.Borders.empty(4, 0, 0, 0));

        // 启用开关行
        JPanel enableRow = new JPanel(new BorderLayout(8, 0));
        enableRow.setOpaque(false);
        inlineEnableCheck = new JCheckBox("是否启用补全模型 (Ghost Text)");
        inlineEnableCheck.setFont(JBUI.Fonts.label(13));
        inlineEnableCheck.setForeground(textPrimary());
        inlineEnableCheck.setOpaque(false);
        inlineEnableCheck.setFocusable(false);
        inlineEnableCheck.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        enableRow.add(inlineEnableCheck, BorderLayout.WEST);

        JLabel enableHint = new JLabel("输入代码时实时显示 AI 补全建议，Tab 键采纳");
        enableHint.setFont(JBUI.Fonts.label(11));
        enableHint.setForeground(textMuted());
        enableRow.add(enableHint, BorderLayout.EAST);

        inlinePanel.add(enableRow);

        // 延迟滑块行
        JPanel delayRow = new JPanel(new BorderLayout(12, 0));
        delayRow.setOpaque(false);
        delayRow.setBorder(JBUI.Borders.empty(6, 22, 0, 0));

        JLabel delayLabel = new JLabel("触发延迟");
        delayLabel.setFont(JBUI.Fonts.label(12));
        delayLabel.setForeground(textSecondary());
        delayRow.add(delayLabel, BorderLayout.WEST);

        JPanel sliderWrap = new JPanel(new BorderLayout(8, 0));
        sliderWrap.setOpaque(false);
        delaySlider = new JSlider(100, 2000, CPSettings.getInstance().getCompletionDelayMs());
        delaySlider.setOpaque(false);
        delaySlider.setFocusable(false);
        delaySlider.setUI(new PurpleSliderUI(delaySlider));
        delayValueLabel = new JLabel(delaySlider.getValue() + " ms");
        delayValueLabel.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        delayValueLabel.setForeground(brandPrimary());
        delayValueLabel.setPreferredSize(new Dimension(64, 20));
        delayValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        delaySlider.addChangeListener(e ->
                delayValueLabel.setText(delaySlider.getValue() + " ms"));
        sliderWrap.add(delaySlider, BorderLayout.CENTER);
        sliderWrap.add(delayValueLabel, BorderLayout.EAST);
        delayRow.add(sliderWrap, BorderLayout.CENTER);

        inlinePanel.add(delayRow);

        // 将分段控件放在 CENTER 区域（内联面板独立出 modeSectionContainer，避免编辑模式连带着被隐藏）
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);
        segControl.setAlignmentX(Component.LEFT_ALIGNMENT);
        centerPanel.add(segControl);

        section.add(centerPanel, BorderLayout.CENTER);

        // 初始加载当前设置
        CPSettings s = CPSettings.getInstance();
        inlineEnableCheck.setSelected(s.isEnableSmartAutoComplete());

        return section;
    }

    /** 视觉模式下显示在温度下方的说明（无勾选框：进入该模式即表示要配置视觉模型）。
     *  仅保留提示文字，宽度与上方字段对齐。 */
    private JPanel buildVisionEnableRow() {
        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setOpaque(false);

        JLabel hint = new JLabel("<html><div style='width:280px'>主模型在用户附带图片时，通过 view_image 工具调用此视觉模型理解图片</div></html>");
        hint.setFont(JBUI.Fonts.label(11));
        hint.setForeground(textMuted());
        row.add(hint, BorderLayout.NORTH);

        return row;
    }

    /** 自定义/编辑模型时：标记该主模型自身具备视觉（看图）能力，勾选后聊天流程可直接看图，
     *  无需依赖独立的视觉子智能体。 */
    private JPanel buildSupportsVisionRow() {
        JPanel row = new JPanel(new BorderLayout(0, 4));
        row.setOpaque(false);

        supportsVisionCheck = new JCheckBox("该模型自带视觉能力");
        supportsVisionCheck.setFont(JBUI.Fonts.label(13).deriveFont(Font.BOLD));
        supportsVisionCheck.setForeground(textPrimary());
        supportsVisionCheck.setOpaque(false);
        supportsVisionCheck.setFocusable(false);
        supportsVisionCheck.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.add(supportsVisionCheck, BorderLayout.NORTH);

        JLabel hint = new JLabel("<html><div style='width:280px'>部分主模型（如 GPT-4o、Claude）本身即可识别图片；"
                + "勾选后用户附图时直接由原模型处理，不再调用独立的视觉子智能体。</div></html>");
        hint.setFont(JBUI.Fonts.label(11));
        hint.setForeground(textMuted());
        row.add(hint, BorderLayout.CENTER);

        supportsVisionRow = row; // 保存引用，供补全模式下整体隐藏
        return row;
    }

    private void applyCompletionDefaults() {
        if (visionMode) return; // 视觉子智能体模式不覆盖预填字段
        baseurlField.setText("https://api.deepseek.com/beta");
        modelNameField.setText("deepseek-v4-flash");
        maxContextField.setText(String.valueOf(COMPLETION_MAX_CTX));
        maxOutputField.setText(String.valueOf(COMPLETION_MAX_OUT));
        temperatureSlider.setValue((int) (COMPLETION_TEMP * 100));
    }

    private void updateInlinePanelVisibility() {
        if (inlinePanel != null) {
            inlinePanel.setVisible(completionMode);
        }
        // 补全模式下隐藏「模型自带视觉能力」整行（勾选框+提示文本），避免与「是否启用补全模型」重复出现
        if (supportsVisionRow != null) {
            supportsVisionRow.setVisible(!completionMode && !visionMode);
        }
        if (modeLabelRow != null) {
            Component hint = ((BorderLayout) modeLabelRow.getLayout()).getLayoutComponent(BorderLayout.EAST);
            if (hint instanceof JLabel) {
                hint.setVisible(forceMode == MODE_BOTH && editingConfig == null);
            }
        }
    }

    private JPanel buildFormSection() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(8, 0);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        int row = 0;

        // Base URL
        gbc.gridy = row++; gbc.gridx = 0;
        form.add(formRow("Base URL", baseurlField = createTextField("https://api.example.com")), gbc);

        // API Key
        gbc.gridy = row++;
        apiKeyField = new JPasswordField();
        styleTextField(apiKeyField);
        apiKeyField.putClientProperty("JTextField.placeholderText", "可选，留空使用全局 API Key");
        form.add(formRow("API Key", apiKeyField), gbc);

        // 模型名称
        gbc.gridy = row++;
        modelNameField = createTextField("例如 deepseek-v4-flash");
        form.add(formRow("模型名称", modelNameField), gbc);

        // 数字字段行（两列）
        gbc.gridy = row++;
        JPanel numRow = new JPanel(new GridLayout(1, 2, 12, 0));
        numRow.setOpaque(false);
        maxContextField = createTextField("1048576");
        maxOutputField = createTextField("393216");
        // 数字框列数收窄，避免输入框过长导致标签/后缀错位
        maxContextField.setColumns(12);
        maxOutputField.setColumns(12);
        numRow.add(formRowInline("最大上下文", maxContextField, null));
        numRow.add(formRowInline("最大输出", maxOutputField, null));
        form.add(numRow, gbc);

        // 温度
        gbc.gridy = row++;
        JPanel tempRow = new JPanel(new BorderLayout(12, 0));
        tempRow.setOpaque(false);
        tempRow.add(formLabel("温度"), BorderLayout.WEST);

        JPanel sliderWrap = new JPanel(new BorderLayout(8, 0));
        sliderWrap.setOpaque(false);
        temperatureSlider = new JSlider(0, 100, 70);
        temperatureSlider.setOpaque(false);
        temperatureSlider.setFocusable(false);
        temperatureSlider.setUI(new PurpleSliderUI(temperatureSlider));
        temperatureValueLabel = new JLabel("0.70");
        temperatureValueLabel.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        temperatureValueLabel.setForeground(brandPrimary());
        temperatureValueLabel.setPreferredSize(new Dimension(48, 20));
        temperatureValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        temperatureSlider.addChangeListener(e ->
                temperatureValueLabel.setText(String.format("%.2f", temperatureSlider.getValue() / 100.0)));

        sliderWrap.add(temperatureSlider, BorderLayout.CENTER);
        sliderWrap.add(temperatureValueLabel, BorderLayout.EAST);
        tempRow.add(sliderWrap, BorderLayout.CENTER);
        form.add(tempRow, gbc);

        return form;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);

        // 左侧提示
        JLabel tip = new JLabel("可随时通过模型下拉框管理自定义模型");
        tip.setFont(JBUI.Fonts.label(11));
        tip.setForeground(textMuted());
        footer.add(tip, BorderLayout.WEST);

        // 右侧按钮（统一圆角按钮，贴近参考图）
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnRow.setOpaque(false);

        // 取消：透明底 + 灰描边 + 灰字，hover 变蓝
        RoundButton cancelBtn = new RoundButton("取消", 8);
        cancelBtn.setFont(JBUI.Fonts.label(13));
        cancelBtn.setForeground(textSecondary());
        cancelBtn.setBackground(new Color(0, 0, 0, 0));
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

        // 保存（添加模型）：实心蓝 + 白字，hover 加深
        RoundButton saveBtn = new RoundButton(
                visionMode ? "保存" : (editingConfig != null ? "保存修改" : "添加模型"), 8);
        saveBtn.setFont(JBUI.Fonts.label(13).deriveFont(Font.BOLD));
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setBackground(brandPrimary());
        saveBtn.addActionListener(e -> onSave());

        btnRow.add(cancelBtn);
        btnRow.add(saveBtn);
        footer.add(btnRow, BorderLayout.EAST);
        return footer;
    }

    // 圆角按钮：JButton 默认方角，这里自绘 8px 圆角背景（实心/描边两态）
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

    // ═══════════════════════════════════════════
    //  组件样式工具
    // ═══════════════════════════════════════════

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
        l.setForeground(textPrimary());
        return l;
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
        // 透明输入框 + 细灰边（参考图风格，不填深灰块）
        field.setOpaque(false);
        field.setBackground(inputBg());
        // 自定义边框：默认灰细线，focus 时变蓝（监听 focus 重绘）
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
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1,
                        RADIUS_INPUT, RADIUS_INPUT));
                g2.dispose();
            }
        });
        if (field instanceof JTextField) {
            final JTextField tf = (JTextField) field;
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
        }
        if (field instanceof JBTextField) {
            ((JBTextField) field).setColumns(24);
        } else if (field instanceof JTextField) {
            ((JTextField) field).setColumns(24);
        }
    }

    private JPanel formRow(String labelText, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(formLabel(labelText), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private JPanel formRowInline(String labelText, JComponent field, String suffix) {
        // 横排：标签在左，[输入框 + 后缀] 在右，避免输入框撑满整列、后缀被挤出重叠
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel l = formLabel(labelText);
        row.add(l, BorderLayout.WEST);
        JPanel fieldWrap = new JPanel(new BorderLayout(6, 0));
        fieldWrap.setOpaque(false);
        fieldWrap.add(field, BorderLayout.CENTER);
        if (suffix != null) {
            JLabel s = new JLabel(suffix);
            s.setFont(JBUI.Fonts.label(11));
            s.setForeground(textMuted());
            fieldWrap.add(s, BorderLayout.EAST);
        }
        row.add(fieldWrap, BorderLayout.CENTER);
        return row;
    }

    private static Color darken(Color c, float factor) {
        return new Color(
                Math.max(0, (int)(c.getRed() * factor)),
                Math.max(0, (int)(c.getGreen() * factor)),
                Math.max(0, (int)(c.getBlue() * factor)),
                c.getAlpha());
    }

    // ═══════════════════════════════════════════
    //  厂商卡片
    // ═══════════════════════════════════════════

    private class ProviderCard extends JPanel {
        private final String name;
        private final String iconText;
        private final Color accentColor;
        private boolean hovered = false;

        ProviderCard(String name, String iconText, Color accent) {
            this.name = name;
            this.iconText = iconText;
            this.accentColor = accent;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // 给 GridLayout 一个明确的「最小宽度」：100 让 cell 自然撑到列宽（而不是被 preferredSize 100/2=50 压扁）
            setPreferredSize(new Dimension(100, 72));
            setBorder(JBUI.Borders.empty(8));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            int w = getWidth(), h = getHeight();
            boolean selected = (providerCards != null && providerCards[selectedProvider] == this);

            // 背景
            if (selected) {
                g2.setColor(brandLightBg());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, RADIUS_CARD, RADIUS_CARD));
                g2.setColor(brandPrimary());
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, RADIUS_CARD, RADIUS_CARD));
            } else {
                g2.setColor(surfaceCard());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, RADIUS_CARD, RADIUS_CARD));
                Color bc = hovered ? borderHover() : borderDefault();
                g2.setColor(bc);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, RADIUS_CARD, RADIUS_CARD));
            }

            // 图标
            int iconSize = 28;
            int iconX = (w - iconSize) / 2;
            int iconY = 10;
            if (iconText != null && !iconText.isEmpty()) {
                g2.setColor(selected ? brandPrimary() : accentColor);
                g2.fill(new RoundRectangle2D.Float(iconX, iconY, iconSize, iconSize, 7, 7));
                g2.setColor(Color.WHITE);
                g2.setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
                FontMetrics fm = g2.getFontMetrics();
                int tx = iconX + (iconSize - fm.stringWidth(iconText)) / 2;
                int ty = iconY + (iconSize - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(iconText, tx, ty);
            } else {
                // "+" 图标
                g2.setColor(textMuted());
                g2.setStroke(new BasicStroke(2f));
                int cx = iconX + iconSize / 2, cy = iconY + iconSize / 2;
                g2.drawLine(cx - 6, cy, cx + 6, cy);
                g2.drawLine(cx, cy - 6, cx, cy + 6);
            }

            // 名称
            g2.setFont(JBUI.Fonts.label(11).deriveFont(selected ? Font.BOLD : Font.PLAIN));
            FontMetrics fm2 = g2.getFontMetrics();
            g2.setColor(selected ? brandPrimary() : textPrimary());
            int nx = (w - fm2.stringWidth(name)) / 2;
            int ny = iconY + iconSize + 6 + fm2.getAscent();
            g2.drawString(name, nx, ny);

            // 选中勾
            if (selected) {
                g2.setColor(brandPrimary());
                g2.setStroke(new BasicStroke(2f));
                int cx = w - 14, cy = 12;
                g2.drawLine(cx - 4, cy, cx - 1, cy + 3);
                g2.drawLine(cx - 1, cy + 3, cx + 4, cy - 3);
            }

            g2.dispose();
        }
    }

    private void updateProviderCards() {
        if (providerCards == null) return;
        for (ProviderCard c : providerCards) c.repaint();
    }

    // ═══════════════════════════════════════════
    //  Segmented Radio
    // ═══════════════════════════════════════════

    private class SegRadio extends JRadioButton {
        SegRadio(String text, boolean selected) {
            super(text, selected);
            setOpaque(false);
            setFocusable(false);
            setFont(JBUI.Fonts.label(12).deriveFont(Font.BOLD));
            setForeground(textSecondary());
            setBorder(JBUI.Borders.empty(6, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            int w = getWidth(), h = getHeight();
            boolean enabled = isEnabled();
            if (isSelected()) {
                g2.setColor(enabled ? brandPrimary() : textMuted());
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 8, 8));
                g2.setColor(enabled ? Color.WHITE : textSecondary());
            } else {
                g2.setColor(enabled ? textSecondary() : textMuted());
            }
            FontMetrics fm = g2.getFontMetrics();
            String text = getText();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(text, tx, ty);
            g2.dispose();
        }
    }

    // ═══════════════════════════════════════════
    //  紫色滑块UI
    // ═══════════════════════════════════════════

    private static class PurpleSliderUI extends BasicSliderUI {
        PurpleSliderUI(JSlider slider) { super(slider); }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int trackY = trackRect.y + trackRect.height / 2 - 2;
            int trackH = 4;

            // 未填充部分
            g2.setColor(new JBColor(new Color(0xE5E7EB), new Color(0x4B5563)));
            g2.fill(new RoundRectangle2D.Float(trackRect.x, trackY, trackRect.width, trackH, 2, 2));

            // 已填充部分
            int fillW = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            g2.setColor(brandPrimary());
            g2.fill(new RoundRectangle2D.Float(trackRect.x, trackY, fillW, trackH, 2, 2));
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = thumbRect.x, y = thumbRect.y, s = thumbRect.width;
            // 阴影
            g2.setColor(new Color(0, 0, 0, 25));
            g2.fillOval(x + 1, y + 2, s, s);
            // 主体
            g2.setColor(brandPrimary());
            g2.fillOval(x, y, s, s);
            // 高光
            g2.setColor(new Color(255, 255, 255, 60));
            g2.fillOval(x + 3, y + 3, s - 8, s - 8);
            g2.dispose();
        }

        @Override protected Dimension getThumbSize() { return JBUI.size(18, 18); }
    }

    // ═══════════════════════════════════════════
    //  圆角带聚焦边框
    // ═══════════════════════════════════════════

    private static class RoundBorder extends AbstractBorder {
        private final int radius;
        private final Color defaultColor;
        private final Color focusColor;
        private final int thickness;
        RoundBorder(int radius, Color defaultColor, Color focusColor, int thickness) {
            this.radius = radius; this.defaultColor = defaultColor; this.focusColor = focusColor; this.thickness = thickness;
        }
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean focused = c.isFocusOwner();
            g2.setColor(focused ? focusColor : defaultColor);
            g2.setStroke(new BasicStroke(focused ? thickness : 1f));
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, JBUI.scale(radius), JBUI.scale(radius)));
            g2.dispose();
        }
        @Override
        public Insets getBorderInsets(Component c) { return JBUI.insets(8, 12); }
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12));
            return insets;
        }
    }

    // ═══════════════════════════════════════════
    //  事件处理
    // ═══════════════════════════════════════════

    private void applyProviderDefaults(int idx) {
        if (idx < 0 || idx >= P_BASEURL.length) return;
        if (editingConfig != null) return; // 编辑模式不覆盖
        if (visionMode) return; // 视觉子智能体模式：字段已由 prefillVision() 从已存配置预填，切换服务提供商不得重置用户已填内容
        baseurlField.setText(P_BASEURL[idx]);
        modelNameField.setText(P_MODEL[idx]);
        maxContextField.setText(String.valueOf(P_MAXCTX[idx]));
        maxOutputField.setText(String.valueOf(P_MAXOUT[idx]));
        temperatureSlider.setValue((int) (P_TEMP[idx] * 100));
    }

    private void onModeChanged() {
        completionMode = modeCompletionRadio.isSelected();
        if (editingConfig != null) {
            updateInlinePanelVisibility();
            return;
        }
        if (completionMode) {
            selectedProvider = 0;
            updateProviderCards();
            applyCompletionDefaults();
        } else {
            applyProviderDefaults(selectedProvider);
        }
        updateInlinePanelVisibility();
    }

    private void lockModeForEditing() {
        modeChatRadio.setEnabled(false);
        modeCompletionRadio.setEnabled(false);
        modeChatRadio.setCursor(Cursor.getDefaultCursor());
        modeCompletionRadio.setCursor(Cursor.getDefaultCursor());
        if (segControl != null) {
            segControl.setVisible(false);
        }
        // 隐藏「配置用途」section 的标签与单选控件（编辑模式下模式不可切换）。
        // 注意：inlinePanel 已独立于 modeSectionContainer，不受此隐藏影响，
        // 补全模型的「是否启用补全模型」勾选框仍可正常显示。
        if (modeSectionContainer != null) {
            modeSectionContainer.setVisible(false);
        }
        // 补全模型编辑时，确保内联面板可见（用户需要看到启用开关）
        if (inlinePanel != null && completionMode) {
            inlinePanel.setVisible(true);
        }
    }

    /**
     * 校验并规范化 Base URL：本工具会自动拼接接口后缀（OpenAI: /v1/chat/completions，Anthropic: /v1/messages）。
     * 若用户已手动填写了后缀，会导致重复拼接（如 .../v1/chat/completions/v1/chat/completions）。
     * 检测到时弹窗提示「请只填写域名/基址」，并自动剥离多余后缀后保存。
     *
     * @return 规范化后的 Base URL；用户取消保存时返回 null。
     */
    private String normalizeAndValidateApiBase(String apiBase) {
        boolean anthropic = ModelConfig.FORMAT_ANTHROPIC.equals(P_FORMATS[selectedProvider]);
        String suffix = anthropic ? "/v1/messages" : "/v1/chat/completions";
        String u = apiBase.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith(suffix)) {
            String stripped = u.substring(0, u.length() - suffix.length());
            while (stripped.endsWith("/")) stripped = stripped.substring(0, stripped.length() - 1);
            int r = Messages.showYesNoDialog(this,
                    "你填写的 Base URL 已包含接口后缀（" + suffix + "）。\n" +
                            "系统会自动拼接该后缀，建议只填写域名或基址（如 https://api.openai.com）。\n\n" +
                            "已自动移除多余后缀并保存为：\n" + stripped,
                    "Base URL 提示", Messages.getQuestionIcon());
            if (r != Messages.YES) return null; // 用户取消，自行修改
            baseurlField.setText(stripped);
            return stripped;
        }
        return u;
    }

    private void onSave() {
        String name = modelNameField.getText().trim();
        if (name.isEmpty()) {
            showError("请输入模型名称");
            modelNameField.requestFocus();
            return;
        }
        String apiBaseRaw = baseurlField.getText().trim();
        if (apiBaseRaw.isEmpty()) {
            showError("请输入 Base URL");
            baseurlField.requestFocus();
            return;
        }
        // 校验 Base URL：系统会自动拼接接口后缀，用户若已填后缀会重复拼接；检测到则提示并自动剥离
        String apiBase = normalizeAndValidateApiBase(apiBaseRaw);
        if (apiBase == null) return; // 用户取消保存，自行修改
        if (visionMode && new String(apiKeyField.getPassword()).trim().isEmpty()) {
            showError("配置视觉模型时，API Key 不能为空");
            apiKeyField.requestFocus();
            return;
        }

        CPSettings settings = CPSettings.getInstance();
        ModelConfig newCfg = new ModelConfig(
                name, "", apiBase,
                parseIntOr(maxContextField.getText(), DS_MAX_CTX),
                temperatureSlider.getValue() / 100.0);
        newCfg.setApiKey(new String(apiKeyField.getPassword()).trim());
        newCfg.setMaxOutput(parseIntOr(maxOutputField.getText(), DS_MAX_OUT));
        newCfg.setApiFormat(P_FORMATS[selectedProvider]);
        newCfg.setSupportsVision(supportsVisionCheck != null && supportsVisionCheck.isSelected());

        if (visionMode) {
            // 视觉模型：写入独立的 visionModel 配置（进入该模式即视为启用）
            settings.setVisionModel(newCfg);
            settings.setVisionEnabled(true);
            saved = true;
            savedConfig = newCfg;
            dispose();
            return;
        }

        if (editingConfig != null) {
            newCfg.setId(editingConfig.getId());
            if (completionMode) {
                settings.updateCompletionModel(newCfg);
            } else {
                settings.updateChatModel(newCfg);
            }
        } else if (completionMode) {
            settings.addCompletionModel(newCfg);
        } else {
            settings.addChatModel(newCfg);
        }

        saved = true;
        savedConfig = newCfg;

        settings.setEnableSmartAutoComplete(inlineEnableCheck.isSelected());
        settings.setCompletionDelayMs(delaySlider.getValue());

        dispose();
    }

    private void showError(String msg) {
        UIManager.put("OptionPane.messageFont", JBUI.Fonts.label(13));
        JOptionPane.showMessageDialog(this, msg, "提示", JOptionPane.INFORMATION_MESSAGE);
    }
}
