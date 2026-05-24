package com.loongc.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 项目文件读取工具类
 */
public class FileReaderUtil {
    private static final Logger LOG = Logger.getInstance(FileReaderUtil.class);
    private static final int MAX_FILE_SIZE = 100 * 1024; // 100KB
    private static final int MAX_FILES = 20;

    /**
     * 读取当前打开项目的所有代码文件内容
     */
    public static List<FileContent> readProjectFiles(Project project) {
        List<FileContent> files = new ArrayList<>();
        if (project == null) {
            return files;
        }

        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) {
            return files;
        }

        collectFiles(baseDir, files, project.getName(), 0);
        return files;
    }

    /**
     * 递归收集文件
     */
    private static void collectFiles(VirtualFile dir, List<FileContent> files, String projectName, int depth) {
        if (depth > 3 || files.size() >= MAX_FILES) {
            return;
        }

        for (VirtualFile child : dir.getChildren()) {
            if (files.size() >= MAX_FILES) {
                break;
            }
            if (child.isDirectory()) {
                String name = child.getName();
                // 跳过常见的非代码目录
                if (shouldSkipDir(name)) {
                    continue;
                }
                collectFiles(child, files, projectName, depth + 1);
            } else if (isCodeFile(child.getName())) {
                String content = readFileContent(child);
                if (content != null && !content.isEmpty()) {
                    files.add(new FileContent(child.getPath(), content));
                }
            }
        }
    }

    /**
     * 读取单个文件内容
     */
    public static String readFileContent(VirtualFile file) {
        try {
            if (file.getLength() > MAX_FILE_SIZE) {
                return "// 文件过大，已跳过: " + file.getPath();
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) {
                return document.getText();
            }
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read file: " + file.getPath(), e);
            return null;
        }
    }

    /**
     * 通过文件名查找并读取文件
     */
    public static String findAndReadFile(String fileName) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project));
            for (VirtualFile file : files) {
                String content = readFileContent(file);
                if (content != null) {
                    return content;
                }
            }
        }
        return null;
    }

    /**
     * 构建文件上下文提示词
     */
    public static String buildFileContext(List<FileContent> files) {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是项目中的相关文件内容：\n\n");
        for (FileContent file : files) {
            sb.append("--- 文件: ").append(file.path).append(" ---\n");
            sb.append(file.content);
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private static boolean shouldSkipDir(String name) {
        return name.startsWith(".")
            || name.equals("node_modules")
            || name.equals("target")
            || name.equals("build")
            || name.equals("dist")
            || name.equals("out")
            || name.equals(".idea")
            || name.equals(".git")
            || name.equals("vendor")
            || name.equals("__pycache__");
    }

    private static boolean isCodeFile(String name) {
        String[] extensions = {
            ".java", ".kt", ".scala", ".groovy",
            ".py", ".js", ".ts", ".jsx", ".tsx",
            ".go", ".rs", ".cpp", ".c", ".h", ".hpp", ".cc",
            ".cs", ".vb", ".fs",
            ".php", ".rb", ".swift", ".m", ".mm",
            ".xml", ".json", ".yaml", ".yml", ".properties",
            ".html", ".css", ".scss", ".less",
            ".sql", ".sh", ".bat", ".ps1",
            ".md", ".txt"
        };
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文件内容数据类
     */
    public static class FileContent {
        public final String path;
        public final String content;

        public FileContent(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }
}
