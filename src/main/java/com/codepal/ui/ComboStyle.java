package com.codepal.ui;

import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;

/**
 * CP 下拉框样式 —— 现代简约风格，参考 Material 3 + Apple HIG。
 *
 * 核心设计：
 * - 弹窗内选项为"卡片式"：四周留白，选中/hover态绘制圆角pill背景
 * - 操作按钮（编辑/删除）始终可见，柔和灰色，hover变紫色
 * - 收起态箭头在圆角框内
 * - 遵循 4pt 网格间距系统
 */
public final class ComboStyle {

    private ComboStyle() {}

    // ═══════════════════════════════════════════
    //  设计 Token
    // ═══════════════════════════════════════════

    /** 强调色：IDEA 原生链接/选中蓝（替代原紫罗兰怪色，贴合平台风格） */
    public static Color brandAccent() {
        return JBColor.namedColor("Link.activeForeground", new JBColor(0x2D6BDB, 0x589DF6));
    }

    /** 品牌色柔和版 */
    public static Color brandAccentSoft() {
        Color a = brandAccent();
        return new Color(a.getRed(), a.getGreen(), a.getBlue(), 36);
    }

    /** 弹窗表面色 */
    public static Color surfaceColor() {
        Color bg = UIUtil.getPanelBackground();
        float f = JBColor.isBright() ? 0.02f : 0.03f;
        return new Color(
                Math.max(0, (int)(bg.getRed() * (1 - f))),
                Math.max(0, (int)(bg.getGreen() * (1 - f))),
                Math.max(0, (int)(bg.getBlue() * (1 - f))));
    }

    /** 按钮表面色 */
    public static Color buttonSurface() {
        Color bg = UIUtil.getPanelBackground();
        if (JBColor.isBright()) {
            return new Color(
                    Math.max(0, (int)(bg.getRed() * 0.97f)),
                    Math.max(0, (int)(bg.getGreen() * 0.97f)),
                    Math.max(0, (int)(bg.getBlue() * 0.98f)));
        } else {
            return new Color(
                    Math.min(255, (int)(bg.getRed() * 1.06f)),
                    Math.min(255, (int)(bg.getGreen() * 1.06f)),
                    Math.min(255, (int)(bg.getBlue() * 1.07f)));
        }
    }

    /** 按钮hover色 */
    public static Color buttonHover() {
        Color s = buttonSurface();
        return JBColor.isBright() ? shade(s, 0.96f) : shade(s, 1.08f);
    }

    /** 弹窗表面色（比按钮表面稍亮/稍暗，形成层次区分） */
    public static Color popupSurfaceColor() {
        Color bg = UIUtil.getPanelBackground();
        if (JBColor.isBright()) {
            return new Color(
                    Math.max(0, (int)(bg.getRed() * 0.95f)),
                    Math.max(0, (int)(bg.getGreen() * 0.95f)),
                    Math.max(0, (int)(bg.getBlue() * 0.96f)));
        } else {
            // 暗色主题：popup比按钮更亮，产生浮起效果
            return new Color(
                    Math.min(255, (int)(bg.getRed() * 1.12f)),
                    Math.min(255, (int)(bg.getGreen() * 1.12f)),
                    Math.min(255, (int)(bg.getBlue() * 1.14f)));
        }
    }

    /** 选项选中色：比表面稍深一档的灰（扁平、无蓝、无圆角），贴近 CodeBuddy 原生下拉 */
    public static Color selectionColor() {
        return new JBColor(0xE6E7EA, 0x2F343B);
    }

    /** 选项hover色：比选中更淡一点的灰，行 hover 时浮现 */
    public static Color hoverColor() {
        return new JBColor(0xF2F2F3, 0x262A30);
    }

    public static Color textPrimary() { return UIUtil.getListForeground(); }
    public static Color textSelected() { return UIUtil.getListSelectionForeground(true); }

    public static Color textSecondary() {
        return JBColor.namedColor("Label.infoForeground",
                new JBColor(0x6B7280, 0x9CA3AF));
    }

    public static Color linkColor() { return brandAccent(); }

    /** 数据源下拉收起态图标（数据库） */
    public static Icon databaseIcon() {
        return IconLoader.getIcon("/icons/combo_database.svg", ComboStyle.class);
    }

