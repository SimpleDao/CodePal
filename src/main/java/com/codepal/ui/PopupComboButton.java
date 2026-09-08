package com.codepal.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用「按钮 + 圆角阴影弹层列表」组件 —— 抽取自技能（Skills）面板的成熟范式。
 *
 * 背景：JComboBox（BasicComboPopup）的列表行高由 setFixedCellHeight 全局锁死，
 * 无法让某一行（如占位项）高度归零，因而弹层顶部总会残留一整行空白。
 * Skills 面板早已改用 JButton + JPopupMenu 自绘圆角阴影弹层（showSkillPopup），
 * 完美避开该限制。本组件把这套范式固化，供技能 / 数据源等同类下拉复用。
 *
 * 特性（与 showSkillPopup 完全一致）：
 * - 按钮：扁平无边框、hover 半透明黑圆角实心（亮18/暗32）、手型光标、focusable=false
 * - 弹层：JPopupMenu 自绘「多层阴影 + 圆角主体 + 1px 描边」，主体色 ComboStyle.popupSurfaceColor()
 * - 宽度自适应：按最长项文本 stringWidth 计算，杜绝省略号；并取 minWidth、按钮宽度的最大值
 * - 高度：按可见行数（≤ maxRows）计算
 * - 定位：优先朝上（按钮上方留 6px），上方空间不足则回退到下方
 * - hover 追踪：鼠标点写入 clientProperty，供渲染器自查询（key 与既有渲染器兼容）
 *
 * @param <T> 列表项类型
 */
public class PopupComboButton<T> extends JButton {

    /** 列表点击回调（传 MouseEvent 以便调用方区分左右键、双击、取坐标） */
    public interface ItemClick<T> {
        void onClick(T item, int index, MouseEvent e);
    }

    /** 列表鼠标移动回调（用于行内图标 hover 判定，如技能删除图标） */
    public interface ItemMotion {
        void onMove(Point p);
    }

    /** hover 鼠标点的 clientProperty key —— 与 SkillComboRenderer / DatabaseComboRenderer 读取的 key 一致 */
    private static final String MOUSE_POINT_KEY = "CP.modelMousePoint";

    private final JList<T> list;
    private final Supplier<List<T>> provider;
    private final Function<T, String> textFn;
    private final int maxRows;
    private final int minWidth;
    private final int rightReserve;

    private JPopupMenu popup;
    private boolean hover = false;

