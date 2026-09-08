package com.codepal.tools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.codepal.api.ModelLinkDispatcher;
import com.codepal.linter.LinterRegistry;
import com.codepal.linter.LintResult;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.model.ModelConfig;
import com.codepal.settings.CPSettings;
import com.codepal.utils.FileReaderUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 代码审查器
 *
 * 两级审查机制：
 *  1. Linter 优先 —— 用项目本地的 linter 工具快速检查（省 token、速度快）
 *  2. LLM 兜底 —— 没 linter 时用 LLM 深度审查，或用户要求深度分析
 */
public class CodeReviewer {

    /**
     * 审查文件（自动选择方式）
     *
     * @param filePath 文件路径
     * @param mode     审查模式："auto"（自动，linter 优先）、"linter"（仅 linter）、"llm"（仅 LLM 深度审查）
     * @param focus    审查重点（仅 LLM 模式有效）
     * @param project  项目
     * @return 审查结果
     */
    public static String reviewFile(String filePath, String mode, String focus, Project project) {
        if (filePath == null || filePath.isBlank()) return "错误：请提供文件路径";
        if (project == null) return "错误：未打开项目";

        VirtualFile vf = FileReaderUtil.findVirtualFile(filePath, project);
        if (vf == null) return "错误：找不到文件 " + filePath;

        String projectPath = project.getBasePath() != null ? project.getBasePath() : ".";

        boolean useLinter = true;
        boolean useLlm = false;

        if ("linter".equalsIgnoreCase(mode)) {
            useLinter = true;
            useLlm = false;
        } else if ("llm".equalsIgnoreCase(mode)) {
            useLinter = false;
            useLlm = true;
        } else {
            useLinter = true;
            useLlm = true;
        }

        StringBuilder result = new StringBuilder();

        if (useLinter) {
            List<LintResult> lintResults = LinterRegistry.lintFile(filePath, projectPath);
            String lintText = LinterRegistry.formatResults(lintResults);

            boolean hasLinter = lintResults != null && !lintResults.isEmpty();

            result.append("🔍 Linter 检查\n");
            result.append("─────────────────────────────────────────────\n");
            result.append(lintText).append("\n\n");

            if ("auto".equalsIgnoreCase(mode) && hasLinter) {
                useLlm = false;
            }
        }

        if (useLlm) {
            String content = FileReaderUtil.readFileContent(vf);
            if (content != null) {
                int totalLines = content.split("\n", -1).length;
                String llmResult = doLlmReview(vf.getName(), content, 1, totalLines, focus);
                if (useLinter) {
                    result.append("\n");
                }
                result.append("🤖 LLM 深度审查\n");
                result.append("─────────────────────────────────────────────\n");
                result.append(llmResult);
            }
        }

        return result.toString().trim();
    }

    /**
     * 审查代码片段
     */
    public static String reviewRange(String filePath, int startLine, int endLine,
                                     String mode, String focus, Project project) {
        if (filePath == null || filePath.isBlank()) return "错误：请提供文件路径";
        if (project == null) return "错误：未打开项目";

        VirtualFile vf = FileReaderUtil.findVirtualFile(filePath, project);
        if (vf == null) return "错误：找不到文件 " + filePath;

        String content = FileReaderUtil.readFileContent(vf);
        if (content == null) return "错误：无法读取文件 " + filePath;

        String[] lines = content.split("\n", -1);
        int start = Math.max(1, startLine);
        int end = (endLine <= 0 || endLine > lines.length) ? lines.length : endLine;
        if (start > end) return "错误：起始行不能大于结束行";

        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }
        String code = sb.toString();

        StringBuilder result = new StringBuilder();
        result.append("📄 文件：").append(filePath).append("\n");
        result.append("📝 范围：第 ").append(start).append(" - ").append(end).append(" 行\n\n");

        String llmResult = doLlmReview(vf.getName(), code, start, end, focus);
        result.append("🤖 LLM 深度审查\n");
        result.append("─────────────────────────────────────────────\n");
        result.append(llmResult);