    /** 底部「＋ 配置数据源」蓝+图标 */
    public static Icon addDataSourceIcon() {
        return IconLoader.getIcon("/icons/add_datasource.svg", ComboStyle.class);
    }

    /**
     * 行内编辑笔图标。
     * ★ 复用模型下拉框的 edit_model.svg（16×16、fill=#FFFFFF 实心），不再用独立的
     *   edit_datasource.svg（22×22 描边）—— 两处编辑笔视觉保持一致。
     */
    public static Icon editDataSourceIcon() {
        return IconLoader.getIcon("/icons/edit_model.svg", ComboStyle.class);
    }

    /** 操作图标默认色（柔和灰色，暗色主题下不要太亮） */
    public static Color iconMuted() {
        return JBColor.namedColor("Label.infoForeground",
                new JBColor(0x6B7280, 0x9CA3AF));
    }

    /** 操作图标在激活卡片上的颜色（亮灰色，在紫色背景上清晰可见） */
    public static Color iconOnCard() {
        return new JBColor(0xFFFFFF, 0xE2E8F0);
    }

    /** 操作图标hover色 */
    public static Color iconHover() { return Color.WHITE; }

    /** 图标背景色：中性灰（亮色淡黑 / 暗色淡白），去掉紫色底 */
    public static Color iconBackground() {
        return JBColor.isBright()
                ? new Color(0, 0, 0, 18)
                : new Color(255, 255, 255, 22);
    }

    /** 操作按钮hover背景色 */
    public static Color actionBtnHoverBg() {
        return Color.WHITE;
    }

    /** 操作按钮默认可见色（在紫色卡片上） */
    public static Color actionBtnDefaultBg() {
        return new Color(255, 255, 255, 50);
    }

    /** 操作按钮非卡片状态下的淡背景色 */
    public static Color actionBtnIdleBg() {
        if (JBColor.isBright()) {
            return new Color(0, 0, 0, 15);
        } else {
            return new Color(255, 255, 255, 12);
        }
    }

    // ── 尺寸 Token ──

    /** 弹窗圆角 */
    public static int popupRadius() { return 12; }
    /** 按钮圆角（胶囊） */
    public static int buttonRadius() { return 18; }
    /** 选项卡片圆角 */
    public static int itemRadius() { return 8; }
    /** 图标容器圆角 */
    public static int iconRadius() { return 7; }
    /** 操作按钮圆角 */
    public static int actionBtnRadius() { return 6; }

    /** 行高（紧凑，贴近 CodeBuddy 原生下拉） */
    public static int rowHeight() { return 34; }
    /** 图标尺寸 */
    public static int iconSize() { return 22; }
    /** 箭头宽度 */
    public static int arrowWidth() { return 32; }
    /** 操作按钮尺寸 */
    public static int actionBtnSize() { return 26; }

    /** 选项卡片左右内边距（留白，形成呼吸感） */
    public static int itemPaddingX() { return 8; }
    /** 选项卡片上下内边距 */
    public static int itemPaddingY() { return 3; }
    /** 选项卡片内部左右padding */
    public static int itemInnerX() { return 12; }
    /** 弹窗右侧额外padding（给滚动条留空间） */
    public static int popupRightPadding() { return 14; }

