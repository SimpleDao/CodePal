package com.codepal.linter.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.codepal.linter.LintIssue;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.ShellBasedLinter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ESLint linter —— 支持 JavaScript / TypeScript / Vue
 *
 * 支持项目本地 node_modules 中的 eslint
 * 使用 --format json 输出，解析结构化结果
 */
public class EsLintLinter extends ShellBasedLinter {

    @Override
    public String getName() {
        return "ESLint";
    }

    @Override
    public String getDescription() {
        return "JavaScript / TypeScript / Vue 代码检查工具";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("js", "jsx", "ts", "tsx", "vue", "mjs", "cjs");
    }

    @Override
    protected String getVersionCommand() {
        return "eslint --version";
    }

    @Override
    protected String getLintCommand(String filePath, String projectPath) {
        String cmd = resolveCommand(projectPath);
        if (cmd == null) cmd = "eslint";
        // 用 JSON 格式输出，方便解析
        // 加上 --no-error-on-unmatched-pattern 避免文件不匹配时报错
        return "\"" + cmd + "\" --format json --no-error-on-unmatched-pattern \"" + filePath + "\"";
    }


    @Override
    protected String findLocalCommand(String projectPath) {
        String local = projectPath + File.separator + "node_modules" + File.separator + ".bin" + File.separator + "eslint";
        if (new File(local).exists() || new File(local + ".cmd").exists()) {
            return local;
        }
        return null;
    }

    @Override
    protected List<LintIssue> parseOutput(String stdout, String stderr, String filePath) {
        List<LintIssue> issues = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return issues;

        try {
            JsonArray results = JsonParser.parseString(stdout.trim()).getAsJsonArray();
            if (results.size() == 0) return issues;

            JsonObject fileResult = results.get(0).getAsJsonObject();
            if (!fileResult.has("messages")) return issues;
            JsonArray messages = fileResult.getAsJsonArray("messages");

            for (int i = 0; i < messages.size(); i++) {
                JsonObject msg = messages.get(i).getAsJsonObject();
                int line = msg.has("line") ? msg.get("line").getAsInt() : 0;
                int column = msg.has("column") ? msg.get("column").getAsInt() : 0;
                String severityStr = msg.has("severity") ? msg.get("severity").getAsString() : "1";
                String message = msg.has("message") ? msg.get("message").getAsString() : "";
                String rule = msg.has("ruleId") ? msg.get("ruleId").getAsString() : "";

                LintSeverity severity;
                if ("2".equals(severityStr)) {
                    severity = LintSeverity.ERROR;
                } else {
                    severity = LintSeverity.WARNING;
                }

                LintIssue issue = new LintIssue(line, column, severity, message, rule);
                if (msg.has("fix")) {
                    issue.suggestion = "可自动修复";
                }
                issues.add(issue);
            }
        } catch (Exception e) {
        }

        return issues;
    }

    @Override
    public String getInstallationInstructions() {
        return "npm install --save-dev eslint  (项目本地安装，推荐) 或  npm install -g eslint  (全局安装)";
    }
}