        return result.toString();
    }

    private static String doLlmReview(String fileName, String code, int startLine, int endLine, String focus) {
        String language = detectLanguage(fileName);
        String focusDesc = formatFocus(focus);

        String systemPrompt = "你是一位资深代码审查专家，擅长发现代码中的安全漏洞、性能问题、潜在Bug、风格问题和不良实践。\n"
                + "请对用户提供的代码进行严格审查，找出所有问题。\n"
                + "审查范围：" + focusDesc + "\n\n"
                + "输出要求：\n"
                + "1. 先输出一段简要总结（2-3句话），概括整体代码质量和主要问题\n"
                + "2. 然后列出每个具体问题，格式如下：\n"
                + "   【等级】 类别 | 行号\n"
                + "   问题：问题描述\n"
                + "   建议：修复建议\n\n"
                + "问题等级从高到低：CRITICAL、HIGH、MEDIUM、LOW、INFO\n"
                + "问题类别：security（安全）、performance（性能）、bug（潜在Bug）、style（代码风格）、best-practice（最佳实践）\n"
                + "行号：如果是代码片段审查，行号基于代码片段的第一行（第1行对应原始文件第" + startLine + "行）\n"
                + "请只输出审查结果，不要输出多余的解释和道歉。";

        String userPrompt = "请审查以下" + language + "代码（文件：" + fileName
                + "，第 " + startLine + "-" + endLine + " 行）：\n\n"
                + "```" + language + "\n"
                + code + "\n"
                + "```";

        CPSettings settings = CPSettings.getInstance();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", userPrompt));

        ChatRequest request = new ChatRequest();
        request.setModel(settings.getChatModelName());
        request.setMessages(messages);
        request.setStream(true);
        request.setMax_tokens(Math.min(settings.getChatMaxOutput(), 2048));
        request.setTemperature(0.3);
        request.setThinking(false);

        StringBuilder resultBuilder = new StringBuilder();
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try {
            ModelConfig model = CPSettings.getInstance().getCurrentChatModel();
            ModelLinkDispatcher.streamChat(request, messages, null, model, new ModelLinkDispatcher.Relay() {
                @Override
                public void onMessage(String content) {
                    resultBuilder.append(content);
                }
                @Override
                public void onReasoning(String reasoning) {
                }
                @Override
                public void onComplete() {
                    future.complete(true);
                }
                @Override
                public void onToolCalls(List<ChatMessage.ToolCall> toolCalls) {
                    future.complete(true);
                }
                @Override
                public void onError(Throwable error) {
                    resultBuilder.append("审查失败：").append(error.getMessage());
                    future.complete(false);
                }
            });
            future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "代码审查失败：" + e.getMessage();
        }

        return resultBuilder.toString().trim();
    }

    private static String detectLanguage(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".jsx")) return "jsx";
        if (lower.endsWith(".tsx")) return "tsx";
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".c")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".swift")) return "swift";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".html")) return "html";
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) return "css";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "bash";
        if (lower.endsWith(".ps1")) return "powershell";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".md")) return "markdown";
        return "code";
    }

    private static String formatFocus(String focus) {
        if (focus == null || focus.isBlank() || "all".equalsIgnoreCase(focus)) {
            return "全面审查（安全、性能、Bug、代码风格、最佳实践）";
        }
        switch (focus.toLowerCase()) {
            case "security": return "安全漏洞（注入、越权、敏感信息泄露、XSS、CSRF等）";
            case "performance": return "性能问题（时间复杂度、内存泄漏、低效算法、N+1查询等）";
            case "bug": return "潜在Bug（空指针、边界条件、并发问题、异常处理等）";
            case "style": return "代码风格（命名规范、格式、可读性、代码规范等）";
            case "best-practice": return "最佳实践（设计模式、架构设计、可维护性、可测试性等）";
            default: return focus;
        }
    }
}
