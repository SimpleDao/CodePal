package com.codepal.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.diff.DiffContentFactoryEx;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.editor.DiffEditorTabFilesManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 文件变更列表面板 —— 对标图2的现代化设计
 * - 单行紧凑布局：下载箭头⬇ + 文件名 + 灰色路径 + 右侧(+N -N) + 状态圆点
 * - PENDING状态：hover显示保留/撤销小按钮
 * - 分组标题行：N个文件 + 保留/撤销/查看变更按钮
 */
public class FileChangeListPanel {

    public enum FileState { PENDING, ACCEPTED, REJECTED }

    public enum OpType { EDIT, WRITE, APPEND }

    public static class FileChange {
        public String filePath;
        public String originalContent;
        public String newContent;
        public int addedLines;
        public int removedLines;
        public FileState state = FileState.PENDING;
        public OpType op = OpType.EDIT;
        public boolean isNewFile = false;

        public FileChange(String filePath, String originalContent, String newContent) {
            this(filePath, originalContent, newContent, false);
        }

        public FileChange(String filePath, String originalContent, String newContent, boolean isNewFile) {
            this.filePath = filePath;
            this.originalContent = originalContent;
            this.newContent = newContent;
            this.isNewFile = isNewFile;
            computeStats();
            detectOpType();
        }

        public void updateContent(String newContent) {
            this.newContent = newContent;
            computeStats();
            detectOpType();
        }

        private void detectOpType() {
            if (isNewFile || originalContent == null || originalContent.isEmpty()) {
                op = OpType.WRITE;
            } else if (removedLines == 0 && addedLines > 0) {
                op = OpType.APPEND;
            } else {
                op = OpType.EDIT;
            }
        }

        private void computeStats() {
            String[] oldLines = originalContent != null ? originalContent.split("\n", -1) : new String[0];
            String[] newLines = newContent != null ? newContent.split("\n", -1) : new String[0];
            addedLines = 0;
            removedLines = 0;
            int minLen = Math.min(oldLines.length, newLines.length);
            for (int i = 0; i < minLen; i++) {
                if (!oldLines[i].equals(newLines[i])) { removedLines++; addedLines++; }
            }
            if (oldLines.length > newLines.length) removedLines += oldLines.length - newLines.length;
            else if (newLines.length > oldLines.length) addedLines += newLines.length - oldLines.length;
        }

        public String getFileName() {
            int sep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            return sep >= 0 ? filePath.substring(sep + 1) : filePath;
        }

        public String getDirPath() {
            String name = getFileName();
            int sep = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (sep < 0) return "";
            String dir = filePath.substring(0, sep);
            dir = dir.replace('\\', '/');
            int maxLen = 25;
            if (dir.length() <= maxLen) return dir;
            return "..." + dir.substring(dir.length() - maxLen);
        }

        public Icon getFileIcon() {
            String name = getFileName().toLowerCase();
            if (name.endsWith(".java")) return AllIcons.FileTypes.Java;
            if (name.endsWith(".xml")) return AllIcons.FileTypes.Xml;
            if (name.endsWith(".json")) return AllIcons.FileTypes.Json;
            if (name.endsWith(".js") || name.endsWith(".ts")) return AllIcons.FileTypes.JavaScript;
            if (name.endsWith(".html")) return AllIcons.FileTypes.Html;
            if (name.endsWith(".css")) return AllIcons.FileTypes.Css;
            if (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"))
                return AllIcons.FileTypes.Properties;
            return AllIcons.FileTypes.Text;
        }
    }

    private static class RowRef {
        final FileChange change;
        final JPanel row;
        final ActionIcon acceptBtn;
        final ActionIcon rejectBtn;
        final ActionIcon diffBtn;
        final JBLabel addStatLabel;
        final JBLabel delStatLabel;
        final StatusDot statusDot;
        final JBLabel nameLabel;
        final JBLabel dirLabel;
        final DownloadIcon downloadIcon;

        RowRef(FileChange change, JPanel row, ActionIcon acceptBtn, ActionIcon rejectBtn,
               ActionIcon diffBtn, JBLabel addStatLabel, JBLabel delStatLabel,
               StatusDot statusDot, JBLabel nameLabel, JBLabel dirLabel, DownloadIcon downloadIcon) {
            this.change = change;
            this.row = row;
            this.acceptBtn = acceptBtn;
            this.rejectBtn = rejectBtn;
            this.diffBtn = diffBtn;
            this.addStatLabel = addStatLabel;
            this.delStatLabel = delStatLabel;
            this.statusDot = statusDot;
            this.nameLabel = nameLabel;
            this.dirLabel = dirLabel;
            this.downloadIcon = downloadIcon;
        }
    }

