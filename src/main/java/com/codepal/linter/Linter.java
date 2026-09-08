package com.codepal.linter;

import java.util.List;

/**
 * Linter 接口
 */
public interface Linter {
    String getName();
    String getDescription();
    List<String> getSupportedExtensions();
    boolean isAvailable(String projectPath);
    LintResult lintFile(String filePath, String projectPath);
    String getInstallationInstructions();
}
