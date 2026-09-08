package com.codepal.linter.impl;

import com.codepal.linter.LintIssue;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.ShellBasedLinter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PMD linter —— Java 静态代码分析
 *
 * 使用 PMD CLI，支持自定义规则集
 */
public class PmdLinter extends ShellBasedLinter {

    // 匹配 PMD 输出格式: filename:line:  priority: message (rule)
    private static final Pattern PMD_PATTERN = Pattern.compile(
            "(.+?):(\\d+):\\s*(\\d+)?:?\\s*(\\w+):\\s*(.+?)\\s*\\((\\w+)\\)\\s*$"
    );

    @Override
    public String getName() {
        return "PMD";
    }

    @Override
    public String getDescription() {
        return "Java 静态代码分析工具（Bug 检测、坏味道、最佳实践等）";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("java");
    }

    @Override
    protected String getVersionCommand() {
        return "pmd --version";
    }

    @Override
    protected String getLintCommand(String filePath, String projectPath) {
        String cmd = resolveCommand(projectPath);
        if (cmd == null) cmd = "pmd";
        // pmd check -f text -R rulesets/java/quickstart.xml -d filepath
        // 用 quickstart 规则集，比较通用
        return "\"" + cmd + "\" check -f text -R rulesets/java/quickstart.xml -d \"" + filePath + "\"";
    }

    @Override
    protected String findLocalCommand(String projectPath) {
        // PMD 一般是全局安装或 Maven/Gradle 插件，这里先检查常见路径
        // 后续可以扩展支持 mvn pmd:pmd / gradle pmdMain
        return null;
    }

    @Override
    protected List<LintIssue> parseOutput(String stdout, String stderr, String filePath) {
        List<LintIssue> issues = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return issues;

        String[] lines = stdout.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 跳过 PMD 的 header/footer 信息
            if (line.startsWith("WARNING:") || line.startsWith("This analysis could be")
                    || line.startsWith("PMD") || line.startsWith("+")
                    || line.startsWith("Updated") || line.startsWith("Downloaded")
                    || line.startsWith("Ruleset:")) {
                continue;
            }

            try {
                Matcher m = PMD_PATTERN.matcher(line);
                if (m.find()) {
                    int lineNum = Integer.parseInt(m.group(2));
                    String priorityStr = m.group(4);
                    String message = m.group(5);
                    String rule = m.group(6);

                    // PMD priority: 1=最高, 5=最低
                    LintSeverity severity;
                    int priority = 3;
                    try { priority = Integer.parseInt(priorityStr.trim()); } catch (Exception ignored) {}

                    severity = switch (priority) {
                        case 1 -> LintSeverity.ERROR;
                        case 2, 3 -> LintSeverity.WARNING;
                        default -> LintSeverity.INFO;
                    };

                    issues.add(new LintIssue(lineNum, 0, severity, message, rule));
                }
            } catch (Exception e) {
                // 跳过解析失败的行
            }
        }

        return issues;
    }

    @Override
    public String getInstallationInstructions() {
        return "下载 PMD: https://pmd.github.io/  或通过 Maven/Gradle 插件使用";
    }
}