    // ═══════════════════════════════════════════
    //  核心应用方法
    // ═══════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public static void apply(JComboBox<?> combo) {
        combo.setUI(new CPComboUI(combo));
        combo.setBorder(BorderFactory.createEmptyBorder());
        combo.setOpaque(false);
        combo.setBackground(new Color(0, 0, 0, 0));
    }

    public static void styleList(JList<?> list) {
        if (list == null) return;
        list.setOpaque(false);
        list.setBackground(new Color(0, 0, 0, 0));
        list.setSelectionBackground(new Color(0, 0, 0, 0));
        list.setSelectionForeground(textSelected());
        list.setFocusable(false);
        list.setFixedCellHeight(rowHeight());
        // 列表四周留padding；右侧额外留空间给滚动条
        list.setBorder(BorderFactory.createEmptyBorder(
                itemPaddingY() + 6,      // top
                itemPaddingX(),          // left
                itemPaddingY() + 6,      // bottom
                popupRightPadding()));   // right（给滚动条留空间）
        // 禁用文件列表样式的默认焦点边框
        list.putClientProperty("List.isFileList", Boolean.FALSE);
        // 移除默认焦点边框
        list.setFocusTraversalKeysEnabled(false);
    }

    /** 递归查找容器中的JScrollPane并设置透明 */
    private static void makeScrollPaneTransparent(Container c) {
        if (c == null) return;
        if (c instanceof JScrollPane) {
            JScrollPane sp = (JScrollPane) c;
            sp.setOpaque(false);
            sp.setBackground(new Color(0, 0, 0, 0));
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.setViewportBorder(BorderFactory.createEmptyBorder());
            sp.getViewport().setOpaque(false);
            sp.getViewport().setBackground(new Color(0, 0, 0, 0));
            // 注意：JViewport不支持setBorder()，调用会抛IllegalArgumentException
            sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            if (sp.getVerticalScrollBar() != null) {
                sp.getVerticalScrollBar().setOpaque(false);
                sp.getVerticalScrollBar().setBackground(new Color(0, 0, 0, 0));
            }
        }
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            list.setOpaque(false);
            list.setBackground(new Color(0, 0, 0, 0));
            list.setSelectionBackground(new Color(0, 0, 0, 0));
            list.putClientProperty("List.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
            list.setBorder(BorderFactory.createEmptyBorder(
                    itemPaddingY() + 6, itemPaddingX(),
                    itemPaddingY() + 6, popupRightPadding()));
        }
        for (Component child : c.getComponents()) {
            // JViewport不支持setBorder()，递归时跳过它的边框处理
            // 但仍然递归进入它的children（主要是JList）
            if (child instanceof JViewport) {
                JViewport vp = (JViewport) child;
                vp.setOpaque(false);
                vp.setBackground(new Color(0, 0, 0, 0));
                for (Component vpChild : vp.getComponents()) {
                    if (vpChild instanceof Container) {
                        makeScrollPaneTransparent((Container) vpChild);
                    }
                }
            } else if (child instanceof Container) {
                makeScrollPaneTransparent((Container) child);
            }
        }
    }

    // ═══════════════════════════════════════════
    //  动作按钮接口和工具方法
    // ═══════════════════════════════════════════

    public static final String KEY_HIDE_ARROW = "CP.hideArrow";

    /** 按可用宽度截断文本，末尾加"..." */
    public static String clipTextIfNeeded(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        int totalW = fm.stringWidth(text);
        if (totalW <= maxWidth) return text;
        String ellipsis = "...";
        int ellW = fm.stringWidth(ellipsis);
        if (ellW >= maxWidth) return ellipsis;
        int avail = maxWidth - ellW;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = fm.charWidth(c);
            if (fm.stringWidth(sb.toString()) + w > avail) break;
            sb.append(c);
        }
        return sb.toString() + ellipsis;
    }

    // ═══════════════════════════════════════════
    //  内部：自定义 ComboBoxUI
    // ═══════════════════════════════════════════

    private static class CPComboUI extends BasicComboBoxUI {
        private final JComboBox<?> targetCombo;
        private boolean rollover = false;

        CPComboUI(JComboBox<?> combo) {
            this.targetCombo = combo;
        }

        private boolean isArrowHidden() {
            return Boolean.TRUE.equals(targetCombo.getClientProperty(KEY_HIDE_ARROW));
        }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            // 强制使用轻量级弹出，确保自定义圆角阴影生效
            JPopupMenu.setDefaultLightWeightPopupEnabled(true);
            comboBox.setLightWeightPopupEnabled(true);
            // 关闭焦点：避免 BasicComboBoxUI 默认 focus 边框（深灰方框）叠加我们的 hover 卡
            comboBox.setFocusable(false);
            if (!isArrowHidden()) {
                // 手型光标（仅可点击时）
                Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
                targetCombo.setCursor(hand);
                if (arrowButton != null) {
                    arrowButton.setBorder(BorderFactory.createEmptyBorder());
                    arrowButton.setCursor(hand);
                }
            } else {
                // 无箭头模式：默认光标，禁用焦点
                targetCombo.setCursor(Cursor.getDefaultCursor());
                targetCombo.setFocusable(false);
            }
            targetCombo.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (!isArrowHidden()) { rollover = true; targetCombo.repaint(); } }
                @Override public void mouseExited(MouseEvent e) { if (!isArrowHidden()) { rollover = false; targetCombo.repaint(); } }
            });
            // 关键修复：弹层关闭时（无论通过选中 / 外部点击 / Esc），mouseExited 经常不会触发
            // （弹层期间鼠标被 popup 捕获），导致 rollover 残留→收起态 hover 卡住不消失。
            // 在 popupMenuWillBecomeInvisible 强制清零 rollover 并重绘，覆盖所有关闭路径。
            targetCombo.addPopupMenuListener(new PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
                @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    rollover = false;
                    targetCombo.repaint();
                }
                @Override public void popupMenuCanceled(PopupMenuEvent e) {
                    rollover = false;
                    targetCombo.repaint();
                }
            });
            targetCombo.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { layoutArrow(); }
                @Override public void componentShown(ComponentEvent e) { layoutArrow(); }
            });
            SwingUtilities.invokeLater(this::layoutArrow);
        }

        private void layoutArrow() {
            if (arrowButton == null) return;
            if (isArrowHidden()) {
                arrowButton.setBounds(0, 0, 0, 0);
                return;
            }
            int w = targetCombo.getWidth();
            int h = targetCombo.getHeight();
            int bw = arrowWidth();
            // 按钮占满 combo 整个高度，方便内部 chevron 以 combo 高度精确居中
            arrowButton.setBounds(w - bw, 0, bw, h);
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup popup = new BasicComboPopup(comboBox) {
                @Override
                protected Rectangle computePopupBounds(int px, int py, int pw, int ph) {
                    // 获取屏幕可用区域
                    GraphicsConfiguration gc = targetCombo.getGraphicsConfiguration();
                    Rectangle screenBounds;
                    if (gc != null) {
                        screenBounds = gc.getBounds();
                        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
                        screenBounds.x += screenInsets.left;
                        screenBounds.y += screenInsets.top;
                        screenBounds.width -= screenInsets.left + screenInsets.right;
                        screenBounds.height -= screenInsets.top + screenInsets.bottom;
                    } else {
                        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                        screenBounds = new Rectangle(0, 0, screenSize.width, screenSize.height);
                    }

                    Point comboLoc = targetCombo.getLocationOnScreen();
                    int comboX = (int) comboLoc.getX() - screenBounds.x;
                    int comboY = (int) comboLoc.getY() - screenBounds.y;
                    int comboH = targetCombo.getHeight();

                    int popupW = calcPopupWidthForPopup(this);
                    int itemCount = comboBox.getModel().getSize();
                    int maxRows = Math.min(comboBox.getMaximumRowCount(), itemCount);
                    int rowH = rowHeight();
                    // 阴影边距 - 统一为8px让计算简单
                    int shadowPad = 8;
                    int listPadV = 8;
                    int popupH = maxRows * rowH + listPadV * 2 + shadowPad * 2;

                    int spaceAbove = comboY;
                    int spaceBelow = screenBounds.height - comboY - comboH;
                    int margin = 8;

                    int popupX = px;
                    int popupY;

                    if (spaceAbove >= popupH + margin) {
                        // 朝上弹出：整个popup在下拉框上方，留6px间隙
                        popupY = -popupH - 6;
                    } else if (spaceBelow >= popupH + margin) {
                        // 朝下弹出：整个popup在下拉框下方，留6px间隙
                        popupY = comboH + 6;
                    } else {
                        if (spaceAbove >= spaceBelow) {
                            popupH = Math.max(spaceAbove - margin, 3 * rowH);
                            popupY = -popupH - 6;
                        } else {
                            popupH = Math.max(spaceBelow - margin, 3 * rowH);
                            popupY = comboH + 6;
                        }
                    }

                    return new Rectangle(popupX, popupY, popupW, popupH);
                }

                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    int w = calcPopupWidthForPopup(this);
                    int itemCount = comboBox.getModel().getSize();
                    int maxRows = Math.min(comboBox.getMaximumRowCount(), itemCount);
                    int shadowPad = 8;
                    int listPadV = 8;
                    int h = maxRows * rowHeight() + listPadV * 2;
                    for (Component comp : getComponents()) {
                        if (comp instanceof JScrollPane) {
                            JScrollPane sp = (JScrollPane) comp;
                            sp.setPreferredSize(new Dimension(w - shadowPad * 2, h));
                            break;
                        }
                    }
                }

                @Override
                protected void paintComponent(Graphics g) {
                    // 不调用super.paintComponent，完全自定义绘制
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = getWidth(), h = getHeight();

                    // 阴影和边距
                    int shadowPad = 8;
                    int r = popupRadius();

                    int bodyX = shadowPad;
                    int bodyY = shadowPad;
                    int bodyW = w - shadowPad * 2;
                    int bodyH = h - shadowPad * 2;

                    // 多层阴影
                    for (int i = 10; i >= 2; i -= 2) {
                        float alpha = 0.04f * (10 - i + 2);
                        int sx = bodyX + 2 - i/4;
                        int sy = bodyY + 3 - i/4;
                        int sw = bodyW + i/2;
                        int sh = bodyH + i/2;
                        g2.setColor(new Color(0, 0, 0, Math.min((int)(alpha * 255), 60)));
                        g2.fillRoundRect(sx, sy, sw, sh, r + i/2, r + i/2);
                    }

                    // 主体背景
                    g2.setColor(popupSurfaceColor());
                    g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, r, r);

                    // 外边框
                    Color borderColor;
                    if (JBColor.isBright()) {
                        borderColor = new Color(0, 0, 0, 30);
                    } else {
                        borderColor = new Color(255, 255, 255, 20);
                    }
                    g2.setColor(borderColor);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new RoundRectangle2D.Float(bodyX + 0.5f, bodyY + 0.5f, bodyW - 1, bodyH - 1, r, r));

                    g2.dispose();
                }
            };

            // 设置透明
            popup.setOpaque(false);
            popup.setBackground(new Color(0, 0, 0, 0));
            // 留出阴影空间的border - 统一8px
            popup.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            JList<?> list = popup.getList();

            // ── 同步配置list样式（不延迟，确保popup显示前就绪）──
            styleList(list);

            // scroller透明化可以延迟，不影响坐标和事件
            SwingUtilities.invokeLater(() -> {
                for (Component comp : popup.getComponents()) {
                    if (comp instanceof JScrollPane) {
                        JScrollPane sp = (JScrollPane) comp;
                        sp.setOpaque(false);
                        sp.setBackground(new Color(0, 0, 0, 0));
                        sp.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
                        sp.setViewportBorder(BorderFactory.createEmptyBorder());
                        sp.getViewport().setOpaque(false);
                        sp.getViewport().setBackground(new Color(0, 0, 0, 0));
                    }
                }
                makeScrollPaneTransparent(popup);
            });
            return popup;
        }

        /** 计算popup所需宽度 */
        private int calcPopupWidthForPopup(BasicComboPopup popup) {
            JList<?> list = popup.getList();
            @SuppressWarnings("unchecked")
            ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) list.getCellRenderer();
            if (renderer == null) return targetCombo.getWidth() + 100;

            int maxItemWidth = 0;
            int rowH = rowHeight();
            int count = comboBox.getModel().getSize();
            Object selectedItem = comboBox.getSelectedItem();

            for (int i = 0; i < count; i++) {
                Object value = comboBox.getModel().getElementAt(i);
                Component c = renderer.getListCellRendererComponent(list, value, i, false, false);
                c.setSize(Integer.MAX_VALUE / 2, rowH);
                Dimension pref = c.getPreferredSize();
                if (pref.width > maxItemWidth) maxItemWidth = pref.width;
            }

            // 恢复收起态
            renderer.getListCellRendererComponent(list, selectedItem, -1, false, false);

            int listBorder = itemPaddingX() + popupRightPadding();
            int popupBorderH = 8 + 8; // left(8) + right(8) = 16
            int scrollBar = 16;
            int totalWidth = maxItemWidth + listBorder + popupBorderH + scrollBar;
            int minWidth = targetCombo.getWidth() + 60;
            return Math.max(totalWidth, minWidth);
        }

        private Container getPopupContainer(ComboPopup popup) {
            if (popup instanceof JComponent) return (JComponent) popup;
            Container c = popup.getList().getParent();
            for (int i = 0; i < 3 && c != null; i++) c = c.getParent();
            return c;
        }

        @Override
        protected JButton createArrowButton() {
            JButton arrow = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    // 箭头按钮完全透明：原生按钮底色绝对不画（否则 Craft 窄框右端显双色/发黑），
                    // 也不自绘 chevron —— chevron 由 CPComboUI.paint() 在 super.paint 之后统一
                    // 绘制一次，避免与 paintChildren 阶段再画一遍造成重影模糊。
                }
            };
            arrow.setOpaque(false);
            arrow.setContentAreaFilled(false);
            arrow.setBorderPainted(false);
            arrow.setFocusable(false);
            arrow.setBorder(BorderFactory.createEmptyBorder());
            arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return arrow;
        }

        /**
         * 收起态 preferredSize：直接基于【当前选中项】的文本宽度计算。
         * 关键：不能依赖 renderer.getPreferredSize()——IntelliJ 的 ComboBox 会用按最宽项
         * 计算的渲染器包装，绕过我们的渲染器覆盖逻辑，把整个 combo 撑到最长模型名那么宽。
         * 因此直接用 FontMetrics 量选中项文本宽度，精确跟随当前选中。
         */
        @Override
        public Dimension getPreferredSize(JComponent c) {
            int h = super.getPreferredSize(c).height;
            Object selected = comboBox.getSelectedItem();
            String text = selected == null ? "" : selected.toString();
            Font font = comboBox.getFont();
            FontMetrics fm = comboBox.getFontMetrics(font);
            int textW = fm.stringWidth(text);
            // 与渲染器收起态严格对齐的内边距（图标 + 文字 + 左右留白）
            // rightPad=11 保留余量避免亚像素截断；箭头不再额外加内边距（btnW=arrowWidth）
            int iconArea = iconSize() + 6; // iconSize + icon-text gap
            int leftPad = 8, rightPad = 11;
            int w = leftPad + iconArea + textW + rightPad;
            // 加上箭头宽度（不附加内边距，箭头紧贴文字区右界）
            int btnW = isArrowHidden() ? 0 : arrowWidth();
            w += btnW;
            // 注意：不设 setMaximumSize 封顶，名字完全自适应（长名也完整显示，不加省略号）
            return new Dimension(w, h);
        }

        @Override
        protected Rectangle rectangleForCurrentValue() {
            // 返回整个 combo 宽度，让文字画到任意右边界（FlowLayout 已按名字预留空间）。
            // BasicComboBoxUI 默认会按 "combo 宽 - 箭头宽" 裁剪，导致长名尾巴被切。
            // 改为全宽后文字可延伸到 combo 右边界，箭头只是视觉上的子组件不冲突。
            int w = comboBox.getWidth(), h = comboBox.getHeight();
            Insets in = getInsets();
            return new Rectangle(in.left, in.top, w - in.left - in.right, h - in.top - in.bottom);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            hasFocus = false;
            Graphics2D g2 = (Graphics2D) g.create();
            com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
            int w = c.getWidth(), h = c.getHeight();
            // 与圆环 / + 号按钮完全一致的圆角半径（8），避免“阴影框样式不一致”
            int r = itemRadius();
            // 防御性 hover：仅当鼠标真的在 combo 上（rollover）或弹层打开时绘制。
            boolean hover = rollover || targetCombo.isPopupVisible();

            // 收起态默认无背景、无边框（贴合参考图：只有图标+文字+箭头）；
            // 悬停或弹层打开时，画一个与圆环/+号一致的「淡半透明黑圆角实心」
            // 满铺整个 combo —— 居中填充、无描边、无外阴影偏移，只有这一层。
            if (hover) {
                g2.setColor(new com.intellij.ui.JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                g2.fillRoundRect(0, 0, w, h, r, r);
            }
            g2.dispose();

            // 交给 BasicComboBoxUI 画当前选中项 renderer + 透明 arrowButton（arrowButton 已设为
            // 完全透明、不画任何原生底色，因此 Craft 这种窄框也不会出现「右端双色/发黑」）。
            super.paint(g, c);

            // chevron 由这里统一画【一次】：arrowButton 已透明、不再自绘，避免 paintChildren 阶段
            // 又画一遍造成重影/模糊。坐标基于 combo 整体宽度，箭头按钮占满最右侧 bw 宽。
            int bw = arrowWidth();
            int sz = 7;
            int ax = (w - bw) + (bw - sz) / 2;
            int ay = (h - sz) / 2 + 2;
            Graphics2D cg = (Graphics2D) g.create();
            cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            cg.setColor(hover ? brandAccent() : textSecondary());
            cg.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            cg.drawLine(ax, ay, ax + sz / 2, ay + sz / 2);
            cg.drawLine(ax + sz / 2, ay + sz / 2, ax + sz, ay);
            cg.dispose();
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            // 背景在paint()中统一绘制
        }
    }

    // ═══════════════════════════════════════════
    //  弹窗边框
    // ═══════════════════════════════════════════

    public static class PopupBorder implements Border {
        private final Color bgColor;
        private final int r;

        public PopupBorder(Color bgColor, int radius) {
            this.bgColor = bgColor;
            this.r = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 阴影参数
            int shadowOffX = 2;
            int shadowOffY = 3;
            int shadowBlur = 10;

            int bodyX = x + 2;
            int bodyY = y + 1;
            int bodyW = w - 2 - shadowOffX - 2;
            int bodyH = h - 1 - shadowOffY - 2;

            // 多层柔和阴影（向右下偏移）
            for (int i = shadowBlur; i >= 2; i -= 2) {
                float alpha = 0.02f * (shadowBlur - i + 2);
                int sx = bodyX + shadowOffX - (i - shadowBlur / 2) / 2;
                int sy = bodyY + shadowOffY - (i - shadowBlur / 2) / 2;
                int sw = bodyW + i - shadowBlur / 2;
                int sh = bodyH + i - shadowBlur / 2;
                g2.setColor(new Color(0, 0, 0, Math.min((int)(alpha * 255), 50)));
                g2.fillRoundRect(sx, sy, sw, sh, r + i / 2, r + i / 2);
            }

            // 主体背景（圆角矩形）
            g2.setColor(bgColor);
            g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, r, r);

            // 外边框（1px 细边，增强轮廓）
            Color borderColor;
            if (JBColor.isBright()) {
                borderColor = new Color(0, 0, 0, 25);
            } else {
                borderColor = new Color(255, 255, 255, 18);
            }
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(bodyX + 0.5f, bodyY + 0.5f, bodyW - 1, bodyH - 1, r, r));

            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            // 给阴影留出空间（右下阴影）
            return new Insets(2, 2, 8, 8);
        }

        @Override
        public boolean isBorderOpaque() { return false; }
    }

    // ═══════════════════════════════════════════
    //  绘制工具方法
    // ═══════════════════════════════════════════

    public static void paintIconBg(Graphics2D g2, int x, int y, int size, int radius) {
        g2.setColor(iconBackground());
        g2.fillRoundRect(x, y, size, size, radius, radius);
    }

    /**
     * 绘制选项卡片圆角背景（选中/hover态）。
     * 坐标系为cell相对坐标，自动考虑itemPadding。
     */
    public static void paintItemCard(Graphics2D g2, int w, int h, Color color) {
        int px = itemPaddingX();
        int py = itemPaddingY();
        g2.setColor(color);
        g2.fillRoundRect(px, py, w - px * 2, h - py * 2, itemRadius(), itemRadius());
    }

    /**
     * 绘制选项左侧的紫色强调条（选中态）。
     */
    public static void paintItemAccent(Graphics2D g2, int h) {
        int py = itemPaddingY();
        int barW = 3;
        int barH = h - py * 2 - 8;
        int barX = itemPaddingX() + 4;
        int barY = py + (h - py * 2 - barH) / 2;
        g2.setColor(brandAccent());
        g2.fillRoundRect(barX, barY, barW, barH, 2, 2);
    }

    // ═══════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════

    private static int blend(int base, int overlay, float ratio) {
        return Math.round(base * (1 - ratio) + overlay * ratio);
    }

    private static Color lighten(Color c, float factor) {
        return new Color(
                Math.min(255, (int)(c.getRed() + (255 - c.getRed()) * factor)),
                Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * factor)),
                Math.min(255, (int)(c.getBlue() + (255 - c.getBlue()) * factor)),
                c.getAlpha());
    }

    private static Color shade(Color c, float factor) {
        return new Color(
                Math.max(0, Math.min(255, (int)(c.getRed() * factor))),
                Math.max(0, Math.min(255, (int)(c.getGreen() * factor))),
                Math.max(0, Math.min(255, (int)(c.getBlue() * factor))),
                c.getAlpha());
    }
}
