package com.codepal.linter.impl;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.codepal.linter.LintIssue;
import com.codepal.linter.LintResult;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.Linter;

import java.util.ArrayList;
import java.util.List;

/**
 * IDE 内置检查 Linter —— 直接读取 IntelliJ 的实时诊断信息。
 *
 * <p>无需安装任何外部工具，利用 IDEA 正在运行的代码检查引擎
 * （和 IDE 编辑器中红色波浪线是同一份数据源）。</p>
 *
 * <p>覆盖所有 IDEA 原生支持的语言和检查规则（Java、Kotlin、
 * XML、JSON、YAML、Properties 等）。</p>
 */
public class IdeInspectionsLinter implements Linter {

    private static final String NAME = "IDE Inspections";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "使用 IntelliJ IDEA 内置代码检查引擎，支持所有 IDE 原生语言和规则";
    }

    @Override
    public List<String> getSupportedExtensions() {
        // 不限制扩展名——IDE 能打开的文件都能检查
        return List.of("*");
    }

    @Override
    public boolean isAvailable(String projectPath) {
        // 只要在 IDEA 内运行就始终可用
        return true;
    }

    @Override
    public String getInstallationInstructions() {
        return "无需安装，IntelliJ IDEA 内置";
    }

    /**
     * 对指定文件运行 IDE 内置检查。
     *
     * @param filePath    文件路径（绝对路径或相对于项目的路径）
     * @param projectPath 项目根路径
     */
    @Override
    public LintResult lintFile(String filePath, String projectPath) {
        // ★ 本方法会被后台线程调用（ToolExecutor.executeOnPooledThread → ErrorValidationAgent），
        //   而 FileDocumentManager.getDocument / VFS / 编辑器 MarkupModel 均要求 read-access，
        //   否则抛 "Read access is allowed from inside read-action only"（见日志.md）。
        //   用 ReadAction.run(Runnable) 单一重载包裹（避免 Computable/ThrowableComputable 重载歧义）；
        //   在 EDT 上调用也安全（读锁幂等）。
        final List<LintIssue> issues = new ArrayList<>();
        final boolean[] ok = {false};
        final String fPath = filePath;

        ReadAction.run(() -> {
            Project project = findProject();
            if (project == null) return;

            VirtualFile vf = resolveFile(fPath, projectPath);
            if (vf == null) return;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return;

            // 通过 Public API（FileEditorManager → TextEditor → MarkupModel）读取 IDE 已渲染的诊断高亮，
            // 与编辑器红色波浪线同一份数据；避免使用 Internal 的 DaemonCodeAnalyzerImpl。
            // 仅当目标文件当前在编辑器中被打开时才可读到高亮。
            FileEditor[] editors = FileEditorManager.getInstance(project).getEditors(vf);
            for (FileEditor fe : editors) {
                if (!(fe instanceof TextEditor)) continue;
                Editor editor = ((TextEditor) fe).getEditor();
                if (editor.getDocument() != document) continue;
                MarkupModel model = editor.getMarkupModel();
                for (RangeHighlighter h : model.getAllHighlighters()) {
                    HighlightInfo info = HighlightInfo.fromRangeHighlighter(h);
                    if (info == null) continue;
                    LintIssue issue = convert(info, document, vf.getName());
                    if (issue != null) {
                        issues.add(issue);
                    }
                }
                break;
            }
            ok[0] = true;
        });

        return new LintResult(fPath, NAME, issues, ok[0]);
    }

    /**
     * 将 IDEA HighlightInfo 转换为 LintIssue
     */
    private static LintIssue convert(HighlightInfo info, Document document, String fileName) {
        int offset = info.getStartOffset();
        if (offset < 0 || offset >= document.getTextLength()) {
            offset = Math.max(0, Math.min(offset, document.getTextLength() - 1));
        }

        int line = document.getLineNumber(offset) + 1;
        int column = offset - document.getLineStartOffset(line - 1) + 1;

        LintSeverity severity = mapSeverity(info.getSeverity());
        String message = info.getDescription() != null ? info.getDescription() : "";
        String rule = info.getInspectionToolId() != null ? info.getInspectionToolId() : "";

        LintIssue issue = new LintIssue(line, column, severity, message, rule);
        issue.filePath = fileName;
        return issue;
    }

    /**
     * IDEA severity → LintSeverity 映射
     */
    private static LintSeverity mapSeverity(HighlightSeverity severity) {
        if (severity == null) return LintSeverity.INFO;
        if (severity.compareTo(HighlightSeverity.ERROR) >= 0) {
            return LintSeverity.ERROR;
        }
        if (severity.compareTo(HighlightSeverity.WARNING) >= 0) {
            return LintSeverity.WARNING;
        }
        return LintSeverity.INFO;
    }

    /**
     * 查找当前项目
     */
    private static Project findProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }

    /**
     * 解析文件路径 → VirtualFile
     */
    private static VirtualFile resolveFile(String filePath, String projectPath) {
        // 尝试绝对路径
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (vf != null) return vf;

        // 在项目目录下拼接相对路径
        if (projectPath != null && !projectPath.equals(".")) {
            String fullPath = projectPath + "/" + filePath;
            vf = LocalFileSystem.getInstance().findFileByPath(fullPath);
            if (vf != null) return vf;
        }

        return null;
    }
}
