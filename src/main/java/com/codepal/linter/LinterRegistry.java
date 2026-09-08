package com.codepal.linter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Linter 注册表 —— 管理所有可用的 linter
 */
public class LinterRegistry {

    private static final ConcurrentHashMap<String, Linter> linters = new ConcurrentHashMap<>();
    private static boolean initialized = false;

    /** IDE 内置 linter 实例（不按扩展名匹配，始终可用） */
    private static final Linter ideLinter = new com.codepal.linter.impl.IdeInspectionsLinter();

    /**
     * 注册所有内置 linter
     */
    public static synchronized void initialize() {
        if (initialized) return;

        // IDE 内置检查 — 最高优先级
        register(ideLinter);

        // JavaScript / TypeScript / Vue
        register(new com.codepal.linter.impl.EsLintLinter());

        // Python
        register(new com.codepal.linter.impl.RuffLinter());

        // Java
        register(new com.codepal.linter.impl.PmdLinter());

        // Shell
        register(new com.codepal.linter.impl.ShellCheckLinter());

        initialized = true;
    }

    public static void register(Linter linter) {
        linters.put(linter.getName().toLowerCase(), linter);
    }

    public static Linter getLinter(String name) {
        initialize();
        return linters.get(name.toLowerCase());
    }

    public static List<Linter> getAllLinters() {
        initialize();
        return new ArrayList<>(linters.values());
    }

    /**
     * 根据文件扩展名找到适用的 linter
     */
    public static List<Linter> findLintersForFile(String filePath, String projectPath) {
        initialize();
        List<Linter> result = new ArrayList<>();

        // IDE 内置 linter 始终可用，不受扩展名限制
        if (ideLinter.isAvailable(projectPath)) {
            result.add(ideLinter);
        }

        // 按扩展名匹配外部 linter
        String ext = getExtension(filePath).toLowerCase();
        for (Linter linter : linters.values()) {
            if (linter == ideLinter) continue; // 已添加
            if (linter.getSupportedExtensions().contains(ext)) {
                if (linter.isAvailable(projectPath)) {
                    result.add(linter);
                }
            }
        }
        return result;
    }

    /**
     * 对单个文件运行所有适用的 linter
     */
    public static List<LintResult> lintFile(String filePath, String projectPath) {
        List<Linter> suitable = findLintersForFile(filePath, projectPath);
        List<LintResult> results = new ArrayList<>();
        for (Linter linter : suitable) {
            try {
                results.add(linter.lintFile(filePath, projectPath));
            } catch (Exception e) {
                // 单个 linter 失败不影响其他
            }
        }
        return results;
    }

    /**
     * 格式化 lint 结果为可读文本
     */
    public static String formatResults(List<LintResult> results) {
        if (results == null || results.isEmpty()) {
            return "未找到可用的 linter（可安装 ESLint / Ruff / PMD 等）";
        }

        StringBuilder sb = new StringBuilder();
        int totalErrors = 0;
        int totalWarnings = 0;
        int totalInfos = 0;

        for (LintResult result : results) {
            if (!result.success && result.issues.isEmpty()) {
                sb.append("⚠️ ").append(result.linterName).append(" 执行失败");
                if (result.errorMessage != null) {
                    sb.append(": ").append(result.errorMessage, 0, Math.min(200, result.errorMessage.length()));
                }
                sb.append("\n\n");
                continue;
            }

            if (!result.hasIssues()) {
                sb.append("✅ ").append(result.linterName).append(": 未发现问题\n\n");
                continue;
            }

            sb.append("📋 ").append(result.linterName)
                    .append(" —— ").append(result.getErrorCount()).append(" 错误, ")
                    .append(result.getWarningCount()).append(" 警告, ")
                    .append(result.getInfoCount()).append(" 提示\n");
            sb.append("─────────────────────────────────────────────\n");

            totalErrors += result.getErrorCount();
            totalWarnings += result.getWarningCount();
            totalInfos += result.getInfoCount();

            // 按严重程度排序，最多显示 30 条
            List<LintIssue> sorted = result.issues.stream()
                    .sorted((a, b) -> {
                        int sa = severityOrder(a.severity);
                        int sb2 = severityOrder(b.severity);
                        if (sa != sb2) return sa - sb2;
                        return a.line - b.line;
                    })
                    .limit(30)
                    .toList();

            for (LintIssue issue : sorted) {
                sb.append(issue.severity.getLabel())
                        .append(" 第").append(issue.line).append("行");
                if (issue.column > 0) sb.append(":").append(issue.column);
                if (issue.rule != null && !issue.rule.isBlank()) {
                    sb.append(" [").append(issue.rule).append("]");
                }
                sb.append("\n   ").append(issue.message).append("\n");
                if (issue.suggestion != null && !issue.suggestion.isBlank()) {
                    sb.append("   💡 建议: ").append(issue.suggestion).append("\n");
                }
            }

            if (result.issues.size() > 30) {
                sb.append("\n   ... 还有 ").append(result.issues.size() - 30).append(" 个问题\n");
            }
            sb.append("\n");
        }

        // 汇总
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📊 总计: ").append(totalErrors).append(" 错误, ")
                .append(totalWarnings).append(" 警告, ")
                .append(totalInfos).append(" 提示\n");

        return sb.toString();
    }

    private static int severityOrder(LintSeverity s) {
        return switch (s) {
            case ERROR -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }

    private static String getExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) return "";
        return filePath.substring(dot + 1);
    }
}
