package com.codepal.utils;

import com.intellij.openapi.application.ReadAction;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author 水龙吟
 * @date 2026-05-24
 *
 * 项目文件读取工具类
 * 支持按行范围读取、搜索文件、列出目录结构
 */
public class FileReaderUtil {
    private static final Logger LOG = Logger.getInstance(FileReaderUtil.class);
    private static final int MAX_FILE_SIZE = 1000 * 1024; // 100KB
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
        // FileDocumentManager.getDocument 与 VFS 读取需要 read-access；本方法会被后台线程池
        // （ThreadHelper.executeAsync → ToolExecutor）调用，必须包 read-action。
        // 用 ReadAction.run(Runnable) 单一重载，避免 runReadAction(Computable /
        // ThrowableComputable) 两个返回型重载导致的 lambda 歧义。
        final String[] result = {null};
        final java.util.List<String> writtenPaths = new java.util.ArrayList<>();
        ReadAction.run(() -> {
            try {
                if (file.getLength() > MAX_FILE_SIZE) {
                    result[0] = "// 文件过大，已跳过: " + file.getPath();
                    return;
                }
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) { result[0] = document.getText(); return; }
                result[0] = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Failed to read file: " + file.getPath(), e);
            }
        });
        return result[0];
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
            int limit = maxLength > 0 ? maxLength : 50000;
            return full.length() > limit ? full.substring(0, limit) + "\n...(已截断，共 " + full.length() + " 字符)" : full;
        }
        String[] lines = full.split("\n", -1);
        int total = lines.length;
        int from = startLine > 0 ? Math.min(startLine, total) : 1;
        int to   = endLine   > 0 ? Math.min(endLine, total)   : total;

        StringBuilder sb = new StringBuilder();
        sb.append("// 显示行 ").append(from).append("-").append(to)
                .append("（共 ").append(total).append(" 行）\n");
        int limit = maxLength > 0 ? maxLength : 5000000;
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
        // FilenameIndex 索引访问需要 read-access；本方法会被后台线程池调用。
        final String[] result = {null};
        final java.util.List<String> writtenPaths = new java.util.ArrayList<>();
        ReadAction.run(() -> {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
                        fileName, GlobalSearchScope.projectScope(project));
                for (VirtualFile file : files) {
                    String content = readFileContent(file);
                    if (content != null) { result[0] = content; return; }
                }
            }
        });
        return result[0];
    }

    /**
     * 搜索项目中匹配关键词的文件，返回文件信息列表
     *
     * @param query      文件名关键词或通配符（* ? 支持）
     * @param maxResults 最多返回数量
     */
    public static List<FileInfo> searchProjectFiles(String query, int maxResults) {
        // FilenameIndex 索引访问需要 read-access；本方法会被后台线程池（list_files 工具）调用。
        final List<FileInfo> result = new ArrayList<>();
        ReadAction.run(() -> {
            String pattern = query.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            final int limit = maxResults <= 0 ? 50 : maxResults;
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                // 用 IDE 文件名索引（FilenameIndex）覆盖所有模块/子模块，无目录 depth 限制，
                // 与 locateSymbol 统一口径（对标 AutoDev 的 IDE 原生索引原则）。
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                String[] allNames = FilenameIndex.getAllFilenames(project);
                for (String name : allNames) {
                    if (result.size() >= limit) break;
                    if (!name.matches("(?i)" + pattern)) continue;
                    for (VirtualFile vf : FilenameIndex.getVirtualFilesByName(name, scope)) {
                        if (result.size() >= limit) break;
                        result.add(new FileInfo(vf.getPath(), vf.getName(), vf.getLength(), vf.isDirectory()));
                    }
                }
                if (result.size() >= limit) break;
            }
        });
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 任意目录检索（不依赖 IDEA 项目 / PSI，用 java.nio.file 遍历本地文件系统）
    // 用于搜索"未在 IDEA 中打开的本地目录"。
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 在任意本地目录中按文件名通配符（* ?）搜索文件，不依赖 IDEA 项目。
     *
     * @param query     文件名关键词或通配符（* ? 支持）
     * @param rootPath  要搜索的根目录（绝对路径）；为 null/空则退回当前 IDEA 项目根
     * @param maxResults 最多返回数量
     */
    public static List<FileInfo> searchProjectFilesInPath(String query, String rootPath, int maxResults) {
        List<FileInfo> results = new ArrayList<>();
        final int limit = maxResults <= 0 ? 50 : maxResults;
        Path root = resolveRootPath(rootPath);
        if (root == null || !Files.isDirectory(root)) return results;

        // 把通配符转成正则：* -> .* ，? -> . ，并忽略大小写
        String regex = "(?i)" + query.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        Pattern namePattern = Pattern.compile(regex);

        try (Stream<Path> walk = Files.walk(root, 20)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !isUnderSkippedDir(p))
                .filter(p -> namePattern.matcher(p.getFileName().toString()).matches())
                .limit(limit)
                .forEach(p -> {
                    try {
                        results.add(new FileInfo(p.toString(), p.getFileName().toString(),
                                Files.size(p), false));
                    } catch (IOException ignored) { }
                });
        } catch (IOException | RuntimeException e) {
            LOG.warn("searchProjectFilesInPath failed for " + root, e);
        }
        return results;
    }

    /**
     * 在任意本地目录中搜索包含关键词的代码行（grep），不依赖 IDEA 项目。
     * 按文件分组返回命中行（行号 + 内容）。
     *
     * @param keyword    要搜索的关键词
     * @param rootPath   要搜索的根目录（绝对路径）；为 null/空则退回当前 IDEA 项目根
     * @param filePattern 可选文件类型过滤，如 *.java、*.xml（glob，支持 ** 与 * ?）
     * @param maxResults 最多返回命中行数
     */
        public static String searchGrepInPath(String keyword, String rootPath,
                                               String filePattern, int maxResults) {
            return searchGrepInPath(keyword, rootPath, filePattern, maxResults, false);
        }
    
        /**
         * 重载：支持 regex 模式。regex=true 时 keyword 按 Java 正则匹配行内容；false 时按字面量包含。
         */
        public static String searchGrepInPath(String keyword, String rootPath,
                                               String filePattern, int maxResults, boolean regex) {
            if (keyword == null || keyword.isBlank()) return "错误：请提供搜索关键词";
            final int limit = maxResults <= 0 ? 10 : maxResults;
            // ★ 提前验证正则合法性，避免每个文件重复抛异常
            java.util.function.Predicate<String> lineMatcher;
            if (regex) {
                final Pattern kwPat;
                try {
                    kwPat = Pattern.compile(keyword);
                } catch (Exception e) {
                    return "错误：正则表达式非法 - " + e.getMessage()
                            + "\n提示：如果只想搜普通文本，请传 regex=false 或不传。";
                }
                lineMatcher = line -> kwPat.matcher(line).find();
            } else {
                lineMatcher = line -> line.contains(keyword);
            }
            Path root = resolveRootPath(rootPath);
            if (root == null || !Files.isDirectory(root)) {
                return "错误：目录不存在或无法访问：" + (rootPath == null ? "(空)" : rootPath);
            }
     
             Pattern filePat = filePattern != null && !filePattern.isBlank()
                     ? globToRegex(filePattern) : null;
             final java.util.Map<String, List<String>> byFile = new java.util.LinkedHashMap<>();
             int[] found = {0};
     
             try (Stream<Path> walk = Files.walk(root, 20)) {
                 walk.filter(Files::isRegularFile)
                     .filter(p -> !isUnderSkippedDir(p))
                     .filter(p -> filePat == null || filePat.matcher(p.toString().replace('\\', '/')).find())
                     .forEach(p -> {
                         if (found[0] >= limit) return;
                         if (isBinaryOrHuge(p)) return;
                         try {
                             List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                             for (int i = 0; i < lines.size() && found[0] < limit; i++) {
                                 String line = lines.get(i);
                                 if (lineMatcher.test(line)) {
                                byFile.computeIfAbsent(p.toString(), k -> new ArrayList<>())
                                      .add(String.format("  %d:%s", i + 1, line.trim()));
                                found[0]++;
                            }
                        }
                    } catch (IOException | RuntimeException ignored) { }
                });
        } catch (IOException | RuntimeException e) {
            LOG.warn("searchGrepInPath failed for " + root, e);
        }

        if (byFile.isEmpty()) {
            return "🔍 目录搜索：\"" + keyword + "\"\n\n未找到匹配结果" + (rootPath != null ? "（范围：" + rootPath + "）" : "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 目录搜索：\"").append(keyword).append("\"")
          .append(rootPath != null ? "（范围：" + rootPath + "）" : "").append("\n\n");
        for (var entry : byFile.entrySet()) {
            if (found[0] >= limit && sb.length() > 0) break;
            sb.append("## ").append(entry.getKey()).append("\n");
            for (String l : entry.getValue()) sb.append(l).append("\n");
            sb.append("\n");
        }
        sb.append("共找到 ").append(found[0]).append(" 处匹配\n");
        return sb.toString();
    }

    /**
     * 在任意本地目录中列出文件/子目录（nio 遍历，不依赖 IDEA 项目）。
     * 支持 glob 文件名过滤（* ? 以及 **）。按相对根目录的路径展示。
     *
     * @param pathStr   要列出的目录（绝对路径）；为 null/空则退回当前 IDEA 项目根
     * @param pattern   可选 glob 文件名过滤，如 *.java 或 *.xml；** 表示递归匹配任意层级（如 **.test.**）。不传则列出全部
     * @param recursive 是否递归子目录
     * @param maxDepth  递归最大深度（仅 recursive=true 时有效）
     * @param maxResults 最多返回数量
     */
    public static String listDirectoryInPath(String pathStr, String pattern,
                                             boolean recursive, int maxDepth, int maxResults) {
        Path root = resolveRootPath(pathStr);
        if (root == null || !Files.isDirectory(root)) {
            return "错误：目录不存在或无法访问：" + (pathStr == null ? "(空)" : pathStr);
        }
        int depth = recursive ? (maxDepth < 1 ? 3 : Math.min(maxDepth, 10)) : 1;
        if (pattern != null && pattern.contains("**")) recursive = true;
        Pattern pat = (pattern != null && !pattern.isBlank()) ? globToRegex(pattern) : null;
        final int limit = maxResults <= 0 ? 200 : maxResults;

        List<String> matched = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, depth)) {
            walk.filter(p -> !isUnderSkippedDir(p))
                .filter(p -> pat == null || pat.matcher(p.toString().replace('\\', '/')).find())
                .limit(limit)
                .forEach(p -> {
                    Path rel = root.relativize(p);
                    if (rel.getNameCount() == 0) return; // 跳过根目录自身
                    String relStr = rel.toString().replace('\\', '/');
                    matched.add((Files.isDirectory(p) ? "📂 " : "📄 ") + relStr);
                });
        } catch (IOException | RuntimeException e) {
            LOG.warn("listDirectoryInPath failed for " + root, e);
        }

        if (matched.isEmpty()) {
            return "（未找到匹配的文件）\n范围：" + root;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📁 ").append(root).append("\n");
        if (pattern != null && !pattern.isBlank()) sb.append("   模式: ").append(pattern).append("\n");
        sb.append("\n");
        for (String m : matched) sb.append("  ").append(m).append("\n");
        if (matched.size() >= limit) sb.append("\n...(结果超过 ").append(limit).append(" 条，已截断)");
        return sb.toString();
    }

    /** 解析根目录：传了绝对/相对路径用本地文件系统；空则退回当前 IDEA 项目根目录 */
    private static Path resolveRootPath(String rootPath) {
        if (rootPath != null && !rootPath.isBlank()) {
            Path p = Paths.get(rootPath);
            if (Files.exists(p)) return p;
            // 相对路径尝试相对当前项目根
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.getBaseDir() != null) {
                    VirtualFile vf = project.getBaseDir().findFileByRelativePath(rootPath);
                    if (vf != null) return Paths.get(vf.getPath());
                }
            }
            return null;
        }
        // 无 path：退回 IDEA 项目根（保持与原项目内搜索一致的行为）
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.getBaseDir() != null) return Paths.get(project.getBaseDir().getPath());
        }
        return null;
    }

    private static boolean isUnderSkippedDir(Path p) {
        for (Path part : p) {
            if (shouldSkipDir(part.toString())) return true;
        }
        return false;
    }

    private static boolean isBinaryOrHuge(Path p) {
        try {
            if (Files.size(p) > 2L * 1024 * 1024) return true; // 跳过 >2MB
        } catch (IOException ignored) { }
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".png")
                || name.endsWith(".jpg") || name.endsWith(".zip") || name.endsWith(".pdf");
    }

    /** 把 glob（支持 * ? 以及 **）转成正则 */
    private static Pattern globToRegex(String glob) {
        String g = glob.replace("\\", "/");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < g.length()) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    sb.append(".*"); // ** 匹配任意路径
                    i += 2;
                    if (i < g.length() && g.charAt(i) == '/') i++; // 跳过 **/ 的斜杠
                    continue;
                }
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (".+^${}()|[]\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
            i++;
        }
        return Pattern.compile("(?i)" + sb);
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

        // PSI / 索引访问必须在 read-action 内进行。本方法通常由后台线程池
        // （ThreadHelper.executeAsync → ToolExecutor）调用，不在 EDT 也不持有读锁，
        // 直接访问 StubIndex/JavaPsiFacade 会触发 "Read access is allowed from
        // inside read-action only" 异常。包一层 ReadAction.run 即可。
        ReadAction.run(() -> {
            // 覆盖全工程（含所有模块 / 子模块源码根 + 库）的 scope。
            // 对标 AutoDev JavaSymbolProvider：用 allScope 即可覆盖子模块，无需逐模块枚举。
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 1. 按类名查找（JavaPsiFacade，全限定名）
            try {
                com.intellij.psi.PsiClass psiClass = com.intellij.psi.JavaPsiFacade
                        .getInstance(project).findClass(symbol, scope);
                addPsiClassPath(psiClass, results);
            } catch (Exception e) {
                LOG.warn("JavaPsiFacade.findClass failed for " + symbol, e);
            }

            // 2. 按短名查找类（PsiShortNamesCache）
            try {
                com.intellij.psi.search.PsiShortNamesCache cache =
                        com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                for (com.intellij.psi.PsiClass cls : cache.getClassesByName(symbol, scope)) {
                    addPsiClassPath(cls, results);
                }
            } catch (Exception e) {
                LOG.warn("PsiShortNamesCache.getClassesByName failed for " + symbol, e);
            }

            // 3. 按方法名查找
            try {
                com.intellij.psi.search.PsiShortNamesCache cache =
                        com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                for (com.intellij.psi.PsiMethod method : cache.getMethodsByName(symbol, scope)) {
                    addPsiMethodPath(method, results);
                }
            } catch (Exception e) {
                LOG.warn("PsiShortNamesCache.getMethodsByName failed for " + symbol, e);
            }

            // 4. 回退：按文件名查找（IDE 文件名索引，覆盖所有模块 / 子模块，不依赖目录递归深度）
            if (results.isEmpty()) {
                try {
                    String fileName = symbol.endsWith(".java") ? symbol : symbol + ".java";
                    for (VirtualFile vf : FilenameIndex.getVirtualFilesByName(
                            fileName, GlobalSearchScope.allScope(project))) {
                        if (!results.contains(vf.getPath())) results.add(vf.getPath());
                    }
                } catch (Exception ignored) { }
            }
        });
        return results;
    }

    /**
     * 查找符号所有引用/调用点 —— 用 IDEA 的 ReferencesSearch（PSI 层语义搜索）。
     * 与 search_tool 的文本匹配不同：能识别同名但无关的符号（重载、不同类的同名方法），
     * 返回「文件:行号」列表 + 该行文本。
     *
     * @param symbol 类名或方法名（先定位其定义 PsiElement，再搜引用）
     * @param maxResults 最多返回条数
     */
    public static String findSymbolUsages(String symbol, int maxResults, Project project) {
        if (symbol == null || symbol.isBlank()) return "错误：请提供类名或方法名";
        if (project == null) return "错误：项目未打开";
        final int limit = maxResults <= 0 ? 20 : Math.min(maxResults, 100);

        final String[] result = {null};
        final java.util.List<String> writtenPaths = new java.util.ArrayList<>();
        ReadAction.run(() -> {
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            // 1. 定位定义元素：优先类，其次方法（可能多个同名重载 → 全部纳入搜索目标）
            java.util.List<com.intellij.psi.PsiElement> targets = new ArrayList<>();
            try {
                com.intellij.psi.PsiClass cls = com.intellij.psi.JavaPsiFacade
                        .getInstance(project).findClass(symbol, scope);
                if (cls != null) targets.add(cls);
            } catch (Exception ignored) { }
            if (targets.isEmpty()) {
                try {
                    com.intellij.psi.search.PsiShortNamesCache cache =
                            com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
                    for (com.intellij.psi.PsiMethod m : cache.getMethodsByName(symbol, scope)) {
                        targets.add(m);
                        if (targets.size() >= 10) break; // 同名重载过多时截断，避免搜索爆炸
                    }
                } catch (Exception ignored) { }
            }
            if (targets.isEmpty()) {
                result[0] = "未找到符号 \"" + symbol + "\" 的定义，无法查找引用。"
                                                 + "可先用 locate_code_by_symbol 确认定义位置，或改用 search_tool 做文本匹配。";
                return;
            }

            // 2. 逐个目标搜引用（ReferencesSearch 是 IDEA Find Usages 的底层实现）
            // 记录结构：path -> (lineNo -> lineText)，天然去重同一行多次命中
            java.util.Map<String, java.util.TreeMap<Integer, String>> byFile = new java.util.LinkedHashMap<>();
            int[] found = {0};
            for (com.intellij.psi.PsiElement target : targets) {
                if (found[0] >= limit) break;
                try {
                    for (com.intellij.psi.PsiReference ref : com.intellij.psi.search.searches.ReferencesSearch
                            .search(target, scope).findAll()) {
                        if (found[0] >= limit) break;
                        com.intellij.psi.PsiElement el = ref.getElement();
                        com.intellij.openapi.vfs.VirtualFile vf = el.getContainingFile() != null
                                ? el.getContainingFile().getVirtualFile() : null;
                        if (vf == null) continue;
                        int offset = el.getTextOffset();
                        com.intellij.openapi.editor.Document doc = com.intellij.openapi.fileEditor.FileDocumentManager
                                .getInstance().getDocument(vf);
                        int lineNo = -1;
                        String lineText = "";
                        if (doc != null && offset >= 0 && offset <= doc.getTextLength()) {
                            try {
                                int lineIdx = doc.getLineNumber(offset);
                                lineNo = lineIdx + 1;
                                lineText = doc.getText(new com.intellij.openapi.util.TextRange(
                                        doc.getLineStartOffset(lineIdx), doc.getLineEndOffset(lineIdx))).trim();
                            } catch (Exception ignored) { }
                        }
                        java.util.TreeMap<Integer, String> lines = byFile.computeIfAbsent(
                                vf.getPath(), k -> new java.util.TreeMap<>());
                        if (!lines.containsKey(lineNo)) {
                            lines.put(lineNo, lineText);
                            found[0]++;
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("ReferencesSearch failed for " + symbol, e);
                }
            }

            if (byFile.isEmpty()) {
                result[0] = "🔍 引用查找：\"" + symbol + "\"\n\n未找到任何引用（可能只在定义处存在）。";
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 符号 \"").append(symbol).append("\" 的引用/调用点：\n\n");
            for (var entry : byFile.entrySet()) {
                sb.append("## ").append(entry.getKey()).append("\n");
                for (var ln : entry.getValue().entrySet()) {
                    sb.append(String.format("  %d:%s%n", ln.getKey(), ln.getValue()));
                }
                sb.append("\n");
            }
            sb.append("共 ").append(found[0]).append(" 处引用\n");
            result[0] = sb.toString();
        });
        return result[0] != null ? result[0] : "错误：查找引用失败";
    }

    private static void addPsiClassPath(com.intellij.psi.PsiClass cls, List<String> results) {
        if (cls != null && cls.getContainingFile() != null
                && cls.getContainingFile().getVirtualFile() != null) {
            String path = cls.getContainingFile().getVirtualFile().getPath();
            if (!results.contains(path)) results.add(path);
        }
    }

    private static void addPsiMethodPath(com.intellij.psi.PsiMethod method, List<String> results) {
        if (method != null && method.getContainingFile() != null
                && method.getContainingFile().getVirtualFile() != null) {
            String path = method.getContainingFile().getVirtualFile().getPath();
            if (!results.contains(path)) results.add(path);
        }
    }

    /**
     * 查看文件大纲 —— 输入文件路径，返回类的属性、方法签名及注释（利用 PSI）
     */
    public static String viewFileOutline(String filePath, Project project) {
        if (filePath == null || filePath.isBlank() || project == null) return "错误：参数无效";

        // PsiManager.findFile / LocalFileSystem 等 PSI、VFS 访问需要 read-access；
        // 本方法会被后台线程池（view_file_outline 工具）调用，必须包 read-action。
        final String[] result = {null};
        final java.util.List<String> writtenPaths = new java.util.ArrayList<>();
        ReadAction.run(() -> {
            VirtualFile vf = findVirtualFile(filePath, project);
            if (vf == null) { result[0] = "错误：找不到文件 " + filePath; return; }

            com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) { result[0] = "错误：无法解析文件 " + filePath; return; }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 文件大纲：").append(vf.getName()).append("\n");
            sb.append("路径：").append(vf.getPath()).append("\n\n");

            // 尝试 Java PSI
            if (psiFile instanceof com.intellij.psi.PsiJavaFile) {
                com.intellij.psi.PsiJavaFile jf = (com.intellij.psi.PsiJavaFile) psiFile;
                for (com.intellij.psi.PsiClass cls : jf.getClasses()) {
                    appendClassOutline(cls, sb, 0);
                }
            } else if (vf.getName().toLowerCase().endsWith(".vue")) {
                // Vue 单文件组件：用文本解析生成大纲
                appendVueOutline(vf, sb);
            } else {
                // 回退：通用 PSI 提取顶层元素
                appendGenericOutline(psiFile, sb);
            }
            result[0] = sb.toString();
        });
        // ★ VFS 刷新必须放在 ReadAction 之外：读锁内同步刷新会触发 IDEA 死锁保护
        //   （"Do not perform a synchronous refresh under read lock"）。盘写已在读锁内完成，
        //   这里在释放读锁后刷新新写入的文件，确保 read_file_range 能立即读到。
        if (!writtenPaths.isEmpty()) {
            for (String p : writtenPaths) {
                try {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(p);
                } catch (Exception ignored) {}
            }
        }
        return result[0];
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
        int clsLine = getLineNumber(cls);
        if (clsLine > 0) sb.append("  (L").append(clsLine).append(")");
        sb.append("\n");

        // 字段
        for (com.intellij.psi.PsiField field : cls.getFields()) {
            String fDoc = field.getDocComment() != null ? trimDoc(field.getDocComment().getText()) : "";
            if (!fDoc.isEmpty()) sb.append(pad).append("  📝 ").append(fDoc).append("\n");
            int fLine = getLineNumber(field);
            sb.append(pad).append("  📦 ").append(field.getType().getPresentableText())
                    .append(" ").append(field.getName())
                    .append(fLine > 0 ? "  (L" + fLine + ")" : "").append("\n");
        }

        // 方法
        for (com.intellij.psi.PsiMethod method : cls.getMethods()) {
            String mDoc = method.getDocComment() != null ? trimDoc(method.getDocComment().getText()) : "";
            if (!mDoc.isEmpty()) sb.append(pad).append("  📝 ").append(mDoc).append("\n");
            int mLine = getLineNumber(method);
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
            sb.append(mLine > 0 ? "  (L" + mLine + ")" : "").append("\n");
        }

        // 内部类
        for (com.intellij.psi.PsiClass inner : cls.getInnerClasses()) {
            appendClassOutline(inner, sb, indent + 1);
        }
    }

    /**
     * 取 PSI 元素在文件中的 1-based 行号，供 outline 输出行号、让模型据此用 read_file_range 定点读取。
     * 取不到（无 VFS / 无 Document）时返回 -1，调用方据此跳过行号标注。
     */
    private static int getLineNumber(com.intellij.psi.PsiElement e) {
        if (e == null) return -1;
        try {
            com.intellij.psi.PsiFile pf = e.getContainingFile();
            if (pf == null) return -1;
            com.intellij.openapi.vfs.VirtualFile vf = pf.getVirtualFile();
            if (vf == null) return -1;
            com.intellij.openapi.editor.Document doc =
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return -1;
            return doc.getLineNumber(e.getTextOffset()) + 1;
        } catch (Exception ex) {
            return -1;
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

    private static void appendVueOutline(VirtualFile vf, StringBuilder sb) {
        String content = readFileContent(vf);
        if (content == null) {
            sb.append("  (无法读取文件内容)\n");
            return;
        }
        String[] lines = content.split("\n", -1);

        // 识别 SFC 块
        boolean inTemplate = false, inScript = false, inScriptSetup = false, inStyle = false;
        int templateStart = -1, scriptStart = -1, styleStart = -1;

        java.util.List<String> props = new java.util.ArrayList<>();
        java.util.List<String> emits = new java.util.ArrayList<>();
        java.util.List<String> refs = new java.util.ArrayList<>();
        java.util.List<String> computed = new java.util.ArrayList<>();
        java.util.List<String> functions = new java.util.ArrayList<>();
        java.util.List<String> components = new java.util.ArrayList<>();

        java.util.regex.Pattern definePropsPat = java.util.regex.Pattern.compile("defineProps\\s*\\(\\s*([\\{\\<])");
        java.util.regex.Pattern defineEmitsPat = java.util.regex.Pattern.compile("defineEmits\\s*\\(");
        java.util.regex.Pattern refPat = java.util.regex.Pattern.compile("const\\s+(\\w+)\\s*=\\s*(ref|shallowRef|reactive)\\s*\\(");
        java.util.regex.Pattern computedPat = java.util.regex.Pattern.compile("const\\s+(\\w+)\\s*=\\s*computed\\s*\\(");
        java.util.regex.Pattern funcPat = java.util.regex.Pattern.compile("(?:const\\s+(\\w+)\\s*=\\s*\\(|function\\s+(\\w+)\\s*\\()\\s*[^)]*\\)\\s*\\{");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 块检测
            if (line.startsWith("<template")) {
                inTemplate = true;
                templateStart = i + 1;
                continue;
            }
            if (line.startsWith("</template>")) { inTemplate = false; continue; }
            if (line.startsWith("<script")) {
                inScript = true;
                inScriptSetup = line.contains("setup");
                scriptStart = i + 1;
                continue;
            }
            if (line.startsWith("</script>")) { inScript = false; inScriptSetup = false; continue; }
            if (line.startsWith("<style")) { inStyle = true; styleStart = i + 1; continue; }
            if (line.startsWith("</style>")) { inStyle = false; continue; }

            if (!inScript && !inScriptSetup) continue;

            // props
            java.util.regex.Matcher m = definePropsPat.matcher(line);
            if (m.find()) {
                // 提取 props 名称（简单处理：找后面的 { 或 < 里面的内容）
                String propBlock = "";
                int braceCount = 0;
                boolean inBrace = false;
                for (int j = i; j < lines.length && j < i + 50; j++) {
                    String l = lines[j];
                    for (char c : l.toCharArray()) {
                        if (c == '{' || c == '<') { braceCount++; inBrace = true; }
                        else if (c == '}' || c == '>') { braceCount--; }
                    }
                    propBlock += l + "\n";
                    if (inBrace && braceCount <= 0) break;
                }
                // 简单提取 prop 名：行首的单词 + :
                java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(?:^|\\n)\\s*(\\w+)\\s*:").matcher(propBlock);
                while (pm.find()) {
                    String pn = pm.group(1);
                    if (!pn.equals("type") && !pn.equals("default") && !pn.equals("required")) {
                        if (!props.contains(pn)) props.add(pn);
                    }
                }
            }

            // emits
            if (defineEmitsPat.matcher(line).find()) {
                java.util.regex.Matcher em = java.util.regex.Pattern.compile("[\"'](\\w+)[\"']").matcher(line);
                while (em.find()) {
                    if (!emits.contains(em.group(1))) emits.add(em.group(1));
                }
            }

            // ref / reactive
            m = refPat.matcher(line);
            if (m.find()) {
                if (!refs.contains(m.group(1))) refs.add(m.group(1));
            }

            // computed
            m = computedPat.matcher(line);
            if (m.find()) {
                if (!computed.contains(m.group(1))) computed.add(m.group(1));
            }

            // functions
            m = funcPat.matcher(line);
            if (m.find()) {
                String fn = m.group(1) != null ? m.group(1) : m.group(2);
                if (fn != null && !fn.equals("ref") && !fn.equals("computed") && !fn.equals("reactive")) {
                    if (!functions.contains(fn)) functions.add(fn);
                }
            }
        }

        // 组件导入检测
        java.util.regex.Pattern importCompPat = java.util.regex.Pattern.compile("import\\s+\\{?\\s*(\\w+)\\s*\\}?\\s+from\\s+['\"].+\\.vue['\"]");
        for (String line : lines) {
            java.util.regex.Matcher m = importCompPat.matcher(line.trim());
            if (m.find()) {
                if (!components.contains(m.group(1))) components.add(m.group(1));
            }
        }

        // 输出大纲
        sb.append("  📦 Vue 单文件组件\n");
        if (templateStart > 0) sb.append("    📝 <template> 第 ").append(templateStart).append(" 行\n");
        if (scriptStart > 0) {
            sb.append("    📝 <script").append(inScriptSetup ? " setup" : "").append("> 第 ").append(scriptStart).append(" 行\n");
        }
        if (styleStart > 0) sb.append("    📝 <style> 第 ").append(styleStart).append(" 行\n");
        sb.append("\n");

        if (!props.isEmpty()) {
            sb.append("  📥 Props (").append(props.size()).append(")\n");
            for (String p : props) sb.append("    • ").append(p).append("\n");
            sb.append("\n");
        }
        if (!emits.isEmpty()) {
            sb.append("  📤 Emits (").append(emits.size()).append(")\n");
            for (String e : emits) sb.append("    • ").append(e).append("\n");
            sb.append("\n");
        }
        if (!refs.isEmpty()) {
            sb.append("  🔗 Refs / Reactive (").append(refs.size()).append(")\n");
            for (String r : refs.subList(0, Math.min(refs.size(), 20))) {
                sb.append("    • ").append(r).append("\n");
            }
            if (refs.size() > 20) sb.append("    ... 还有 ").append(refs.size() - 20).append(" 个\n");
            sb.append("\n");
        }
        if (!computed.isEmpty()) {
            sb.append("  🧮 Computed (").append(computed.size()).append(")\n");
            for (String c : computed) sb.append("    • ").append(c).append("\n");
            sb.append("\n");
        }
        if (!functions.isEmpty()) {
            sb.append("  🔧 方法 (").append(functions.size()).append(")\n");
            for (String f : functions) sb.append("    • ").append(f).append("\n");
            sb.append("\n");
        }
        if (!components.isEmpty()) {
            sb.append("  🧩 引用组件 (").append(components.size()).append(")\n");
            for (String c : components) sb.append("    • ").append(c).append("\n");
            sb.append("\n");
        }
    }

    private static String trimDoc(String doc) {
        if (doc == null) return "";
        return doc.replaceAll("\\s*\\*\\s*", " ").replaceAll("/\\*\\*|\\*/", "").trim();
    }

    /**
     * 全局搜索 —— 使用 IDEA 自带的 FindInProjectUtil（索引搜索）。
     * 自动覆盖所有模块 / 子模块，无目录递归 depth 限制，不依赖外部二进制。
     * 对标 AutoDev 的 SearchInFilesContentTool（同样用 FindManager + FindInProjectUtil）。
     */
        public static String searchGrep(String keyword, String filePattern, int maxResults, Project project) {
            return searchGrep(keyword, filePattern, maxResults, project, false);
        }
    
        /**
         * 重载：支持 regex 模式。regex=true 时 keyword 按 IDEA 的正则语义匹配；false 时按字面量。
         */
        public static String searchGrep(String keyword, String filePattern, int maxResults,
                                         Project project, boolean regex) {
            if (keyword == null || keyword.isBlank()) return "错误：请提供搜索关键词";
            if (project == null) return "错误：项目未打开";
            final int limit = maxResults <= 0 ? 10 : maxResults;
    
             com.intellij.find.FindModel findModel =
                     com.intellij.find.FindManager.getInstance(project).getFindInProjectModel().clone();
             findModel.setStringToFind(keyword);
             findModel.setCaseSensitive(false);
             findModel.setWholeWordsOnly(false);
             findModel.setRegularExpressions(regex);
        if (filePattern != null && !filePattern.isBlank()) {
            findModel.setFileFilter(filePattern);
        }

        // ★ FindInProjectUtil 在多线程上并发回调 consumer（processOnAllThreads + BoundedTaskExecutor），
        //   ArrayList 非线程安全 → 并发 add/size 竞态使内部数组损坏并抛 ArrayIndexOutOfBoundsException。
        //   改用 synchronizedList，并用同步块保证「检查-添加」原子性；攒够后返回 false 让搜索提前停止。
        final java.util.List<com.intellij.usageView.UsageInfo> usages =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.intellij.find.impl.FindInProjectUtil.findUsages(
                findModel,
                project,
                usageInfo -> {
                    synchronized (usages) {
                        if (usages.size() >= limit * 20) return false; // 已攒够，停止搜索
                        usages.add(usageInfo);
                        return true;
                    }
                },
                new com.intellij.usages.FindUsagesProcessPresentation(
                        new com.intellij.usages.UsageViewPresentation())
        );

        if (usages.isEmpty()) {
            return "🔍 全局搜索：\"" + keyword + "\"\n\n未找到匹配结果\n";
        }

        // 按文件分组（保持出现顺序）。★ 默认排除点开头隐藏目录（.git/.codebuddy/.gradle 等）
        //   及 node_modules/target/build 等构建产物目录 —— IDEA 索引搜索本身不过滤这些，
        //   导致历史记忆/构建产物噪音混进结果。
        java.util.Map<String, java.util.List<com.intellij.usageView.UsageInfo>> byFile =
                new java.util.LinkedHashMap<>();
        for (com.intellij.usageView.UsageInfo ui : usages) {
            com.intellij.openapi.vfs.VirtualFile vf = ui.getVirtualFile();
            if (vf == null) continue;
            if (isUnderSkippedDirName(vf.getPath())) continue;
            byFile.computeIfAbsent(vf.getPath(), k -> new java.util.ArrayList<>()).add(ui);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 全局搜索：\"").append(keyword).append("\"\n\n");
        int found = 0;
        for (java.util.List<com.intellij.usageView.UsageInfo> group : byFile.values()) {
            if (found >= limit) break;
            com.intellij.openapi.vfs.VirtualFile vf = group.get(0).getVirtualFile();
            if (vf == null) continue;
            sb.append("## ").append(vf.getPath()).append("\n");
            for (com.intellij.usageView.UsageInfo ui : group) {
                if (found >= limit) break;
                LineCtx ctx = readLineContext(vf, ui.getNavigationOffset());
                if (ctx == null) continue;
                found++;
                sb.append(String.format("  %d:%s\n", ctx.lineNo, ctx.line.trim()));
            }
            sb.append("\n");
        }
        sb.append("共找到 ").append(found).append(" 处匹配\n");
        return sb.toString();
    }

    /** 判断绝对路径中任一路径段是否属于应跳过的目录（点开头隐藏目录 / 构建产物等），供索引搜索结果过滤 */
     private static boolean isUnderSkippedDirName(String absolutePath) {
         if (absolutePath == null || absolutePath.isEmpty()) return false;
         for (String seg : absolutePath.replace('\\', '/').split("/")) {
             if (shouldSkipDir(seg)) return true;
         }
         return false;
     }

     /** 从命中偏移读取所在行（1-based 行号 + 行文本），用于 grep 结果展示。内部自行包 read action。 */
    private static LineCtx readLineContext(VirtualFile vf, int offset) {
        if (offset < 0) return null;
        final LineCtx[] holder = {null};
        ReadAction.run(() -> {
            try {
                com.intellij.openapi.editor.Document doc =
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) return;
                int lineIdx = doc.getLineNumber(offset);
                int start = doc.getLineStartOffset(lineIdx);
                int end = doc.getLineEndOffset(lineIdx);
                LineCtx ctx = new LineCtx();
                ctx.lineNo = lineIdx + 1;
                ctx.line = doc.getText(new com.intellij.openapi.util.TextRange(start, end));
                holder[0] = ctx;
            } catch (Exception ignored) { }
        });
        return holder[0];
    }

    private static class LineCtx {
        int lineNo;
        String line;
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

    public static VirtualFile findVirtualFile(String filePath, Project project) {
        VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath);
        if (vf != null && vf.exists()) return vf;
        if (project != null && project.getBaseDir() != null) {
            vf = project.getBaseDir().findFileByRelativePath(filePath);
            if (vf != null && vf.exists()) return vf;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 查看类源码（依赖 / 第三方 jar）—— 落盘到 temp，返回路径让模型按需读取
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 判断查看指定目标是否「需要反编译」（用于决定是否弹确认）。
     * 目标可以是全限定类名、.class 绝对路径、或整个 .jar 绝对路径。
     * 有 -sources.jar / 项目内 .java 源码 → 无需反编译（false）；其余 → 需反编译（true）。
     */
    public static boolean needsDecompile(String target, Project project) {
        if (target == null || target.isBlank() || project == null) return true;
        if (isJarPath(target)) return true;                     // 整 jar 反编译
        if (looksLikePath(target) && target.toLowerCase().endsWith(".class")) return true; // .class 必反编译
        // 当作全限定类名处理
        final boolean[] result = {true};
        ReadAction.run(() -> {
            com.intellij.psi.PsiClass cls = resolveSingleClass(target, project);
            if (cls == null) { result[0] = true; return; }
            com.intellij.psi.PsiFile cf = cls.getContainingFile();
            com.intellij.openapi.vfs.VirtualFile vf = cf != null ? cf.getVirtualFile() : null;
            if (vf == null) { result[0] = true; return; }
            boolean hasSources = "java".equalsIgnoreCase(vf.getExtension());
            result[0] = !hasSources;   // 有 .java 源码（sources.jar 或项目）都免反编译
        });
        return result[0];
    }

    /**
     * 查看类源码：自动定位并反编译，把结果写入临时目录（默认 <项目根>/temp/sources），
     * 返回写入/定位的文件路径列表，模型用 read_file_range 按需读取片段。
     *
     * <p>输入 target 支持三种形式：
     * <ul>
     *   <li>全限定类名，如 com.google.gson.Gson</li>
     *   <li>.class 文件绝对路径，如 D:/repo/x/gson-2.10.1.jar!/com/google/gson/Gson.class（或解压后的 .class）</li>
     *   <li>整个 .jar 绝对路径，如 D:/repo/x/gson-2.10.1.jar（整包反编译到输出目录）</li>
     * </ul>
     *
     * @param target    见上
     * @param outputDir 可选输出目录（相对项目根或绝对路径），默认 temp/sources
     * @param project   当前项目
     */
    public static String viewClassSource(String target, String outputDir, Project project) {
        if (target == null || target.isBlank()) return "错误：请提供类名或路径 (target)";
        if (project == null) return "错误：未打开项目";

        String base = project.getBaseDir() != null ? project.getBaseDir().getPath() : null;
        String outDirAbs = resolveOutDir(outputDir, base);

        // ── 整 jar 反编译（直接走 IDEA 内置 Fernflower，落盘到输出目录）──
        if (isJarPath(target)) {
            String jarAbs = toAbsolute(target, base);
            String err = decompileJar(jarAbs, outDirAbs);
            if (err != null) return err;
            return summarizeJarOutput(outDirAbs, jarAbs);
        }

        // ── 单个/少量类：解析 → 取源码/反编译 → 写 temp ──
        final String[] result = {null};
        final java.util.List<String> writtenPaths = new java.util.ArrayList<>();
        ReadAction.run(() -> {
            java.util.List<com.intellij.psi.PsiClass> classes = resolveClasses(target, project, base);
            if (classes.isEmpty()) {
                result[0] = "未找到类 \"" + target + "\"（不在已加载的依赖/源码中，或路径不正确；"
                        + "请确认依赖已引入且 IDEA 已重新索引）。可改用绝对路径重试，支持三种形式："
                        + "① 整个 .jar：D:/repo/x/foo.jar（整包反编译）；"
                        + "② jar 内单类：D:/repo/x/foo.jar!/com/foo/Bar.class；"
                        + "③ 散 .class：D:/proj/target/classes/com/foo/Bar.class";
                return;
            }

            StringBuilder files = new StringBuilder();
            int count = 0, totalLines = 0;
            String sourceType = null;
            boolean anyProjectSource = false;

            for (com.intellij.psi.PsiClass cls : classes) {
                com.intellij.psi.PsiFile cf = cls.getContainingFile();
                com.intellij.openapi.vfs.VirtualFile vf = cf != null ? cf.getVirtualFile() : null;
                String path = vf != null ? vf.getPath() : "(内存中)";
                boolean isJar = path.contains(".jar!");
                boolean hasSources = "java".equalsIgnoreCase(vf != null ? vf.getExtension() : "");

                // 项目内源码：不复制到 temp，直接返回原文件路径，模型用 read_file_range 读
                if (hasSources && !isJar && base != null && path.startsWith(base)) {
                    files.append("1. ").append(relPath(path, base)).append("  (项目内源码，直接用 read_file_range 读取)\n");
                    count++;
                    anyProjectSource = true;
                    continue;
                }

                String text;
                if (hasSources) {
                    sourceType = "源码包 (-sources.jar)";
                    text = cf.getText();
                } else {
                    sourceType = "IDEA 反编译 (Fernflower)";
                    try {
                        com.intellij.psi.PsiElement nav = cls.getNavigationElement();
                        if (nav instanceof com.intellij.psi.PsiClass) cls = (com.intellij.psi.PsiClass) nav;
                    } catch (Exception e) {
                        LOG.warn("导航反编译失败 for " + target, e);
                    }
                    com.intellij.psi.PsiFile navFile = cls.getContainingFile();
                    text = navFile != null ? navFile.getText() : null;
                }

                // IDEA Fernflower 反编译失败时，不会返回 null/空，而是返回一个非空桩文本
                // "This file was not decompiled"（常见于类被混淆、含不支持的字节码、
                // 或 IDEA 尚未完成该 jar 的索引）。它非空会绕过下方 isEmpty 检查，
                // 被误判为成功并写盘，导致模型读到垃圾内容却以为成功。这里显式识别为失败。
                if (text != null && text.toLowerCase().contains("not decompiled")) {
                    files.append("⚠️ 无法反编译 ").append(cls.getQualifiedName())
                            .append("：Fernflower 返回 \"未反编译\" 桩文本（位置：").append(path).append("）。\n")
                            .append("   常见原因：该类被混淆、含 IDEA 不支持的字节码，或依赖 jar 尚未被 IDEA 索引完成。\n")
                            .append("   建议：① 改用绝对路径指向该 .jar 重试，如 view_class_source(\"D:/repo/x/foo.jar\");\n")
                            .append("          ② 用 source-navigation 技能以 javap 看类签名/字节码；\n")
                            .append("          ③ 若项目内有真实源码，用 search_tool 直接搜类名定位 .java 读取。\n");
                    continue;
                }

                if (text == null || text.trim().isEmpty()) {
                    files.append("⚠️ 无法获取 ").append(cls.getQualifiedName())
                            .append(" 的源码（反编译失败或为空；位置：").append(path).append("）。\n")
                            .append("   建议：改用绝对路径的 .jar 重试，或用 search_tool 在项目内定位 .java 源码。\n");
                    continue;
                }

                String writtenPath = writeSourceFile(cls, text, outDirAbs);
                if (!writtenPath.contains("写入失败")) writtenPaths.add(writtenPath);
                int lines = text.split("\n", -1).length;
                totalLines += lines;
                count++;
                String rel = base != null && writtenPath.startsWith(base)
                        ? relPath(writtenPath, base) : writtenPath;
                files.append(count).append(". ").append(rel)
                        .append("  (约 ").append(lines).append(" 行)\n");
            }

            if (count == 0) {
                result[0] = "未能写出任何源码（反编译失败或定位异常）。可改用 source-navigation 技能手动反编译。";
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📦 源码已就绪（用 read_file_range 读取指定文件/片段）：\n");
            if (sourceType != null && !anyProjectSource) sb.append("来源：").append(sourceType).append("\n");
            sb.append("输出目录：").append(outDirAbs).append("\n");
            sb.append("共 ").append(count).append(" 个文件")
                    .append(totalLines > 0 ? "，约 " + totalLines + " 行" : "").append("\n\n");
            sb.append(files);
            if (count > 1 || totalLines > 800) {
                sb.append("\n提示：类较大，建议用 read_file_range 按行号分段读取，避免一次性占满上下文。");
            }
            result[0] = sb.toString();
        });
        // ★ VFS 刷新必须放在 ReadAction 之外：读锁内同步刷新会触发 IDEA 死锁保护
        //   （"Do not perform a synchronous refresh under read lock"）。盘写已在读锁内完成，
        //   这里在释放读锁后刷新新写入的文件，确保 read_file_range 能立即读到。
        if (!writtenPaths.isEmpty()) {
            for (String p : writtenPaths) {
                try {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(p);
                } catch (Exception ignored) {}
            }
        }
        return result[0];
    }

    // ── 内部辅助 ──

    private static boolean looksLikePath(String s) {
        return s.contains("/") || s.contains("\\") || s.matches("^[A-Za-z]:.*");
    }

    private static boolean isJarPath(String s) {
        String lower = s.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".jar!");
    }

    private static String toAbsolute(String path, String base) {
        if (new java.io.File(path).isAbsolute()) return path;
        if (base != null) return base + "/" + path;
        return path;
    }

    private static String resolveOutDir(String outputDir, String base) {
        String dir = (outputDir == null || outputDir.isBlank()) ? "temp/sources" : outputDir;
        if (new java.io.File(dir).isAbsolute()) return dir;
        return (base != null ? base + "/" + dir : dir);
    }

    private static String relPath(String abs, String base) {
        return abs.startsWith(base) ? abs.substring(base.length()).replace("\\", "/") : abs;
    }

    /** 解析单个类（全限定名路径），找不到返回 null */
    private static com.intellij.psi.PsiClass resolveSingleClass(String target, Project project) {
        return com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(target, GlobalSearchScope.allScope(project));
    }

    /**
     * 当 JavaPsiFacade.findClass（项目 scope）找不到类时的磁盘兜底。
     *
     * 典型失效场景：被分析项目是 Maven/Gradle 多模块，目标类在「另一个模块」里，
     * 而该模块未被 IDEA 作为依赖/源码索引进当前 scope（未编译、source 根未识别、target 被排除等），
     * 此时 findClass 返回 null。这里直接在被分析项目的目录树中按全限定名定位：
     *   1) 源码：src 下的 com/sec/.../X.java，用 LocalFileSystem 定位并解析为 PsiClass（首选，有真实源码）
     *   2) 编译产物：target/classes 下的 X.class（含 inner class）同样定位
     * 命中后返回与 target 全限定名匹配的 PsiClass。
     */
    private static com.intellij.psi.PsiClass resolveOnDisk(String target, Project project, String base) {
        if (base == null || base.isEmpty()) return null;
        String relJava = target.replace('.', '/') + ".java";
        String relClass = target.replace('.', '/') + ".class";
        String simple = target.substring(target.lastIndexOf('.') + 1);

        // 1) 先找源码 .java（最常见且信息最全）
        java.io.File srcFile = new java.io.File(base, relJava);
        com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().findFileByPath(srcFile.getAbsolutePath().replace('\\', '/'));
        if (vf != null && vf.exists()) {
            com.intellij.psi.PsiClass c = psiClassFromFile(vf, project, target);
            if (c != null) return c;
        }
        // 2) 再找编译产物 .class（含内部类 CommonFormulaRequest$Builder.class）
        java.io.File clsFile = new java.io.File(base, "target/classes/" + relClass);
        com.intellij.openapi.vfs.VirtualFile cvf = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().findFileByPath(clsFile.getAbsolutePath().replace('\\', '/'));
        if (cvf != null && cvf.exists()) {
            com.intellij.psi.PsiClass c = psiClassFromFile(cvf, project, target);
            if (c != null) return c;
        }
        // 3) 兜底：在 base 目录树下定位（多模块项目目标类在子模块内）
        java.io.File baseDir = new java.io.File(base);
        if (baseDir.isDirectory()) {
            // 3a) 优先尝试各子模块的常规源码/产物根，避免无谓深递归
            java.io.File[] modules = baseDir.listFiles();
            if (modules != null) {
                for (java.io.File mod : modules) {
                    if (!mod.isDirectory()) continue;
                    java.io.File srcJava = new java.io.File(mod, "src/main/java/" + relJava);
                    com.intellij.psi.PsiClass c = tryRead(srcJava, project, target);
                    if (c != null) return c;
                    java.io.File clsOut = new java.io.File(mod, "target/classes/" + relClass);
                    com.intellij.psi.PsiClass c2 = tryRead(clsOut, project, target);
                    if (c2 != null) return c2;
                }
            }
            // 3b) 仍找不到则全树递归（深度放宽到 16）
            java.io.File found = searchFileByName(baseDir, simple, new String[]{".java", ".class"}, 16);
            if (found != null) {
                com.intellij.openapi.vfs.VirtualFile fvf = com.intellij.openapi.vfs.LocalFileSystem
                        .getInstance().findFileByPath(found.getAbsolutePath().replace('\\', '/'));
                if (fvf != null) {
                    com.intellij.psi.PsiClass c = psiClassFromFile(fvf, project, target);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    /** 从某个 VirtualFile 解析出与 target 全限定名匹配的 PsiClass（忽略 inner class 差异） */
    private static com.intellij.psi.PsiClass psiClassFromFile(
            com.intellij.openapi.vfs.VirtualFile vf, Project project, String target) {
        try {
            com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
            if (psiFile instanceof com.intellij.psi.PsiClassOwner) {
                for (com.intellij.psi.PsiClass c : ((com.intellij.psi.PsiClassOwner) psiFile).getClasses()) {
                    String qn = c.getQualifiedName();
                    if (qn != null && (qn.equals(target) || qn.startsWith(target + "$"))) return c;
                }
            }
        } catch (Exception e) {
            LOG.warn("psiClassFromFile failed for " + vf.getPath(), e);
        }
        return null;
    }

    /** 若磁盘文件存在，定位并解析出与 target 匹配的 PsiClass；不存在或解析失败返回 null */
    private static com.intellij.psi.PsiClass tryRead(java.io.File f, Project project, String target) {
        if (!f.isFile()) return null;
        com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().findFileByPath(f.getAbsolutePath().replace('\\', '/'));
        if (vf == null || !vf.exists()) return null;
        return psiClassFromFile(vf, project, target);
    }

    /** 在 dir 下按文件名（含内部类前缀 simple + "$"）递归查找，最多递归 maxDepth 层 */
    private static java.io.File searchFileByName(java.io.File dir, String simple,
                                                 String[] exts, int maxDepth) {
        if (maxDepth <= 0 || !dir.isDirectory()) return null;
        java.io.File[] children = dir.listFiles();
        if (children == null) return null;
        for (java.io.File f : children) {
            if (f.isDirectory()) continue;
            String name = f.getName();
            for (String ext : exts) {
                if (name.equals(simple + ext) || name.startsWith(simple + "$")) {
                    if (name.endsWith(ext)) return f;
                }
            }
        }
        for (java.io.File f : children) {
            if (f.isDirectory()) {
                java.io.File r = searchFileByName(f, simple, exts, maxDepth - 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 把 target 解析成 PsiClass 列表：支持全限定名与 .class 文件绝对路径 */
    private static java.util.List<com.intellij.psi.PsiClass> resolveClasses(
            String target, Project project, String base) {
        java.util.List<com.intellij.psi.PsiClass> list = new java.util.ArrayList<>();
        if (looksLikePath(target) && target.toLowerCase().endsWith(".class")) {
            com.intellij.openapi.vfs.VirtualFile vf = findVirtualFile(toAbsolute(target, base), project);
            if (vf != null) {
                com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                if (psiFile instanceof com.intellij.psi.PsiClassOwner) {
                    for (com.intellij.psi.PsiClass c : ((com.intellij.psi.PsiClassOwner) psiFile).getClasses()) {
                        list.add(c);
                    }
                }
            }
            return list;
        }
        com.intellij.psi.PsiClass cls = resolveSingleClass(target, project);
        if (cls == null) {
            // 项目 scope 找不到（多模块未索引 / target 排除等），退回磁盘按全限定名定位
            cls = resolveOnDisk(target, project, base);
        }
        if (cls != null) list.add(cls);
        return list;
    }

    /** 取出某个类的源码文本（有 .java 源码直接读；否则用导航元素拿 Fernflower 反编译文本） */
    private static String decompileClassText(com.intellij.psi.PsiClass cls) {
        com.intellij.psi.PsiFile cf = cls.getContainingFile();
        if (cf != null && "java".equalsIgnoreCase(cf.getVirtualFile() != null ? cf.getVirtualFile().getExtension() : "")) {
            return cf.getText();
        }
        try {
            com.intellij.psi.PsiElement nav = cls.getNavigationElement();
            if (nav instanceof com.intellij.psi.PsiClass) {
                com.intellij.psi.PsiFile nf = ((com.intellij.psi.PsiClass) nav).getContainingFile();
                return nf != null ? nf.getText() : null;
            }
        } catch (Exception e) {
            LOG.warn("导航反编译失败 for " + cls.getQualifiedName(), e);
        }
        return cf != null ? cf.getText() : null;
    }

    /** 把类源码写到 <outDir>/<package>/<Class>.java，返回绝对路径 */
    private static String writeSourceFile(com.intellij.psi.PsiClass cls, String text, String outDirAbs) {
        String qn = cls.getQualifiedName();
        if (qn == null) qn = cls.getName() != null ? cls.getName() : "UnknownClass";
        String pkg = qn.contains(".") ? qn.substring(0, qn.lastIndexOf('.')) : "";
        String simple = qn.contains(".") ? qn.substring(qn.lastIndexOf('.') + 1) : qn;
        String fileName = simple.replace('$', '.') + ".java";
        Path dir = Paths.get(outDirAbs, pkg.replace('.', '/'));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, text);
            return file.toString();
        } catch (Exception e) {
            LOG.warn("写入源码文件失败: " + dir + "/" + fileName, e);
            return outDirAbs + "/" + fileName + "  (写入失败: " + e.getMessage() + ")";
        }
    }

    /** 用 IDEA 内置 Fernflower 把整个 jar 反编译到 outDir；成功返回 null，失败返回错误信息 */
    private static String decompileJar(String jarPath, String outDirAbs) {
        try {
            String home = com.intellij.openapi.application.PathManager.getHomePath();
            java.io.File dec = new java.io.File(home, "plugins/java-decompiler/lib/java-decompiler.jar");
            if (!dec.exists()) {
                return "未找到 IDEA 内置反编译器：" + dec.getAbsolutePath()
                        + "\n可改用 source-navigation 技能手动反编译。";
            }
            java.io.File out = new java.io.File(outDirAbs);
            out.mkdirs();
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", dec.getAbsolutePath(),
                    "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler", jarPath, outDirAbs);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream is = p.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            }
            boolean finished = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "反编译超时（180s）：" + jarPath;
            }
            if (p.exitValue() != 0) {
                return "反编译失败，退出码 " + p.exitValue() + "：\n" + buf.toString();
            }
            // ConsoleDecompiler 把结果写成一个 zip（按输入 jar 命名）放进输出目录，
            // 需解压成散落的 .java 文件，否则 summarizeJarOutput 扫不到。
            extractResultZip(outDirAbs, jarPath);
            return null;
        } catch (Exception e) {
            return "反编译异常：" + e.getMessage();
        }
    }

    /** ConsoleDecompiler 产出的是 zip（按输入 jar 命名，如 x.jar），解压成散落 .java 到 outDirAbs，并删除 zip */
    private static void extractResultZip(String outDirAbs, String jarPath) {
        java.io.File dir = new java.io.File(outDirAbs);
        if (!dir.isDirectory()) return;
        // 优先按输入 jar 的同名文件（ConsoleDecompiler 的命名约定）
        java.io.File zip = new java.io.File(outDirAbs, new java.io.File(jarPath).getName());
        if (!zip.isFile()) {
            // 兜底：扫描目录里最新的 *.jar / *.zip（结果 zip）
            java.io.File[] zips = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".jar") || n.endsWith(".zip");
            });
            if (zips != null && zips.length > 0) {
                zip = java.util.Arrays.stream(zips)
                        .max(java.util.Comparator.comparingLong(java.io.File::lastModified))
                        .orElse(null);
            }
        }
        if (zip == null || !zip.isFile()) return;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                java.io.File out = new java.io.File(outDirAbs, e.getName());
                java.io.File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (java.io.InputStream in = zf.getInputStream(e);
                     java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] b = new byte[8192];
                    int r;
                    while ((r = in.read(b)) > 0) os.write(b, 0, r);
                }
            }
            zip.delete();
        } catch (Exception ex) {
            LOG.warn("解压反编译结果 zip 失败: " + zip.getAbsolutePath(), ex);
        }
        // 刷新 VFS，使新解压的 .java 立即可被 read_file_range 读取（与单类反编译路径一致）
        try {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(outDirAbs);
        } catch (Exception ignored) { }
    }

    /** 汇总整 jar 反编译后的输出目录（列出 .java 文件数量与树） */
    private static String summarizeJarOutput(String outDirAbs, String jarPath) {
        try {
            java.util.stream.Stream<Path> walk = Files.walk(Paths.get(outDirAbs));
            java.util.List<Path> javaFiles = walk
                    .filter(p -> p.toString().toLowerCase().endsWith(".java"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            StringBuilder sb = new StringBuilder();
            sb.append("📦 整包反编译完成（用 read_file_range 按需读取）：\n");
            sb.append("Jar：").append(jarPath).append("\n");
            sb.append("输出目录：").append(outDirAbs).append("\n");
            sb.append("共 ").append(javaFiles.size()).append(" 个 .java 文件\n\n");
            int limit = Math.min(javaFiles.size(), 200);
            for (int i = 0; i < limit; i++) {
                sb.append((i + 1)).append(". ").append(relPath(javaFiles.get(i).toString(), outDirAbs)).append("\n");
            }
            if (javaFiles.size() > limit) {
                sb.append("... 还有 ").append(javaFiles.size() - limit).append(" 个文件\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "📦 反编译已完成，输出目录：" + outDirAbs + "（列目录失败：" + e.getMessage() + "）";
        }
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
                ".py", ".js", ".ts", ".jsx", ".tsx", ".vue",
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
