package com.codepal.linter.impl;

import com.codepal.linter.LintIssue;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.ShellBasedLinter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShellCheck linter —— Shell / Bash 脚本检查
 *
 * 使用 shellcheck -f gcc 输出格式
 */
public class ShellCheckLinter extends ShellBasedLinter {

    // 匹配 gcc 格式: file:line:column: severity: message [SCxxxx]
    private static final Pattern GCC_PATTERN = Pattern.compile(
            "(.+?):(\\d+):(\\d+):\\s*(\\w+):\\s*(.+?)\\s*(\\[SC\\d+\\])?\\s*$"
    );

    @Override
    public String getName() {
        return "ShellCheck";
    }

    @Override
    public String getDescription() {
        return "Shell / Bash 脚本静态分析工具";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("sh", "bash", "zsh", "ksh");
    }

    @Override
    protected String getVersionCommand() {
        return "shellcheck --version";
    }

    @Override
    protected String getLintCommand(String filePath, String projectPath) {
        // shellcheck -f gcc <file>
        return "shellcheck -f gcc \"" + filePath + "\"";
    }

    @Override
    protected List<LintIssue> parseOutput(String stdout, String stderr, String filePath) {
        List<LintIssue> issues = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return issues;

        String[] lines = stdout.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            Matcher m = GCC_PATTERN.matcher(line);
            if (m.find()) {
                try {
                    int lineNum = Integer.parseInt(m.group(2));
                    int col = Integer.parseInt(m.group(3));
                    String severityStr = m.group(4).toLowerCase();
                    String message = m.group(5);
                    String rule = m.group(6);
                    if (rule != null) rule = rule.replaceAll("[\\[\\]]", "");

                    LintSeverity severity = switch (severityStr) {
                        case "error" -> LintSeverity.ERROR;
                        case "warning" -> LintSeverity.WARNING;
                        case "info", "note" -> LintSeverity.INFO;
                        default -> LintSeverity.INFO;
                    };

                    issues.add(new LintIssue(lineNum, col, severity, message, rule));
                } catch (Exception e) {
                    // 跳过
                }
            }
        }

        return issues;
    }

    @Override
    public String getInstallationInstructions() {
        return "Windows: scoop install shellcheck  |  macOS: brew install shellcheck  |  Linux: apt install shellcheck";
    }
}
