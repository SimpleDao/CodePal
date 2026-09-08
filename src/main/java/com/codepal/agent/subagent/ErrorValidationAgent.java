package com.codepal.agent.subagent;

import com.intellij.openapi.project.Project;
import com.codepal.linter.LintIssue;
import com.codepal.linter.LintResult;
import com.codepal.linter.LintSeverity;
import com.codepal.linter.LinterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错误验证子智能体 —— 兜底验证，确保代码质量
 *
 * <p>职责：
 * <ul>
 *   <li>主模型处理完复杂任务后，对修改过的文件进行 linter 验证</li>
 *   <li>逐个文件检查，发现潜在 BUG 和代码质量问题</li>
 *   <li>汇总错误报告反馈给主模型，作为兜底验证</li>
 *   <li>串行执行（逐个文件验证，无须并行）</li>
 * </ul>
 *
 * <p>设计思想：
 * 当主模型处理的任务非常重和长（如重构大量代码、生成多个文件）时，
 * 最后一关需要错误验证智能体进行快速检查。该智能体只负责验证，
 * 不负责修改，发现问题后汇总报告给主模型，由主模型决定如何修复。
 *
 * @author CP Multi-Agent
 */
public class ErrorValidationAgent extends SubAgent<List<String>, AgentResult> {

    private static final String AGENT_NAME = "error-validation-agent";
    private static final String AGENT_DESC = "代码错误验证器，对文件进行 linter 检查，发现潜在 BUG 和代码质量问题";

    public ErrorValidationAgent(Project project) {
        super(AGENT_NAME, AGENT_DESC, project);
        this.priority = 200;
    }

    @Override
    public boolean shouldTrigger(Map<String, Object> context) {
        if (context == null) return false;
        Object taskType = context.get("taskType");
        if (taskType != null && "codeGeneration".equals(taskType.toString())) {
            return true;
        }
        Object files = context.get("modifiedFiles");
        return files != null && files instanceof List && !((List<?>) files).isEmpty();
    }

    @Override
    public AgentResult execute(List<String> filePaths, ProgressCallback callback) {
        if (filePaths == null || filePaths.isEmpty()) {
            return AgentResult.error("没有需要验证的文件");
        }

        String projectPath = project.getBasePath();
        if (projectPath == null) {
            projectPath = System.getProperty("user.dir");
        }

        List<AgentResult.FileFinding> allFindings = new ArrayList<>();
        int totalFiles = filePaths.size();
        int filesWithErrors = 0;
        int totalErrors = 0;

        if (callback != null) {
            callback.onProgress("开始验证 " + totalFiles + " 个文件...");
        }

        for (int i = 0; i < filePaths.size(); i++) {
            String filePath = filePaths.get(i);

            if (callback != null) {
                callback.onStep("validating",
                        "[" + (i + 1) + "/" + totalFiles + "] " + filePath);
            }

            try {
                List<LintResult> results = LinterRegistry.lintFile(filePath, projectPath);
                boolean fileHasError = false;

                for (LintResult result : results) {
                    if (!result.success) {
                        continue;
                    }

                    if (result.getErrorCount() > 0) {
                        if (!fileHasError) {
                            filesWithErrors++;
                            fileHasError = true;
                        }

                        for (LintIssue issue : result.issues) {
                            if (issue.severity != LintSeverity.ERROR) {
                                continue;
                            }

                            totalErrors++;

                            String desc = issue.message;
                            if (issue.rule != null && !issue.rule.isBlank()) {
                                desc = "[" + issue.rule + "] " + desc;
                            }

                            allFindings.add(new AgentResult.FileFinding(
                                    filePath,
                                    issue.line,
                                    issue.line,
                                    desc,
                                    "error"
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[ErrorValidationAgent] 验证文件失败: " + filePath + ", " + e.getMessage());
            }
        }

        if (callback != null) {
            callback.onProgress("验证完成：" + totalFiles + " 个文件，"
                    + totalErrors + " 个错误");
        }

        String summary = buildSummary(totalFiles, filesWithErrors, totalErrors, allFindings);

        return new AgentResult.Builder()
                .success(true)
                .content(summary)
                .findings(allFindings)
                .addMetadata("totalFiles", totalFiles)
                .addMetadata("filesWithErrors", filesWithErrors)
                .addMetadata("totalErrors", totalErrors)
                .build();
    }

    private String buildSummary(int totalFiles, int filesWithErrors,
                                int totalErrors,
                                List<AgentResult.FileFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("代码验证结果：").append(totalFiles).append("个文件，")
                .append(totalErrors).append("个错误\n");

        if (findings.isEmpty()) {
            sb.append("✅ 所有文件均未发现错误\n");
            return sb.toString();
        }

        sb.append("\n错误列表：\n");
        for (AgentResult.FileFinding f : findings) {
            int line = f.getStartLine();

            sb.append("- [ERROR] ")
                    .append(f.getFilePath());
            if (line > 0) {
                sb.append(":").append(line);
            }
            if (f.getDescription() != null && !f.getDescription().isEmpty()) {
                sb.append(" — ").append(f.getDescription());
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
