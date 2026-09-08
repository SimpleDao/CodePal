package com.codepal.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBUI;
import com.codepal.model.DatabaseComboItem;

import javax.swing.*;
import java.awt.*;

/**
 * 数据源「按钮 + 弹层列表」的行渲染器（仅列表态，收起态文案由按钮自身绘制）。
 *
 * 与 DatabaseComboRenderer 的区别：后者服务于 JComboBox（含收起态绘制）；
 * 本类只负责弹层中的行，且列表中不再出现 placeholder 占位项，
 * 因此弹层顶部不会残留空行（JComboBox 受 fixedCellHeight 锁死无法做到）。
 *
 * 行形态：
 * - 真实数据源：数据库图标 + 名称，hover 时右侧浮现编辑笔（仿模型下拉框扁平风）
 * - 末尾「配置数据源」：上方 1px 分隔线 + ＋号图标 + 柔灰文字
 */
public class DatabaseListRenderer extends JComponent implements ListCellRenderer<DatabaseComboItem> {

    /** hover 鼠标点 key —— 与 PopupComboButton 写入的 key 保持一致 */
    private static final String MOUSE_POINT_KEY = "CP.modelMousePoint";
    private static final int EDIT_ICON_SIZE = 16;
    /** 编辑笔命中容差 */
    private static final int HIT_PAD = 6;

    private final Icon dbIcon, addIcon, editIcon;

    private DatabaseComboItem value;
    private boolean isAdd;
    private boolean selected;
    private boolean rowHover;
    private boolean editHover;

    public DatabaseListRenderer() {
        this.dbIcon = ComboStyle.databaseIcon();
        this.addIcon = ComboStyle.addDataSourceIcon();
        this.editIcon = ComboStyle.editDataSourceIcon();
        setOpaque(false);
        setBorder(null);
    }

    /** 编辑笔图标左缘 x（相对列表行宽） */
    public static int editIconX(int rowWidth) {
        return rowWidth - ComboStyle.itemPaddingX() - EDIT_ICON_SIZE;
    }

    /**
     * 判定鼠标点是否命中某行的编辑笔（仅真实数据源行）。
     * 不依赖渲染器字段（渲染时序不可靠），直接用 locationToIndex 反算行号 + 从数据源实时判断。
     */
    public boolean hitEditIcon(Point p, JList<?> list) {
        if (list == null) return false;
        int idx = list.locationToIndex(p);
        if (idx < 0 || idx >= list.getModel().getSize()) return false;
        Object v = list.getModel().getElementAt(idx);
        if (!(v instanceof DatabaseComboItem) || ((DatabaseComboItem) v).isAddItem()) return false;
        int rowW = list.getWidth();
        if (rowW <= 0) return false;
        int fixedH = list.getFixedCellHeight();
        if (fixedH <= 0) return false;
        int topPad = list.getInsets().top;
        int rowY = topPad + idx * fixedH;
        int ex = editIconX(rowW);
        int ey = rowY + (fixedH - EDIT_ICON_SIZE) / 2;
        return p.x >= ex - HIT_PAD && p.x <= ex + EDIT_ICON_SIZE + HIT_PAD
                && p.y >= ey - HIT_PAD && p.y <= ey + EDIT_ICON_SIZE + HIT_PAD;
    }

    private void calcHover(JList<?> list, int index) {
        rowHover = false;
        editHover = false;
        if (list == null || index < 0) return;
        Object mpObj = list.getClientProperty(MOUSE_POINT_KEY);
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
        if (isAdd) return;

        int w = list.getWidth() - list.getInsets().left - list.getInsets().right;
        int ex = editIconX(w);
        int ey = (fixedH - EDIT_ICON_SIZE) / 2;
        int rx = mp.x - list.getInsets().left;
        int ry = mp.y - topPad - mouseRow * fixedH;
        if (rx >= ex - HIT_PAD && rx <= ex + EDIT_ICON_SIZE + HIT_PAD
                && ry >= ey - HIT_PAD && ry <= ey + EDIT_ICON_SIZE + HIT_PAD) {
            editHover = true;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        int w = getWidth(), h = getHeight();

        if (selected) {
            g2.setColor(ComboStyle.selectionColor());
            g2.fillRect(0, 0, w, h);
        } else if (rowHover) {
            g2.setColor(ComboStyle.hoverColor());
            g2.fillRect(0, 0, w, h);
        }

        int px = ComboStyle.itemPaddingX();
        int iconSize = ComboStyle.iconSize();
        String text = (value == null) ? "" : value.name;

        if (isAdd) {
            g2.setColor(dividerColor());
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawLine(px, 1, w - px, 1);

            int addSize = 16;
            int addY = (h - addSize) / 2;
            addIcon.paintIcon(this, g2, px, addY);

            g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
            g2.setColor(ComboStyle.textSecondary());
            FontMetrics fm = g2.getFontMetrics();
            int textX = px + addSize + 6;
            String visible = ComboStyle.clipTextIfNeeded(text, fm, (w - px) - textX);
            int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(visible, textX, textY);
        } else {
            int iconY = (h - iconSize) / 2;
            int mx = px + (iconSize - dbIcon.getIconWidth()) / 2;
            int my = iconY + (iconSize - dbIcon.getIconHeight()) / 2;
            dbIcon.paintIcon(this, g2, mx, my);

            g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
            g2.setColor(selected ? Color.WHITE : ComboStyle.textPrimary());
            FontMetrics fm = g2.getFontMetrics();
            int textX = px + iconSize + 10;
            int editReserved = rowHover ? (px + EDIT_ICON_SIZE + 12) : px;
            String visible = ComboStyle.clipTextIfNeeded(text, fm, (w - editReserved) - textX);
            int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(visible, textX, textY);

            if (rowHover) {
                int ex = editIconX(w);
                int ey = (h - EDIT_ICON_SIZE) / 2;
                if (editHover) {
                    GraphicsUtil.setupAAPainting(g2);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    int inset = 2;
                    int cardSize = EDIT_ICON_SIZE + inset * 2;
                    g2.setColor(JBColor.isBright() ? new Color(0, 0, 0, 45) : new Color(255, 255, 255, 50));
                    g2.fillRoundRect(ex - inset, ey - inset, cardSize, cardSize, 6, 6);
                }
                editIcon.paintIcon(this, g2, ex, ey);
            }
        }
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        Font font = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
        FontMetrics fm = getFontMetrics(font);
        int textW = fm.stringWidth((value == null) ? "" : value.name);
        int px = ComboStyle.itemPaddingX();
        int iconSize = ComboStyle.iconSize();
        int w = isAdd
                ? (px + 16 + 6 + textW + px)
                : (px + iconSize + 10 + textW + px + EDIT_ICON_SIZE + 12);
        return new Dimension(w, ComboStyle.rowHeight());
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends DatabaseComboItem> list,
                                                   DatabaseComboItem val, int index,
                                                   boolean isSelected, boolean cellHasFocus) {
        this.value = val;
        this.isAdd = (val != null) && val.isAddItem();
        this.selected = isSelected;
        calcHover(list, index);
        return this;
    }

    private static Color dividerColor() {
        return JBColor.namedColor("Separator.foreground",
                new JBColor(new Color(0, 0, 0, 30), new Color(255, 255, 255, 20)));
    }
}