    /** 下载箭头图标（左侧状态指示） */
    private static class DownloadIcon extends JComponent {
        private FileState state = FileState.PENDING;
        private static final int SIZE = 14;

        private static final Color COLOR_PENDING = new JBColor(new Color(0x3B82F6), new Color(0x60A5FA));
        private static final Color COLOR_ACCEPTED = new JBColor(new Color(0x10B981), new Color(0x34D399));
        private static final Color COLOR_REJECTED = new JBColor(new Color(0xEF4444), new Color(0xF87171));

        DownloadIcon() {
            setPreferredSize(new Dimension(JBUI.scale(16), JBUI.scale(16)));
            setOpaque(false);
        }

        void setState(FileState state) { this.state = state; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float s = JBUI.scale(1f);
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            Color color;
            switch (state) {
                case ACCEPTED: color = COLOR_ACCEPTED; break;
                case REJECTED: color = COLOR_REJECTED; break;
                default: color = COLOR_PENDING; break;
            }
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (state == FileState.ACCEPTED) {
                g2.drawLine(cx - (int)(4*s), cy, cx - (int)(1*s), cy + (int)(3*s));
                g2.drawLine(cx - (int)(1*s), cy + (int)(3*s), cx + (int)(4*s), cy - (int)(3*s));
            } else if (state == FileState.REJECTED) {
                g2.drawLine(cx - (int)(4*s), cy - (int)(4*s), cx + (int)(4*s), cy + (int)(4*s));
                g2.drawLine(cx + (int)(4*s), cy - (int)(4*s), cx - (int)(4*s), cy + (int)(4*s));
            } else {
                int ay = cy - (int)(4*s);
                int by = cy + (int)(3*s);
                g2.drawLine(cx, ay, cx, by - (int)(1*s));
                g2.drawLine(cx - (int)(3*s), cy, cx, by);
                g2.drawLine(cx + (int)(3*s), cy, cx, by);
                g2.drawLine(cx - (int)(4*s), by, cx + (int)(4*s), by);
            }
            g2.dispose();
        }
    }

    /** 右侧状态圆点 */
    private static class StatusDot extends JComponent {
        private FileState state = FileState.PENDING;
        private static final int SIZE = 6;

        private static final Color COLOR_PENDING = new JBColor(new Color(0x10B981), new Color(0x34D399));
        private static final Color COLOR_DONE = new JBColor(new Color(0xD1D5DB), new Color(0x4B5563));

        StatusDot() {
            setPreferredSize(new Dimension(JBUI.scale(10), JBUI.scale(10)));
            setOpaque(false);
        }

        void setState(FileState state) { this.state = state; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float s = JBUI.scale(SIZE) / 2f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            g2.setColor(state == FileState.PENDING ? COLOR_PENDING : COLOR_DONE);
            g2.fill(new Ellipse2D.Float(cx - s, cy - s, s * 2, s * 2));
            g2.dispose();
        }
    }

    /** 下载箭头图标（左侧状态指示） */
    private static class ActionIcon extends JComponent {
        static final int TYPE_ACCEPT = 0;
        static final int TYPE_REJECT = 1;
        static final int TYPE_DIFF = 2;

        private boolean hovered = false;
        private boolean visible = false;
        private final int iconType;
        private static final int SIZE = 16;

        private static final Color ACCEPT_BG = new JBColor(new Color(0xDBEAFE), new Color(0x1E3A5F));
        private static final Color ACCEPT_FG = new JBColor(new Color(0x2563EB), new Color(0x60A5FA));
        private static final Color REJECT_BG = new JBColor(new Color(0xFEE2E2), new Color(0x4C0519));
        private static final Color REJECT_FG = new JBColor(new Color(0xDC2626), new Color(0xF87171));
        private static final Color DIFF_BG = new JBColor(new Color(0xF3F4F6), new Color(0x374151));
        private static final Color DIFF_FG = new JBColor(new Color(0x6B7280), new Color(0x9CA3AF));

