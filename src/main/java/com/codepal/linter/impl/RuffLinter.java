package com.codepal.linter.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.codepal.linter.LintIssue;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.ShellBasedLinter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Ruff linter —— Python 极速 linter
 *
 * 支持项目虚拟环境（venv/.venv）和全局安装
 * 使用 --output-format json 输出
 */
public class RuffLinter extends ShellBasedLinter {

    @Override
    public String getName() {
        return "Ruff";
    }

    @Override
    public String getDescription() {
        return "Python 极速代码检查工具（替代 flake8/pylint）";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("py", "pyi", "ipynb");
    }

    @Override
    protected String getVersionCommand() {
        return "ruff --version";
    }

    @Override
    protected String getLintCommand(String filePath, String projectPath) {
        String cmd = resolveCommand(projectPath);
        if (cmd == null) cmd = "ruff";
        // ruff check --output-format json
        return "\"" + cmd + "\" check --output-format json \"" + filePath + "\"";
    }

    @Override
    protected String findLocalCommand(String projectPath) {
        // 检查常见虚拟环境目录
        String[] venvDirs = {"venv", ".venv", "env", ".env"};
        for (String dir : venvDirs) {
            String path = projectPath + File.separator + dir + File.separator + "bin" + File.separator + "ruff";
            if (new File(path).exists()) return path;
            // Windows
            String winPath = projectPath + File.separator + dir + File.separator + "Scripts" + File.separator + "ruff.exe";
            if (new File(winPath).exists()) return winPath;
        }
        return null;
    }

    @Override
    protected List<LintIssue> parseOutput(String stdout, String stderr, String filePath) {
        List<LintIssue> issues = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return issues;

        try {
            JsonArray results = JsonParser.parseString(stdout.trim()).getAsJsonArray();
            for (int i = 0; i < results.size(); i++) {
                JsonObject item = results.get(i).getAsJsonObject();

                String fname = item.has("filename") ? item.get("filename").getAsString() : "";

                JsonObject location = item.has("location") ? item.getAsJsonObject("location") : null;
                if (location == null) continue;

                int row = location.has("row") ? location.get("row").getAsInt() : 0;
                int col = location.has("column") ? location.get("column").getAsInt() : 0;

                String code = item.has("code") ? item.get("code").getAsString() : "";
                String message = item.has("message") ? item.get("message").getAsString() : "";
                boolean hasFix = item.has("fix") && !item.get("fix").isJsonNull();

                LintSeverity severity;
                if (code.startsWith("E") || code.startsWith("F")) {
                    severity = LintSeverity.ERROR;
                } else if (code.startsWith("W")) {
                    severity = LintSeverity.WARNING;
                } else {
                    severity = LintSeverity.INFO;
                }

                LintIssue issue = new LintIssue(row, col, severity, message, code);
                if (hasFix) {
                    issue.suggestion = "可自动修复 (ruff --fix)";
                }
                issues.add(issue);
            }
        } catch (Exception e) {
        }

        return issues;
    }

    @Override
    public String getInstallationInstructions() {
        return "pip install ruff  或  pipx install ruff";
    }
}
