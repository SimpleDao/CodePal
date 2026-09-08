package com.codepal.ui;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.codepal.model.DatabaseComboItem;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Consumer;

/**
 * 数据源下拉框。外观、交互、hover/选中风格与模型下拉框（ModelComboBox + ModelComboRenderer）完全一致：
 * 扁平灰色选中/淡灰 hover、右侧编辑笔（hover 浮现）、底部「＋ 配置数据源」占位项。
 * 双击行 = 编辑，右键行 = 编辑/删除菜单，点击底部项 = 新增。
 */
public class DatabaseComboBox extends ComboBox<DatabaseComboItem> {

    /** 下拉交互回调，由 ChatPanel 注入（编辑/删除/新增细节依赖插件窗口与 DAO） */
    public interface Callbacks {
        void onAdd();

        void onEdit(String id);

        void onDelete(String id);
    }

    private final Callbacks callbacks;

    public DatabaseComboBox(DatabaseComboItem[] items, Callbacks callbacks) {
        super(items);
        this.callbacks = callbacks;
        ComboStyle.apply(this);
        setRenderer(new DatabaseComboRenderer());
        // hover 鼠标点追踪（驱动渲染器编辑笔命中）
        attachHoverTracking(this);
        // 双击/右键编辑删除
        attachListActions(this);
    }

    @Override
    public void setSelectedItem(Object anObject) {
        if (anObject instanceof DatabaseComboItem && ((DatabaseComboItem) anObject).isAddItem()) {
            if (isPopupVisible()) setPopupVisible(false);
            if (callbacks != null) {
                SwingUtilities.invokeLater(callbacks::onAdd);
            }
            return;
        }
        super.setSelectedItem(anObject);
    }

    // ─────────────────────────────────────────────
    //  渲染器（仿 ModelComboRenderer，纯绘制 + 自查询鼠标位置，避免状态同步）
    // ─────────────────────────────────────────────
    private class DatabaseComboRenderer extends JComponent implements ListCellRenderer<DatabaseComboItem> {
        private final Icon dbIcon, addIcon, editIcon;
        private boolean isAdd = false;
        private boolean isPlaceholder = false;
        private boolean isSelectedItem = false;
        private int currentRow = -1;
        private String text = "数据源";
        private boolean rowHover = false;
        private boolean editHover = false;
        private DatabaseComboItem itemValue = null;
        private boolean renderingAsList = false;

        DatabaseComboRenderer() {
            this.dbIcon = ComboStyle.databaseIcon();
            this.addIcon = ComboStyle.addDataSourceIcon();
            this.editIcon = ComboStyle.editDataSourceIcon();
            setOpaque(false);
            setBorder(null);
        }