        ActionIcon(int iconType) {
            this.iconType = iconType;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setVisible(false);
            if (iconType == TYPE_DIFF) {
                setToolTipText("查看变更");
            } else if (iconType == TYPE_ACCEPT) {
                setToolTipText("保留此改动");
            } else {
                setToolTipText("撤销此改动");
            }
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            if (!isVisible()) return new Dimension(0, 0);
            return new Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE));
        }

        @Override
        public Dimension getMinimumSize() {
            if (!isVisible()) return new Dimension(0, 0);
            return new Dimension(JBUI.scale(SIZE), JBUI.scale(SIZE));
        }

        void showBtn() { visible = true; setVisible(true); revalidate(); repaint(); }
        void hideBtn() { visible = false; setVisible(false); revalidate(); repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            if (!visible) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = JBUI.scale(SIZE);
            float scale = s / 20f;
            Color bg, fg;
            if (iconType == TYPE_ACCEPT) {
                bg = hovered ? ACCEPT_BG : null;
                fg = ACCEPT_FG;
            } else if (iconType == TYPE_REJECT) {
                bg = hovered ? REJECT_BG : null;
                fg = REJECT_FG;
            } else {
                bg = hovered ? DIFF_BG : null;
                fg = DIFF_FG;
            }
            if (bg != null) {
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, s - 1, s - 1, JBUI.scale(3), JBUI.scale(3)));
            }
            g2.setColor(fg);
            int cx = s / 2, cy = s / 2;
            if (iconType == TYPE_ACCEPT) {
                g2.setStroke(new BasicStroke(1.6f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - (int)(3*scale), cy, cx - (int)(0*scale), cy + (int)(3*scale));
                g2.drawLine(cx - (int)(0*scale), cy + (int)(3*scale), cx + (int)(3*scale), cy - (int)(3*scale));
            } else if (iconType == TYPE_REJECT) {
                g2.setStroke(new BasicStroke(1.6f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - (int)(3*scale), cy - (int)(3*scale), cx + (int)(3*scale), cy + (int)(3*scale));
                g2.drawLine(cx + (int)(3*scale), cy - (int)(3*scale), cx - (int)(3*scale), cy + (int)(3*scale));
            } else {
                g2.setStroke(new BasicStroke(1.3f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float r = 1.5f * scale;
                int docX = cx - (int)(4*scale);
                int docY = cy - (int)(5*scale);
                int docW = (int)(8*scale);
                int docH = (int)(10*scale);
                g2.draw(new RoundRectangle2D.Float(docX, docY, docW, docH, r, r));
                int lineY1 = docY + (int)(2*scale);
                int lineY2 = docY + (int)(5*scale);
                int lineX1 = docX + (int)(2*scale);
                int lineX2 = docX + docW - (int)(2*scale);
                g2.drawLine(lineX1, lineY1, lineX2, lineY1);
                g2.drawLine(lineX1, lineY2, (lineX1 + lineX2) / 2, lineY2);
            }
            g2.dispose();
        }
    }

    private final Project project;
    private final List<RowRef> rows = new ArrayList<>();
    private final BiConsumer<String, String> onFileAccepted;
    private final Consumer<String> onFileRejected;
    private final Consumer<String> onFileRemoved;
    private final Runnable onAllResolved;
    private final Runnable onLayoutChanged;

    private JPanel mainPanel;
    private JPanel listPanel;
    private JScrollPane listScrollPane;
    private boolean allPendingResolved = false;
    private final Set<String> openDiffFiles = new HashSet<>();

    // ── 颜色 ──
    private static final Color ROW_HOVER = new JBColor(
            new Color(0xF8FAFC), new Color(0x363A42)
    );
    private static final Color NAME_TEXT = new JBColor(
            new Color(0x1F2937), new Color(0xE5E7EB)
    );
    private static final Color DIR_TEXT = new JBColor(
            new Color(0x9CA3AF), new Color(0x6B7280)
    );
    private static final Color DIM_TEXT = new JBColor(
            new Color(0x9CA3AF), new Color(0x6B7280)
    );
    private static final Color SEPARATOR = new JBColor(
            new Color(0xF3F4F6), new Color(0x3E4145)
    );
    private static final Color HEADER_BG = new JBColor(
            new Color(0xF9FAFB), new Color(0x2B2D30)
    );

    // ── 构造函数 ──

    public FileChangeListPanel(@NotNull Project project,
                               @Nullable BiConsumer<String, String> onFileAccepted,
                               @Nullable Consumer<String> onFileRejected,
                               @Nullable Consumer<String> onFileRemoved,
                               @Nullable Runnable onAllResolved,
                               @Nullable Runnable onLayoutChanged) {
        this.project = project;
        this.onFileAccepted = onFileAccepted;
        this.onFileRejected = onFileRejected;
        this.onFileRemoved = onFileRemoved;
        this.onAllResolved = onAllResolved;
        this.onLayoutChanged = onLayoutChanged;
        initPanel();
    }

    private void fireLayoutChanged() {
        mainPanel.revalidate();
        mainPanel.repaint();
        if (onLayoutChanged != null) onLayoutChanged.run();
    }

    private void initPanel() {
        mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setOpaque(false);

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        listPanel.setBorder(JBUI.Borders.empty(JBUI.scale(1), 0, JBUI.scale(2), 0));

        listScrollPane = new JScrollPane(listPanel);
        listScrollPane.setBorder(JBUI.Borders.empty());
        listScrollPane.setOpaque(false);
        listScrollPane.getViewport().setOpaque(false);
        listScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        listScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScrollPane.getVerticalScrollBar().setUnitIncrement(JBUI.scale(30));

        // 初始最小高度（2行）
        listScrollPane.setPreferredSize(new Dimension(Short.MAX_VALUE, JBUI.scale(66)));

        mainPanel.add(listScrollPane, BorderLayout.CENTER);
    }

    public void addChange(String filePath, String originalContent, String newContent) {
        upsertChange(filePath, originalContent, newContent);
    }

    public void upsertChange(String filePath, String originalContent, String newContent) {
        upsertChange(filePath, originalContent, newContent, false);
    }

    public void upsertChange(String filePath, String originalContent, String newContent, boolean isNewFile) {
        System.out.println("[DiffDebug] FileChangeListPanel.upsertChange: " + filePath +
            " rows.size=" + rows.size() + " originalLen=" + (originalContent != null ? originalContent.length() : -1) +
            " newLen=" + (newContent != null ? newContent.length() : -1) + " isNewFile=" + isNewFile);
        RowRef existing = findRowByPath(filePath);
        if (existing != null && existing.change.state == FileState.PENDING) {
            existing.change.isNewFile = isNewFile || existing.change.isNewFile;
            existing.change.updateContent(newContent);
            existing.addStatLabel.setText("+" + existing.change.addedLines);
            existing.delStatLabel.setText("-" + existing.change.removedLines);
            existing.downloadIcon.setState(FileState.PENDING);
            existing.statusDot.setState(FileState.PENDING);
            existing.nameLabel.setForeground(NAME_TEXT);
            existing.dirLabel.setForeground(DIR_TEXT);
        } else if (existing != null && existing.change.state != FileState.PENDING) {
            // 仅当该文件内容确实发生变化（模型再次修改它）时才重置回待处理；
            // 否则只是因其它文件被编辑而重绘累积面板，必须保留用户已做的「保留/撤销」决定。
            boolean contentChanged = !Objects.equals(existing.change.newContent, newContent)
                    || !Objects.equals(existing.change.originalContent, originalContent)
                    || existing.change.isNewFile != isNewFile;
            if (contentChanged) {
                existing.change.originalContent = originalContent;
                existing.change.isNewFile = isNewFile;
                existing.change.updateContent(newContent);
                existing.change.state = FileState.PENDING;
                resetRowToPending(existing);
            }
            // content 不变：保持已保留/已撤销状态，不重置（避免其它文件编辑时误把已处理文件打回待处理）
        } else {
            FileChange change = new FileChange(filePath, originalContent, newContent, isNewFile);
            addChangeRow(change);
        }
        allPendingResolved = false;
        adjustSize();
        fireLayoutChanged();
        System.out.println("[DiffDebug] FileChangeListPanel.upsertChange done: rows.size=" + rows.size() +
            " mainPanel.size=" + mainPanel.getSize() + " preferredSize=" + mainPanel.getPreferredSize() +
            " listScrollPane.preferredSize=" + listScrollPane.getPreferredSize());
    }

    @Nullable
    private RowRef findRowByPath(String filePath) {
        for (RowRef ref : rows) {
            if (ref.change.filePath.equals(filePath)) return ref;
        }
        return null;
    }

    private void resetRowToPending(RowRef ref) {
        ref.downloadIcon.setState(FileState.PENDING);
        ref.statusDot.setState(FileState.PENDING);
        ref.acceptBtn.hideBtn();
        ref.rejectBtn.hideBtn();
        ref.diffBtn.hideBtn();
        ref.addStatLabel.setVisible(true);
        ref.delStatLabel.setVisible(true);
        ref.statusDot.setVisible(true);
        ref.nameLabel.setForeground(NAME_TEXT);
        ref.dirLabel.setForeground(DIR_TEXT);
        ref.row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ref.row.repaint();
    }

    public void addChangeFromJson(String jsonData) {
        try {
            JsonObject obj = JsonParser.parseString(jsonData).getAsJsonObject();
            addChange(obj.get("file_path").getAsString(), obj.get("original").getAsString(), obj.get("new").getAsString());
        } catch (Exception e) {
            System.err.println("[FileChangeListPanel] 解析失败: " + e.getMessage());
        }
    }

    private void addChangeRow(FileChange change) {
        final boolean[] hovered = {false};
        int rowH = JBUI.scale(30);

        JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.max(d.width, 0), rowH);
            }
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Short.MAX_VALUE, rowH);
            }
            @Override
            public Dimension getMinimumSize() {
                Dimension d = super.getMinimumSize();
                return new Dimension(0, rowH);
            }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (hovered[0]) {
                    g2.setColor(ROW_HOVER);
                    g2.fillRect(0, 0, w, h - 1);
                }
                g2.setColor(SEPARATOR);
                g2.drawLine(JBUI.scale(8), h - 1, w - JBUI.scale(8), h - 1);
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(0, JBUI.scale(8), 0, JBUI.scale(8)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) baseFont = new Font(Font.DIALOG, Font.PLAIN, 12);

        // WEST: 状态图标（VerticalBox+Glue 强制垂直居中）
        DownloadIcon downloadIcon = new DownloadIcon();
        downloadIcon.setAlignmentY(Component.CENTER_ALIGNMENT);
        Box iconBox = Box.createVerticalBox();
        iconBox.setOpaque(false);
        iconBox.add(Box.createVerticalGlue());
        iconBox.add(downloadIcon);
        iconBox.add(Box.createVerticalGlue());
        row.add(iconBox, BorderLayout.WEST);

        // CENTER: 文件名 + 路径（单行紧凑）
        Box centerBox = Box.createHorizontalBox();
        centerBox.setOpaque(false);

        JBLabel nameLabel = new JBLabel(change.getFileName());
        nameLabel.setFont(baseFont.deriveFont(Font.PLAIN, 12f));
        nameLabel.setForeground(NAME_TEXT);
        nameLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        centerBox.add(nameLabel);
        centerBox.add(Box.createHorizontalStrut(JBUI.scale(6)));

        JBLabel dirLabel = new JBLabel(change.getDirPath());
        dirLabel.setFont(baseFont.deriveFont(Font.PLAIN, 10.5f));
        dirLabel.setForeground(DIR_TEXT);
        dirLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        centerBox.add(dirLabel);

        centerBox.add(Box.createHorizontalGlue());

        row.add(centerBox, BorderLayout.CENTER);

        // EAST: 统计 + 按钮 + 圆点（外层VerticalBox+Glue垂直居中，内层HorizontalBox水平排列）
        Box eastOuter = Box.createVerticalBox();
        eastOuter.setOpaque(false);
        eastOuter.add(Box.createVerticalGlue());

        Box eastContent = Box.createHorizontalBox();
        eastContent.setOpaque(false);

        JBLabel addStatLabel = new JBLabel("+" + change.addedLines);
        addStatLabel.setFont(baseFont.deriveFont(Font.PLAIN, 10.5f));
        addStatLabel.setForeground(new JBColor(new Color(0x059669), new Color(0x34D399)));
        addStatLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(addStatLabel);
        eastContent.add(Box.createHorizontalStrut(JBUI.scale(3)));

        JBLabel delStatLabel = new JBLabel("-" + change.removedLines);
        delStatLabel.setFont(baseFont.deriveFont(Font.PLAIN, 10.5f));
        delStatLabel.setForeground(new JBColor(new Color(0xDC2626), new Color(0xF87171)));
        delStatLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(delStatLabel);
        eastContent.add(Box.createHorizontalStrut(JBUI.scale(4)));

        ActionIcon acceptBtn = new ActionIcon(ActionIcon.TYPE_ACCEPT);
        acceptBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(acceptBtn);
        eastContent.add(Box.createHorizontalStrut(JBUI.scale(2)));

        ActionIcon rejectBtn = new ActionIcon(ActionIcon.TYPE_REJECT);
        rejectBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(rejectBtn);
        eastContent.add(Box.createHorizontalStrut(JBUI.scale(2)));

        ActionIcon diffBtn = new ActionIcon(ActionIcon.TYPE_DIFF);
        diffBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(diffBtn);
        eastContent.add(Box.createHorizontalStrut(JBUI.scale(4)));

        StatusDot statusDot = new StatusDot();
        statusDot.setAlignmentY(Component.CENTER_ALIGNMENT);
        eastContent.add(statusDot);

        eastContent.setAlignmentX(Component.RIGHT_ALIGNMENT);
        eastOuter.add(eastContent);
        eastOuter.add(Box.createVerticalGlue());

        row.add(eastOuter, BorderLayout.EAST);

        RowRef ref = new RowRef(change, row, acceptBtn, rejectBtn, diffBtn,
                addStatLabel, delStatLabel, statusDot, nameLabel, dirLabel, downloadIcon);
        rows.add(ref);

        acceptBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { acceptRow(ref); }
        });
        rejectBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { rejectRow(ref); }
        });
        diffBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (change.state == FileState.PENDING && !allPendingResolved) {
                    openDiffForRow(ref);
                }
            }
        });

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Component deepest = SwingUtilities.getDeepestComponentAt(row, e.getX(), e.getY());
                if (deepest instanceof ActionIcon) return;
                if (e.getClickCount() == 2 && change.state == FileState.PENDING && !allPendingResolved) {
                    openDiffForRow(ref);
                }
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered[0] = true;
                if (ref.change.state == FileState.PENDING) {
                    ref.acceptBtn.showBtn();
                    ref.rejectBtn.showBtn();
                    ref.diffBtn.showBtn();
                    ref.addStatLabel.setVisible(false);
                    ref.delStatLabel.setVisible(false);
                    ref.statusDot.setVisible(false);
                }
                row.repaint();
            }
            @Override
            public void mouseExited(MouseEvent e) {
                hovered[0] = false;
                Point p = MouseInfo.getPointerInfo().getLocation();
                SwingUtilities.convertPointFromScreen(p, row);
                if (!row.contains(p)) {
                    ref.acceptBtn.hideBtn();
                    ref.rejectBtn.hideBtn();
                    ref.diffBtn.hideBtn();
                    ref.addStatLabel.setVisible(true);
                    ref.delStatLabel.setVisible(true);
                    ref.statusDot.setVisible(true);
                }
                row.repaint();
            }
        });

        if (change.state != FileState.PENDING) {
            paintRowResolved(ref, change.state == FileState.ACCEPTED);
        } else {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        listPanel.add(row);
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void acceptRow(RowRef ref) {
        if (ref.change.state != FileState.PENDING) return;
        ref.change.state = FileState.ACCEPTED;
        paintRowResolved(ref, true);
        closeDiffForFile(ref.change.filePath);
        openFileInEditor(ref.change.filePath);
        if (onFileAccepted != null) {
            onFileAccepted.accept(ref.change.filePath, ref.change.newContent);
        }
        checkAllPendingResolved();
    }

    private void rejectRow(RowRef ref) {
        if (ref.change.state != FileState.PENDING) return;
        ref.change.state = FileState.REJECTED;
        paintRowResolved(ref, false);
        closeDiffForFile(ref.change.filePath);
        if (onFileRejected != null) {
            onFileRejected.accept(ref.change.filePath);
        }
        checkAllPendingResolved();
    }

    private void paintRowResolved(RowRef ref, boolean accepted) {
        ref.acceptBtn.hideBtn();
        ref.rejectBtn.hideBtn();
        ref.diffBtn.hideBtn();
        ref.addStatLabel.setVisible(false);
        ref.delStatLabel.setVisible(false);
        ref.downloadIcon.setState(accepted ? FileState.ACCEPTED : FileState.REJECTED);
        ref.statusDot.setState(accepted ? FileState.ACCEPTED : FileState.REJECTED);
        ref.statusDot.setVisible(true);
        ref.nameLabel.setForeground(DIM_TEXT);
        ref.dirLabel.setForeground(DIM_TEXT);
        ref.row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ref.row.repaint();
    }

    private void closeDiffForFile(String filePath) {
        openDiffFiles.remove(filePath);
    }

    private void openDiffForRow(RowRef ref) {
        String filePath = ref.change.filePath;
        openDiffFiles.add(filePath);
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                DiffContentFactoryEx factory = DiffContentFactoryEx.getInstanceEx();
                DocumentContent leftContent = factory.create(project, ref.change.originalContent != null ? ref.change.originalContent : "");
                DocumentContent rightContent = factory.create(project, ref.change.newContent != null ? ref.change.newContent : "");
                SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                        "变更: " + ref.change.getFileName(), leftContent, rightContent, "原始", "AI 建议");
                SimpleDiffRequestChain chain = new SimpleDiffRequestChain(diffRequest);
                ChainDiffVirtualFile diffFile = new ChainDiffVirtualFile(chain, "变更: " + ref.change.getFileName());
                DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true);
            } catch (Exception ignored) {}
        });
    }

    private void openFileInEditor(String filePath) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (file == null && project.getBasePath() != null) {
            file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + filePath);
        }
        if (file != null) {
            FileEditorManager.getInstance(project).openFile(file, true);
        }
    }

    private void adjustSize() {
        int rowH = JBUI.scale(30);
        int pad = JBUI.scale(4);
        int maxH = JBUI.scale(180);
        int height = Math.min(rows.size() * rowH + pad, maxH);
        listScrollPane.setPreferredSize(new Dimension(Short.MAX_VALUE, Math.max(height, rowH * 2)));
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public void keepAll() {
        boolean hasPending = false;
        for (RowRef ref : rows) {
            if (ref.change.state == FileState.PENDING) {
                ref.change.state = FileState.ACCEPTED;
                paintRowResolved(ref, true);
                closeDiffForFile(ref.change.filePath);
                if (onFileAccepted != null) {
                    onFileAccepted.accept(ref.change.filePath, ref.change.newContent);
                }
                hasPending = true;
            }
        }
        if (hasPending) {
            checkAllPendingResolved();
        }
    }

    public void revertAll() {
        boolean hasPending = false;
        for (RowRef ref : rows) {
            if (ref.change.state == FileState.PENDING) {
                ref.change.state = FileState.REJECTED;
                paintRowResolved(ref, false);
                closeDiffForFile(ref.change.filePath);
                if (onFileRejected != null) {
                    onFileRejected.accept(ref.change.filePath);
                }
                hasPending = true;
            }
        }
        if (hasPending) {
            closeAllDiffWindows();
            checkAllPendingResolved();
        }
    }

    private void checkAllPendingResolved() {
        boolean hasPending = rows.stream().anyMatch(r -> r.change.state == FileState.PENDING);
        if (hasPending) return;
        allPendingResolved = true;
        if (onAllResolved != null) {
            onAllResolved.run();
        }
        fireLayoutChanged();
    }

    public boolean hasPending() {
        return rows.stream().anyMatch(r -> r.change.state == FileState.PENDING);
    }

    public int getPendingCount() {
        return (int) rows.stream().filter(r -> r.change.state == FileState.PENDING).count();
    }

    public int getChangeCount() {
        return rows.size();
    }

    public void expandAndFocusFirstPending() {
        for (RowRef ref : rows) {
            if (ref.change.state == FileState.PENDING) {
                ref.row.scrollRectToVisible(ref.row.getBounds());
                return;
            }
        }
    }

    public void reset() {
        closeAllDiffWindows();
        for (RowRef ref : rows) {
            listPanel.remove(ref.row);
        }
        rows.clear();
        allPendingResolved = false;
        listScrollPane.setPreferredSize(new Dimension(Short.MAX_VALUE, JBUI.scale(66)));
        listPanel.revalidate();
        listPanel.repaint();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    public boolean isResolved() {
        return allPendingResolved;
    }

    public JComponent getComponent() {
        return mainPanel;
    }

    private void closeAllDiffWindows() {
        openDiffFiles.clear();
    }
}
