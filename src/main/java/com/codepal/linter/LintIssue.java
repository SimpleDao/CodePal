package com.codepal.linter;

/**
 * 单个 lint 问题
 */
public class LintIssue {
    public int line;
    public int column;
    public LintSeverity severity;
    public String message;
    public String rule;
    public String suggestion;
    public String filePath;

    public LintIssue(int line, int column, LintSeverity severity, String message, String rule) {
        this.line = line;
        this.column = column;
        this.severity = severity;
        this.message = message;
        this.rule = rule;
    }

    public LintIssue(int line, LintSeverity severity, String message, String rule) {
        this(line, 0, severity, message, rule);
    }
}
