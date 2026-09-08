package com.codepal.tools;

import com.intellij.diff.DiffContentFactoryEx;
import com.intellij.diff.DiffContext;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.simple.SimpleDiffViewer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public class FileDiffInEditor {

    // ========== 旧版：模态弹窗（Plan 模式等旧调用方） ==========

    public static void showDiff(@NotNull Project project,
                                @NotNull VirtualFile file,
                                @NotNull String originalContent,
                                @NotNull String newContent) {
        showDiff(project, file, originalContent, newContent, null);
    }

    public static void showDiff(@NotNull Project project,
                                @NotNull VirtualFile file,
                                @NotNull String originalContent,
                                @NotNull String newContent,
                                @Nullable Consumer<Boolean> onDecision) {
        ApplicationManager.getApplication().invokeLater(() -> {
            DiffContentFactoryEx contentFactory = DiffContentFactoryEx.getInstanceEx();
            DocumentContent leftContent = contentFactory.create(project, originalContent);
            DocumentContent rightContent = contentFactory.create(project, newContent);

            SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                    "AI 修改建议 — " + file.getName(),
                    leftContent, rightContent,
                    "当前文件", "AI 建议"
            );

            DiffContext diffContext = new DiffContext() {
                @Override public @Nullable Project getProject() { return project; }
                @Override public boolean isWindowFocused() { return true; }
                @Override public boolean isFocusedInWindow() { return true; }
                @Override public void requestFocusInWindow() {}
            };

            DiffDialog dialog = new DiffDialog(project, file, diffContext, diffRequest, newContent, onDecision);
            dialog.show();
        });
    }

    public static boolean showDiffSync(@NotNull Project project,
                                       @NotNull VirtualFile file,
                                       @NotNull String originalContent,
                                       @NotNull String newContent) {
        DiffContentFactoryEx contentFactory = DiffContentFactoryEx.getInstanceEx();
        DocumentContent leftContent = contentFactory.create(project, originalContent);
        DocumentContent rightContent = contentFactory.create(project, newContent);

        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                "AI 修改建议 — " + file.getName(),
                leftContent, rightContent,
                "当前文件", "AI 建议"
        );

        DiffContext diffContext = new DiffContext() {
            @Override public @Nullable Project getProject() { return project; }
            @Override public boolean isWindowFocused() { return true; }
            @Override public boolean isFocusedInWindow() { return true; }
            @Override public void requestFocusInWindow() {}
        };

        DiffDialog dialog = new DiffDialog(project, file, diffContext, diffRequest, newContent, null);
        dialog.show();
        return dialog.isAccepted();
    }

    // ========== 新版：非模态预览（可跟踪、可外部关闭） ==========

    /**
     * 打开非模态 Diff 预览窗口（仅查看对比，无保留/撤销按钮）。
     * 返回句柄，调用方可通过 {@link DiffPreview#close(int)} 主动关闭窗口。
     */
    public static @NotNull DiffPreview openDiffPreview(@NotNull Project project,
                                                        @NotNull String fileName,
                                                        @NotNull String originalContent,
                                                        @NotNull String newContent) {
        DiffContentFactoryEx contentFactory = DiffContentFactoryEx.getInstanceEx();
        DocumentContent leftContent = contentFactory.create(project, originalContent);
        DocumentContent rightContent = contentFactory.create(project, newContent);

        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                "变更: " + fileName,
                leftContent, rightContent,
                "原始", "AI 建议"
        );

        DiffContext diffContext = new DiffContext() {
            @Override public @Nullable Project getProject() { return project; }
            @Override public boolean isWindowFocused() { return true; }
            @Override public boolean isFocusedInWindow() { return true; }
            @Override public void requestFocusInWindow() {}
        };

        DiffPreview dialog = new DiffPreview(project, fileName, diffContext, diffRequest);
        dialog.show();
        return dialog;
    }

    /** 非模态 Diff 预览窗口。仅有「关闭」按钮，决策由外部 FileChangeListPanel 行按钮完成。 */
    public static class DiffPreview extends DialogWrapper {

        private final DiffContext diffContext;
        private final SimpleDiffRequest diffRequest;

        DiffPreview(@NotNull Project project,
                    @NotNull String fileName,
                    @NotNull DiffContext diffContext,
                    @NotNull SimpleDiffRequest diffRequest) {
            super(project, false);  // false = 非模态
            this.diffContext = diffContext;
            this.diffRequest = diffRequest;

            setTitle("Diff: " + fileName);
            setOKButtonText("关闭");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel mainPanel = new JPanel(new BorderLayout());
            mainPanel.setPreferredSize(new Dimension(900, 600));
            mainPanel.setBorder(JBUI.Borders.empty(8));

            SimpleDiffViewer diffViewer = new SimpleDiffViewer(diffContext, diffRequest);
            diffViewer.init();
            mainPanel.add(diffViewer.getComponent(), BorderLayout.CENTER);
            return mainPanel;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }

        /** 关闭预览窗口（从外部调用） */
        public void closeWindow() {
            close(CANCEL_EXIT_CODE);
        }
    }

    // ========== 旧版模态弹窗（保留兼容） ==========

    private static class DiffDialog extends DialogWrapper {

        private final Project project;
        private final VirtualFile file;
        private final DiffContext diffContext;
        private final SimpleDiffRequest diffRequest;
        private final String newContent;
        private final Consumer<Boolean> onDecision;

        DiffDialog(@NotNull Project project,
                   @NotNull VirtualFile file,
                   @NotNull DiffContext diffContext,
                   @NotNull SimpleDiffRequest diffRequest,
                   @NotNull String newContent,
                   @Nullable Consumer<Boolean> onDecision) {
            super(project, true);
            this.project = project;
            this.file = file;
            this.diffContext = diffContext;
            this.diffRequest = diffRequest;
            this.newContent = newContent;
            this.onDecision = onDecision;

            setTitle("Diff: " + file.getName());
            setOKButtonText("保留");
            setCancelButtonText("关闭");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel mainPanel = new JPanel(new BorderLayout());
            mainPanel.setPreferredSize(new Dimension(900, 600));
            mainPanel.setBorder(JBUI.Borders.empty(8));

            SimpleDiffViewer diffViewer = new SimpleDiffViewer(diffContext, diffRequest);
            diffViewer.init();
            mainPanel.add(diffViewer.getComponent(), BorderLayout.CENTER);
            return mainPanel;
        }

        @Override
        protected JComponent createSouthPanel() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            panel.setBorder(JBUI.Borders.empty(8, 0, 0, 0));

            JButton revertBtn = new JButton("撤销");
            revertBtn.setIcon(com.intellij.icons.AllIcons.Actions.Rollback);
            revertBtn.addActionListener(e -> {
                if (onDecision != null) onDecision.accept(false);
                close(CANCEL_EXIT_CODE);
            });

            JButton keepBtn = new JButton("保留");
            keepBtn.setIcon(com.intellij.icons.AllIcons.Actions.Commit);
            keepBtn.setBackground(JBColor.namedColor("Button.default.startBackground",
                    !JBColor.isBright() ? new Color(0x2B5B84) : new Color(0x4B8EF0)));
            keepBtn.setForeground(Color.WHITE);
            keepBtn.setOpaque(true);
            keepBtn.addActionListener(e -> {
                if (onDecision == null) {
                    WriteCommandAction.runWriteCommandAction(project, "CP - AI 修改文件", null, () -> {
                        ApplicationManager.getApplication().runWriteAction(() -> {
                            try {
                                file.setBinaryContent(newContent.getBytes(file.getCharset()));
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(() ->
                                        com.intellij.openapi.ui.Messages.showErrorDialog(project,
                                                "文件写入失败: " + ex.getMessage(),
                                                "CP — 写入失败"));
                            }
                        });
                    });
                }
                if (onDecision != null) onDecision.accept(true);
                close(OK_EXIT_CODE);
            });

            JButton closeBtn = new JButton("关闭");
            closeBtn.addActionListener(e -> close(CANCEL_EXIT_CODE));

            panel.add(closeBtn);
            panel.add(revertBtn);
            panel.add(keepBtn);
            return panel;
        }

        public boolean isAccepted() {
            return getExitCode() == OK_EXIT_CODE;
        }
    }
}
