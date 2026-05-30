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
 * 支持按行范围读取、搜索文件、列出目录结构
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
        if (project == null) return files;
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir == null) return files;
        collectFiles(baseDir, files, project.getName(), 0);
        return files;
    }

    /**
     * 递归收集文件
     */
    private static void collectFiles(VirtualFile dir, List<FileContent> files, String projectName, int depth) {
        if (depth > 3 || files.size() >= MAX_FILES) return;
        for (VirtualFile child : dir.getChildren()) {
            if (files.size() >= MAX_FILES) break;
            if (child.isDirectory()) {
                if (!shouldSkipDir(child.getName())) {
                    collectFiles(child, files, projectName, depth + 1);
                }
            } else if (isCodeFile(child.getName())) {
                String content = readFileContent(child);
                if (content != null && !content.isEmpty()) {
                    files.add(new FileContent(child.getPath(), content));
                }
            }
        }
    }

    /**
     * 读取单个文件内容（全部）
     */
    public static String readFileContent(VirtualFile file) {
        try {
            if (file.getLength() > MAX_FILE_SIZE) {
                return "// 文件过大，已跳过: " + file.getPath();
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) return document.getText();
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read file: " + file.getPath(), e);
            return null;
        }
    }

    /**
     * 读取文件指定行范围的内容（startLine / endLine 从 1 开始，-1 表示不限制）
     *
     * @param file      要读取的文件
     * @param startLine 起始行（1-based），<= 0 则从第一行
     * @param endLine   结束行（1-based，含），<= 0 则到文件末尾
     * @param maxLength 最大字符数，<= 0 使用默认 5000
     */
    public static String readFileContentLines(VirtualFile file, int startLine, int endLine, int maxLength) {
        String full = readFileContent(file);
        if (full == null) return null;
        if (startLine <= 0 && endLine <= 0) {
            int limit = maxLength > 0 ? maxLength : 5000;
            return full.length() > limit ? full.substring(0, limit) + "\n...(已截断，共 " + full.length() + " 字符)" : full;
        }
        String[] lines = full.split("\n", -1);
        int total = lines.length;
        int from = startLine > 0 ? Math.min(startLine, total) : 1;
        int to   = endLine   > 0 ? Math.min(endLine, total)   : total;

        StringBuilder sb = new StringBuilder();
        sb.append("// 显示行 ").append(from).append("-").append(to)
                .append("（共 ").append(total).append(" 行）\n");
        int limit = maxLength > 0 ? maxLength : 5000;
        for (int i = from - 1; i < to && i < lines.length; i++) {
            sb.append(String.format("%4d | %s\n", i + 1, lines[i]));
            if (sb.length() > limit) {
                sb.append("...(已截断)");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 通过文件名查找并读取文件（全部内容）
     */
    public static String findAndReadFile(String fileName) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
                    fileName, GlobalSearchScope.projectScope(project));
            for (VirtualFile file : files) {
                String content = readFileContent(file);
                if (content != null) return content;
            }
        }
        return null;
    }

    /**
     * 搜索项目中匹配关键词的文件，返回文件信息列表
     *
     * @param query      文件名关键词或通配符（* ? 支持）
     * @param maxResults 最多返回数量
     */
    public static List<FileInfo> searchProjectFiles(String query, int maxResults) {
        List<FileInfo> results = new ArrayList<>();
        String pattern = query.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) continue;
            searchFilesRecursive(baseDir, pattern, results, maxResults, 0);
        }
        return results;
    }

    private static void searchFilesRecursive(VirtualFile dir, String pattern,
                                             List<FileInfo> results, int max, int depth) {
        if (depth > 8 || results.size() >= max) return;
        for (VirtualFile child : dir.getChildren()) {
            if (results.size() >= max) break;
            if (child.isDirectory()) {
                if (!shouldSkipDir(child.getName())) {
                    searchFilesRecursive(child, pattern, results, max, depth + 1);
                }
            } else {
                String name = child.getName();
                if (name.matches("(?i)" + pattern)) {
                    results.add(new FileInfo(child.getPath(), name, child.getLength(), child.isDirectory()));
                }
            }
        }
    }

    /**
     * 列出目录内容
     *
     * @param path      目录相对/绝对路径，null 则取项目根目录
     * @param recursive 是否递归
     */
    public static String listDirectory(String path, boolean recursive) {
        StringBuilder sb = new StringBuilder();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            VirtualFile dir;
            if (path == null || path.isBlank()) {
                dir = project.getBaseDir();
            } else {
                dir = project.getBaseDir() != null
                        ? project.getBaseDir().findFileByRelativePath(path) : null;
                if (dir == null) {
                    dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path);
                }
            }
            if (dir == null || !dir.isDirectory()) continue;
            sb.append("\ud83d\udcc1 ").append(dir.getPath()).append("\n");
            appendDirTree(dir, sb, "", recursive ? 3 : 1, 0);
            break;
        }
        return sb.isEmpty() ? "目录不存在或无法访问" : sb.toString();
    }

    private static void appendDirTree(VirtualFile dir, StringBuilder sb, String indent, int maxDepth, int depth) {
        if (depth >= maxDepth) return;
        VirtualFile[] children = dir.getChildren();
        for (int i = 0; i < children.length; i++) {
            VirtualFile child = children[i];
            boolean last = i == children.length - 1;
            String prefix = indent + (last ? "\u2514\u2500\u2500 " : "\u251c\u2500\u2500 ");
            if (child.isDirectory()) {
                if (shouldSkipDir(child.getName())) continue;
                sb.append(prefix).append("\ud83d\udcc1 ").append(child.getName()).append("/\n");
                appendDirTree(child, sb, indent + (last ? "    " : "\u2502   "), maxDepth, depth + 1);
            } else {
                sb.append(prefix).append(child.getName())
                        .append("  (").append(formatSize(child.getLength())).append(")\n");
            }
        }
    }

    /**
     * 构建文件上下文提示词
     */
    public static String buildFileContext(List<FileContent> files) {
        if (files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是项目中的相关文件内容：\n\n");
        for (FileContent file : files) {
            sb.append("--- 文件: ").append(file.path).append(" ---\n");
            sb.append(file.content).append("\n\n");
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 四大金刚：PSI / 代码分析方法
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 定位代码符号 —— 输入类名或方法名，返回所在文件路径列表（利用 PSI）
     */
    public static List<String> locateSymbol(String symbol, Project project) {
        List<String> results = new ArrayList<>();
        if (symbol == null || symbol.isBlank() || project == null) return results;

        // 1. 尝试按类名查找（JavaPsiFacade）
        try {
            com.intellij.psi.PsiClass psiClass = com.intellij.psi.JavaPsiFacade
                    .getInstance(project).findClass(symbol, GlobalSearchScope.projectScope(project));
            if (psiClass != null && psiClass.getContainingFile() != null
                    && psiClass.getContainingFile().getVirtualFile() != null) {
                results.add(psiClass.getContainingFile().getVirtualFile().getPath());
            }
        } catch (Exception e) {
            LOG.warn("JavaPsiFacade.findClass failed for " + symbol, e);
        }

        // 2. 尝试按短名查找类（PsiShortNamesCache）
        try {
            com.intellij.psi.search.PsiShortNamesCache cache =
                    com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            com.intellij.psi.PsiClass[] classes = cache.getClassesByName(
                    symbol, GlobalSearchScope.projectScope(project));
            for (com.intellij.psi.PsiClass cls : classes) {
                if (cls.getContainingFile() != null && cls.getContainingFile().getVirtualFile() != null) {
                    String path = cls.getContainingFile().getVirtualFile().getPath();
                    if (!results.contains(path)) results.add(path);
                }
            }
        } catch (Exception e) {
            LOG.warn("PsiShortNamesCache.getClassesByName failed for " + symbol, e);
        }

        // 3. 尝试按方法名查找
        try {
            com.intellij.psi.search.PsiShortNamesCache cache =
                    com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            com.intellij.psi.PsiMethod[] methods = cache.getMethodsByName(
                    symbol, GlobalSearchScope.projectScope(project));
            for (com.intellij.psi.PsiMethod method : methods) {
                if (method.getContainingFile() != null && method.getContainingFile().getVirtualFile() != null) {
                    String path = method.getContainingFile().getVirtualFile().getPath();
                    if (!results.contains(path)) results.add(path);
                }
            }
        } catch (Exception e) {
            LOG.warn("PsiShortNamesCache.getMethodsByName failed for " + symbol, e);
        }

        // 4. 回退：按文件名搜索
        if (results.isEmpty()) {
            List<FileInfo> files = searchProjectFiles(symbol + "*.java", 5);
            for (FileInfo f : files) results.add(f.path);
        }
        return results;
    }

    /**
     * 查看文件大纲 —— 输入文件路径，返回类的属性、方法签名及注释（利用 PSI）
     */
    public static String viewFileOutline(String filePath, Project project) {
        if (filePath == null || filePath.isBlank() || project == null) return "错误：参数无效";

        VirtualFile vf = findVirtualFile(filePath, project);
        if (vf == null) return "错误：找不到文件 " + filePath;

        com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return "错误：无法解析文件 " + filePath;

        StringBuilder sb = new StringBuilder();
        sb.append("📄 文件大纲：").append(vf.getName()).append("\n");
        sb.append("路径：").append(vf.getPath()).append("\n\n");

        // 尝试 Java PSI
        if (psiFile instanceof com.intellij.psi.PsiJavaFile) {
            com.intellij.psi.PsiJavaFile jf = (com.intellij.psi.PsiJavaFile) psiFile;
            for (com.intellij.psi.PsiClass cls : jf.getClasses()) {
                appendClassOutline(cls, sb, 0);
            }
        } else {
            // 回退：通用 PSI 提取顶层元素
            appendGenericOutline(psiFile, sb);
        }
        return sb.toString();
    }

    private static void appendClassOutline(com.intellij.psi.PsiClass cls, StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        // 类注释
        String doc = cls.getDocComment() != null ? cls.getDocComment().getText() : null;
        if (doc != null) {
            sb.append(pad).append("📋 ").append(trimDoc(doc)).append("\n");
        }
        // 类声明
        sb.append(pad).append("🔷 class ").append(cls.getName());
        if (cls.getExtendsList() != null && cls.getExtendsList().getTextLength() > 0) {
            sb.append(" extends ").append(cls.getExtendsList().getText());
        }
        sb.append("\n");

        // 字段
        for (com.intellij.psi.PsiField field : cls.getFields()) {
            String fDoc = field.getDocComment() != null ? trimDoc(field.getDocComment().getText()) : "";
            if (!fDoc.isEmpty()) sb.append(pad).append("  📝 ").append(fDoc).append("\n");
            sb.append(pad).append("  📦 ").append(field.getType().getPresentableText())
                    .append(" ").append(field.getName()).append("\n");
        }

        // 方法
        for (com.intellij.psi.PsiMethod method : cls.getMethods()) {
            String mDoc = method.getDocComment() != null ? trimDoc(method.getDocComment().getText()) : "";
            if (!mDoc.isEmpty()) sb.append(pad).append("  📝 ").append(mDoc).append("\n");
            sb.append(pad).append("  🔧 ");
            if (method.getModifierList().getText().contains("public")) sb.append("public ");
            else if (method.getModifierList().getText().contains("private")) sb.append("private ");
            else if (method.getModifierList().getText().contains("protected")) sb.append("protected ");
            sb.append(method.getReturnType() != null ? method.getReturnType().getPresentableText() : "void")
                    .append(" ").append(method.getName()).append("(");
            com.intellij.psi.PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
            }
            sb.append(")");
            if (method.getThrowsList().getTextLength() > 0) {
                sb.append(" ").append(method.getThrowsList().getText());
            }
            sb.append("\n");
        }

        // 内部类
        for (com.intellij.psi.PsiClass inner : cls.getInnerClasses()) {
            appendClassOutline(inner, sb, indent + 1);
        }
    }

    private static void appendGenericOutline(com.intellij.psi.PsiFile psiFile, StringBuilder sb) {
        // 通用回退：遍历所有 PsiNamedElement
        for (com.intellij.psi.PsiElement child : psiFile.getChildren()) {
            if (child instanceof com.intellij.psi.PsiNamedElement) {
                String name = ((com.intellij.psi.PsiNamedElement) child).getName();
                if (name != null) sb.append("  • ").append(name).append("\n");
            }
        }
        if (sb.toString().endsWith("\n\n")) {
            // 如果通用提取没拿到什么内容，给点提示
            sb.append("  (该文件类型暂不支持详细大纲解析)\n");
        }
    }

    private static String trimDoc(String doc) {
        if (doc == null) return "";
        return doc.replaceAll("\\s*\\*\\s*", " ").replaceAll("/\\*\\*|\\*/", "").trim();
    }

    /**
     * 全局搜索 —— 在项目所有代码文件中搜索关键词
     */
    public static String searchGrep(String keyword, String filePattern, int maxResults, Project project) {
        if (keyword == null || keyword.isBlank()) return "错误：请提供搜索关键词";
        if (project == null || project.getBaseDir() == null) return "错误：项目未打开";

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 全局搜索：\"").append(keyword).append("\"\n\n");
        int found = 0;

        java.util.regex.Pattern filePat = null;
        if (filePattern != null && !filePattern.isBlank()) {
            String fp = filePattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            filePat = java.util.regex.Pattern.compile("(?i)" + fp);
        }

        List<VirtualFile> allFiles = collectAllCodeFiles(project.getBaseDir(), 6);
        for (VirtualFile vf : allFiles) {
            if (found >= maxResults) break;
            if (filePat != null && !filePat.matcher(vf.getName()).matches()) continue;
            if (vf.getLength() > MAX_FILE_SIZE) continue;

            String content = readFileContent(vf);
            if (content == null) continue;

            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length && found < maxResults; i++) {
                if (lines[i].contains(keyword)) {
                    found++;
                    sb.append(String.format("%d. %s:%d\n", found, vf.getPath(), i + 1));
                    sb.append("   ").append(lines[i].trim()).append("\n\n");
                }
            }
        }

        if (found == 0) sb.append("未找到匹配结果\n");
        else sb.append("共找到 ").append(found).append(" 处匹配\n");
        return sb.toString();
    }

    private static List<VirtualFile> collectAllCodeFiles(VirtualFile dir, int maxDepth) {
        List<VirtualFile> results = new ArrayList<>();
        collectAllCodeFilesRecursive(dir, results, maxDepth, 0);
        return results;
    }

    private static void collectAllCodeFilesRecursive(VirtualFile dir, List<VirtualFile> results, int maxDepth, int depth) {
        if (depth > maxDepth || results.size() > 500) return;
        for (VirtualFile child : dir.getChildren()) {
            if (results.size() > 500) break;
            if (child.isDirectory()) {
                if (!shouldSkipDir(child.getName())) {
                    collectAllCodeFilesRecursive(child, results, maxDepth, depth + 1);
                }
            } else if (isCodeFile(child.getName())) {
                results.add(child);
            }
        }
    }

    private static VirtualFile findVirtualFile(String filePath, Project project) {
        VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath);
        if (vf != null && vf.exists()) return vf;
        if (project != null && project.getBaseDir() != null) {
            vf = project.getBaseDir().findFileByRelativePath(filePath);
            if (vf != null && vf.exists()) return vf;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static boolean shouldSkipDir(String name) {
        return name.startsWith(".")
                || name.equals("node_modules") || name.equals("target")
                || name.equals("build")        || name.equals("dist")
                || name.equals("out")          || name.equals(".idea")
                || name.equals(".git")         || name.equals("vendor")
                || name.equals("__pycache__")  || name.equals("gradle")
                || name.equals(".gradle");
    }

    private static boolean isCodeFile(String name) {
        String[] extensions = {
                ".java", ".kt", ".scala", ".groovy",
                ".py", ".js", ".ts", ".jsx", ".tsx",
                ".go", ".rs", ".cpp", ".c", ".h", ".hpp", ".cc",
                ".cs", ".vb", ".fs", ".php", ".rb", ".swift", ".m", ".mm",
                ".xml", ".json", ".yaml", ".yml", ".properties",
                ".html", ".css", ".scss", ".less",
                ".sql", ".sh", ".bat", ".ps1", ".md", ".txt"
        };
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 数据类
    // ─────────────────────────────────────────────────────────────────────────

    public static class FileContent {
        public final String path;
        public final String content;
        public FileContent(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }

    public static class FileInfo {
        public final String path;
        public final String name;
        public final long   size;
        public final boolean isDirectory;
        public FileInfo(String path, String name, long size, boolean isDirectory) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.isDirectory = isDirectory;
        }
    }
}