        private void calcHover(JList<?> list, int index) {
            rowHover = false;
            editHover = false;
            if (list == null) return;
            Object mpObj = list.getClientProperty("CP.dbMousePoint");
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

            int w = list.getWidth() - list.getInsets().left - list.getInsets().right;
            int px = ComboStyle.itemPaddingX();
            int editSize = 16;
            int editX = w - px - editSize;
            int editY = (fixedH - editSize) / 2;
            int pad = 6;
            int rx = mp.x - list.getInsets().left;
            int ry = mp.y - topPad - mouseRow * fixedH;
            if (isAdd || isPlaceholder) return;
            if (rx >= editX - pad && rx <= editX + editSize + pad
                    && ry >= editY - pad && ry <= editY + editSize + pad) {
                editHover = true;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            int w = getWidth(), h = getHeight();

            boolean inList = renderingAsList;

            if (inList) {
                if (isSelectedItem) {
                    g2.setColor(ComboStyle.selectionColor());
                    g2.fillRect(0, 0, w, h);
                } else if (rowHover) {
                    g2.setColor(ComboStyle.hoverColor());
                    g2.fillRect(0, 0, w, h);
                }

                int px = ComboStyle.itemPaddingX();
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = px;

                if (isAdd) {
                    g2.setColor(dividerColor());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawLine(px, 1, w - px, 1);

                    int addSize = 16;
                    int addY = (h - addSize) / 2;
                    addIcon.paintIcon(this, g2, iconX, addY);

                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(ComboStyle.textSecondary());
                    FontMetrics fm = g2.getFontMetrics();
                    int textX = iconX + addSize + 6;
                    int textRightBound = (w - px);
                    String addVisible = ComboStyle.clipTextIfNeeded(text, fm, textRightBound - textX);
                    int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(addVisible, textX, textY);
                } else if (isPlaceholder) {
                    // 占位项在列表态下：整行刷成弹层底色，让占位行在视觉中"消失"（避免出现白线）。
                    // 注意：占用一格 rowHeight 是 JComboBox.BasicComboPopup 受 fixedCellHeight 锁死的固有结果，
                    // 此处无法压缩高度；只能靠同色 fill 让用户感知不到这格的存在。
                    g2.setColor(ComboStyle.popupSurfaceColor());
                    g2.fillRect(0, 0, w, h);
                } else {
                    int mx = iconX + (iconSize - dbIcon.getIconWidth()) / 2;
                    int my = iconY + (iconSize - dbIcon.getIconHeight()) / 2;
                    dbIcon.paintIcon(this, g2, mx, my);

                    g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                    g2.setColor(isSelectedItem ? Color.WHITE : ComboStyle.textPrimary());
                    FontMetrics fm = g2.getFontMetrics();
                    int textX = iconX + iconSize + 10;
                    int editReserved = (rowHover ? (px + 16 + 12) : px);
                    int textRightBound = w - editReserved;
                    String visible = ComboStyle.clipTextIfNeeded(text, fm, textRightBound - textX);
                    int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString(visible, textX, textY);

                    if (rowHover) {
                        int editSize = 16;
                        int editX = w - px - editSize;
                        int editY = (h - editSize) / 2;
                        if (editHover) {
                            GraphicsUtil.setupAAPainting(g2);
                            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                            int r = 6;
                            int inset = 2;
                            int cardSize = editSize + inset * 2;
                            int sx = editX - inset;
                            int sy = editY - inset;
                            boolean bright = JBColor.isBright();
                            g2.setColor(bright ? new Color(0, 0, 0, 45) : new Color(255, 255, 255, 50));
                            g2.fillRoundRect(sx, sy, cardSize, cardSize, r, r);
                        }
                        editIcon.paintIcon(this, g2, editX, editY);
                    }
                }
                g2.dispose();
            } else {
                // 收起态
                int iconSize = ComboStyle.iconSize();
                int iconY = (h - iconSize) / 2;
                int iconX = 8;
                int mx = iconX + (iconSize - dbIcon.getIconWidth()) / 2;
                int my = iconY + (iconSize - dbIcon.getIconHeight()) / 2;
                dbIcon.paintIcon(this, g2, mx, my);

                g2.setFont(JBUI.Fonts.label(13).deriveFont(Font.PLAIN));
                g2.setColor(UIUtil.getLabelForeground());
                FontMetrics fm = g2.getFontMetrics();
                int textX = iconX + iconSize + 6;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(text, textX, textY);
                g2.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Font font = JBUI.Fonts.label(13).deriveFont(Font.PLAIN);
            FontMetrics fm = getFontMetrics(font);
            int textW = fm.stringWidth(text);
            int px = ComboStyle.itemPaddingX();
            int iconSize = ComboStyle.iconSize();
            int addSize = 16;
            int gap = 10;
            int w;
            if (currentRow == -1) {
                w = 14 + iconSize + gap + textW + 30;
            } else if (isAdd) {
                w = px + addSize + 6 + textW + px;
            } else {
                w = px + iconSize + gap + textW + px + 16 + 12;
            }
            return new Dimension(w, ComboStyle.rowHeight());
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DatabaseComboItem> list, DatabaseComboItem value,
                                                       int index, boolean isSelected, boolean cellHasFocus) {
            // 收起态（index=-1）或 value 为 null → 占位文案「数据源」；
            // 收起态（index=-1）渲染 "数据源" 占位文案；列表态走正常 name。
            // 收起态下 selectedItem 是 placeholder 实例（model 第 0 位），name 同样是 "数据源"。
            text = (value == null) ? "数据源" : value.name;
            isAdd = value != null && value.isAddItem();
            isPlaceholder = value != null && value.isPlaceholder();
            isSelectedItem = isSelected;
            currentRow = index;
            itemValue = value;
            renderingAsList = (index >= 0);
            calcHover(list, index);
            return this;
        }
    }

    // ─────────────────────────────────────────────
    //  hover / 动作追踪（逻辑照搬模型下拉，clientProperty key 区分）
    // ─────────────────────────────────────────────
    private static JList<?> getComboList(JComboBox<?> combo) {
        Object child = combo.getUI().getAccessibleChild(combo, 0);
        if (child instanceof ComboPopup) {
            return ((ComboPopup) child).getList();
        }
        return null;
    }

    private void attachHoverTracking(JComboBox<?> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                JList<?> list = getComboList(combo);
                if (list == null) return;
                if (Boolean.TRUE.equals(list.getClientProperty("CP.dbHoverBound"))) return;
                list.putClientProperty("CP.dbHoverBound", Boolean.TRUE);
                list.putClientProperty("CP.dbMousePoint", null);
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent me) {
                        list.putClientProperty("CP.dbMousePoint", me.getPoint());
                        list.repaint();
                    }
                });
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseExited(MouseEvent me) {
                        list.putClientProperty("CP.dbMousePoint", null);
                        list.repaint();
                    }
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    private void attachListActions(JComboBox<?> combo) {
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                JList<?> list = getComboList(combo);
                if (list == null) return;
                if (Boolean.TRUE.equals(list.getClientProperty("CP.dbListBound"))) return;
                list.putClientProperty("CP.dbListBound", Boolean.TRUE);
                list.putClientProperty("CP.dbMousePoint", null);
                list.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent me) {
                        list.putClientProperty("CP.dbMousePoint", me.getPoint());
                        int row = list.locationToIndex(me.getPoint());
                        Object v = (row >= 0) ? list.getModel().getElementAt(row) : null;
                        boolean hand = v instanceof DatabaseComboItem && !((DatabaseComboItem) v).isAddItem();
                        list.setCursor(hand ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                : Cursor.getDefaultCursor());
                        list.repaint();
                    }
                });
                list.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent me) {
                        if (me.isPopupTrigger()) {
                            showRowMenu(me, list);
                            return;
                        }
                        if (!SwingUtilities.isLeftMouseButton(me)) return;
                        int row = list.locationToIndex(me.getPoint());
                        if (row < 0) return;
                        Object v = list.getModel().getElementAt(row);
                        if (!(v instanceof DatabaseComboItem) || ((DatabaseComboItem) v).isAddItem()
                                || ((DatabaseComboItem) v).isPlaceholder()) return;
                        DatabaseComboItem item = (DatabaseComboItem) v;

                        int px = ComboStyle.itemPaddingX();
                        int editSize = 16;
                        int pad = 6;
                        int contentW = list.getWidth() - list.getInsets().left - list.getInsets().right;
                        int editX = contentW - px - editSize;
                        int fixedH = list.getFixedCellHeight();
                        int topPad = list.getInsets().top;
                        int editY = (fixedH - editSize) / 2;
                        int rx = me.getPoint().x - list.getInsets().left;
                        int ry = me.getPoint().y - topPad - row * fixedH;
                        boolean onPen = rx >= editX - pad && rx <= editX + editSize + pad
                                && ry >= editY - pad && ry <= editY + editSize + pad;

                        if (onPen) {
                            me.consume();
                            combo.setPopupVisible(false);
                            final String id = item.id;
                            SwingUtilities.invokeLater(() -> callbacks.onEdit(id));
                            return;
                        }
                        if (me.getClickCount() == 2) {
                            me.consume();
                            combo.setPopupVisible(false);
                            final String id = item.id;
                            SwingUtilities.invokeLater(() -> callbacks.onEdit(id));
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent me) {
                        if (me.isPopupTrigger()) showRowMenu(me, list);
                    }

                    @Override
                    public void mouseExited(MouseEvent me) {
                        list.putClientProperty("CP.dbMousePoint", null);
                        list.repaint();
                    }
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
    }

    private void showRowMenu(MouseEvent me, JList<?> list) {
        int row = list.locationToIndex(me.getPoint());
        if (row < 0) return;
        Object v = list.getModel().getElementAt(row);
        if (!(v instanceof DatabaseComboItem) || ((DatabaseComboItem) v).isAddItem()) return;
        DatabaseComboItem item = (DatabaseComboItem) v;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("编辑");
        edit.addActionListener(a -> callbacks.onEdit(item.id));
        JMenuItem del = new JMenuItem("删除");
        del.addActionListener(a -> callbacks.onDelete(item.id));
        menu.add(edit);
        menu.add(del);
        menu.show(list, me.getX(), me.getY());
    }

    private static Color dividerColor() {
        return JBColor.namedColor("Separator.foreground",
                new JBColor(new Color(0, 0, 0, 30), new Color(255, 255, 255, 20)));
    }
}
