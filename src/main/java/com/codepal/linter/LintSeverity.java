package com.codepal.linter;

/**
 * Lint 问题严重等级
 */
public enum LintSeverity {
    ERROR("🔴 错误"),
    WARNING("🟡 警告"),
    INFO("🔵 提示");

    private final String label;
    LintSeverity(String label) { this.label = label; }
    public String getLabel() { return label; }
}
