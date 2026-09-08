package com.codepal.linter;

import java.util.List;

/**
 * 单个文件的 lint 结果
 */
public class LintResult {
    public String filePath;
    public String linterName;
    public List<LintIssue> issues;
    public boolean success;
    public String errorMessage;

    public LintResult(String filePath, String linterName, List<LintIssue> issues, boolean success) {
        this.filePath = filePath;
        this.linterName = linterName;
        this.issues = issues;
        this.success = success;
    }

    public int getErrorCount() {
        return (int) issues.stream().filter(i -> i.severity == LintSeverity.ERROR).count();
    }

    public int getWarningCount() {
        return (int) issues.stream().filter(i -> i.severity == LintSeverity.WARNING).count();
    }

    public int getInfoCount() {
        return (int) issues.stream().filter(i -> i.severity == LintSeverity.INFO).count();
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