    public PopupComboButton(Icon icon,
                            String defaultText,
                            ListCellRenderer<T> renderer,
                            Supplier<List<T>> provider,
                            Function<T, String> textFn,
                            ItemClick<T> onClick,
                            ItemMotion onMove,
                            int maxRows,
                            int minWidth,
                            int rightReserve,
                            String tooltip) {
        super(defaultText, icon);
        this.provider = provider;
        this.textFn = textFn;
        this.maxRows = maxRows;
        this.minWidth = minWidth;
        this.rightReserve = rightReserve;

        // ── 按钮外观（与 skillButton 完全一致）──
        setFont(JBUI.Fonts.label(13));
        setFocusable(false);
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (tooltip != null) setToolTipText(tooltip);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
        });

        // ── 内部列表 ──
        list = new JList<>();
        list.setCellRenderer(renderer);
        list.setFixedCellHeight(ComboStyle.rowHeight());
        list.setVisibleRowCount(maxRows);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.setBackground(new Color(0, 0, 0, 0));
        // ★ 列表项均为可点击项，整表手型光标。
        //   （setCursor 若只加在按钮上，鼠标移到弹层内就变回默认箭头 ——
        //     Swing 的 cursor 由鼠标命中的最深子组件决定，list 必须自己持有 HAND_CURSOR。）
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        attachHoverTracking();

        // 点击 + 移动（同一 adapter 需同时注册 MouseListener 与 MouseMotionListener，
        // 否则 mouseMoved 不会被分发 —— 技能面板踩过的坑）
        MouseAdapter adapter = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                T item = list.getModel().getElementAt(idx);
                if (onClick != null) onClick.onClick(item, idx, e);
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (onMove != null) onMove.onMove(e.getPoint());
            }
        };
        list.addMouseListener(adapter);
        list.addMouseMotionListener(adapter);

        addActionListener(e -> showPopup());
    }

    /** 暴露内部列表，供外部渲染器/回调访问（如技能渲染器的 hitDeleteIcon） */
    public JList<T> getList() {
        return list;
    }

    /** 重新拉取数据并重建列表模型（必须在 EDT 调用） */
    public void refreshData() {
        List<T> items = (provider == null) ? java.util.Collections.emptyList() : provider.get();
        list.setModel(new AbstractListModel<T>() {
            @Override public int getSize() { return items.size(); }
            @Override public T getElementAt(int i) { return items.get(i); }
        });
        list.repaint();
    }

    /** 更新按钮文案（如选中某个数据源后显示其名称），并触发重新布局以自适应宽度 */
    public void setDisplayText(String text) {
        setText(text);
        revalidate();
        repaint();
    }

    private void attachHoverTracking() {
        if (Boolean.TRUE.equals(list.getClientProperty("CP.popupHoverBound"))) return;
        list.putClientProperty("CP.popupHoverBound", Boolean.TRUE);
        list.putClientProperty(MOUSE_POINT_KEY, null);
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent me) {
                list.putClientProperty(MOUSE_POINT_KEY, me.getPoint());
                list.repaint();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent me) {
                list.putClientProperty(MOUSE_POINT_KEY, null);
                list.repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (hover) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                // 卡片铺满整个按钮宽高（旧实现用 Math.min(w,h) 会导致右半边无 hover 背景）
                int w = getWidth(), h = getHeight();
                int r = 8, inset = 2;
                g2.setColor(new JBColor(new Color(0, 0, 0, 18), new Color(0, 0, 0, 32)));
                g2.fillRoundRect(inset, inset, w - inset * 2, h - inset * 2, r, r);
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }

    /** 弹出圆角阴影弹层（与 showSkillPopup 同款自绘） */
    public void showPopup() {
        refreshData();
        if (popup != null) popup.setVisible(false);

        final int shadowPad = 8;
        final int radius = ComboStyle.popupRadius();
        final int rowH = ComboStyle.rowHeight();
        final int popupPadV = 8;

        int rows = Math.max(1, list.getModel().getSize());
        int visibleRows = Math.min(rows, maxRows);
        int listH = visibleRows * rowH;

        // ★ 宽度自适应：按最长项文本计算所需宽度，保证长名字完整显示、不出省略号
        Font itemFont = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
        FontMetrics itemFm = list.getFontMetrics(itemFont);
        int maxTextW = 0;
        for (int i = 0; i < list.getModel().getSize(); i++) {
            T it = list.getModel().getElementAt(i);
            String t = (it == null || textFn == null) ? "" : textFn.apply(it);
            if (t != null) maxTextW = Math.max(maxTextW, itemFm.stringWidth(t));
        }
        int px0 = ComboStyle.itemPaddingX();
        int neededW = px0 + ComboStyle.iconSize() + 10 + maxTextW + rightReserve;
        int btnW = getWidth() > 0 ? getWidth() : minWidth;
        int contentW = Math.max(Math.max(btnW, neededW), minWidth);
        int contentH = listH + popupPadV * 2;
        int totalW = contentW + shadowPad * 2;
        int totalH = contentH + shadowPad * 2;

        JPopupMenu p = new JPopupMenu() {
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
        p.setOpaque(false);
        p.setBackground(new Color(0, 0, 0, 0));
        p.setBorder(BorderFactory.createEmptyBorder(shadowPad, shadowPad, shadowPad, shadowPad));

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(popupPadV, popupPadV, popupPadV, popupPadV));
        content.add(list, BorderLayout.CENTER);
        list.setOpaque(false);
        list.setBackground(new Color(0, 0, 0, 0));
        p.add(content);
        p.setPreferredSize(new Dimension(totalW, totalH));

        // ── 位置：相对按钮，优先朝上（与模型下拉行为一致）──
        int btnY = getLocationOnScreen().y;
        Window owner = SwingUtilities.getWindowAncestor(this);
        int screenTop = owner != null ? owner.getLocationOnScreen().y : btnY;
        int spaceAbove = btnY - screenTop;
        int screenH = Toolkit.getDefaultToolkit().getScreenSize().height;
        int spaceBelow = screenH - btnY - getHeight();
        int showY = (spaceAbove >= totalH + 6 || spaceAbove > spaceBelow)
                ? (-totalH - 6)                  // 朝上：按钮上方，留 6px 间隙
                : (getHeight() + 6);             // 朝下
        popup = p;
        p.show(this, 0, showY);
    }

    /** 关闭当前弹层 */
    public void closePopup() {
        if (popup != null) {
            popup.setVisible(false);
            popup = null;
        }
    }
}
