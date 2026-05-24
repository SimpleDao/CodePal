package com.loongc.completion;

import com.loongc.api.DeepSeekClient;
import com.loongc.api.model.ChatMessage;
import com.loongc.settings.CodeBuddySettings;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CodeBuddy AI 代码补全提供者
 */
public class CodeBuddyCompletionProvider {
    private static final Logger LOG = Logger.getInstance(CodeBuddyCompletionProvider.class);
    private final DeepSeekClient client;

    public CodeBuddyCompletionProvider() {
        this.client = new DeepSeekClient();
    }

    public void provideCompletion(@NotNull CompletionParameters parameters,
                                   @NotNull CompletionResultSet resultSet) {
        if (!CodeBuddySettings.getInstance().isConfigured()) {
            return;
        }

        Editor editor = parameters.getEditor();
        Document document = editor.getDocument();
        int offset = parameters.getOffset();

        // 获取光标前后的代码上下文
        String beforeCursor = getTextBefore(document, offset, 1500);
        String afterCursor = getTextAfter(document, offset, 500);

        String language = detectLanguage(parameters.getOriginalFile());

        // 构建提示词
        String prompt = buildCompletionPrompt(beforeCursor, afterCursor, language);

        // 异步获取补全建议
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system",
            "你是一个专业的代码补全助手。根据提供的代码上下文，只输出接下来应该输入的代码片段，不要包含解释。"));
        messages.add(new ChatMessage("user", prompt));

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> client.chat(messages));
            String completion = future.get(8, TimeUnit.SECONDS);

            if (completion != null && !completion.trim().isEmpty()) {
                // 清理响应内容
                completion = cleanCompletion(completion);

                if (!completion.isEmpty()) {
                    LookupElementBuilder element = LookupElementBuilder.create(completion)
                        .withPresentableText(completion.length() > 50
                            ? completion.substring(0, 50) + "..." : completion)
                        .withTypeText("CodeBuddy AI")
                        .withIcon(null)
                        .withInsertHandler((context, item) -> {
                            // 自定义插入逻辑
                        });
                    resultSet.addElement(element);
                }
            }
        } catch (Exception e) {
            LOG.debug("CodeBuddy completion failed: " + e.getMessage());
        }
    }

    private String buildCompletionPrompt(String before, String after, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append("编程语言: ").append(language).append("\n\n");
        sb.append("请根据以下代码上下文，补全光标位置的代码（<CURSOR>标记处）：\n\n");
        sb.append("```").append(language.toLowerCase()).append("\n");
        sb.append(before);
        sb.append("<CURSOR>");
        sb.append(after);
        sb.append("\n```\n\n");
        sb.append("只输出光标处应该补充的代码片段，不要输出完整文件，不要添加解释。");
        return sb.toString();
    }

    private String getTextBefore(Document document, int offset, int maxChars) {
        int start = Math.max(0, offset - maxChars);
        return document.getText().substring(start, offset);
    }

    private String getTextAfter(Document document, int offset, int maxChars) {
        int end = Math.min(document.getTextLength(), offset + maxChars);
        return document.getText().substring(offset, end);
    }

    private String detectLanguage(PsiFile file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".kt")) return "Kotlin";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".js")) return "JavaScript";
        if (name.endsWith(".ts")) return "TypeScript";
        if (name.endsWith(".tsx")) return "TSX";
        if (name.endsWith(".jsx")) return "JSX";
        if (name.endsWith(".go")) return "Go";
        if (name.endsWith(".rs")) return "Rust";
        if (name.endsWith(".cpp") || name.endsWith(".cc")) return "C++";
        if (name.endsWith(".c")) return "C";
        if (name.endsWith(".cs")) return "C#";
        if (name.endsWith(".php")) return "PHP";
        if (name.endsWith(".rb")) return "Ruby";
        if (name.endsWith(".swift")) return "Swift";
        if (name.endsWith(".scala")) return "Scala";
        if (name.endsWith(".groovy")) return "Groovy";
        if (name.endsWith(".html")) return "HTML";
        if (name.endsWith(".css")) return "CSS";
        if (name.endsWith(".sql")) return "SQL";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "YAML";
        return "Unknown";
    }

    private String cleanCompletion(String completion) {
        if (completion == null) return "";

        // 移除 markdown 代码块标记
        completion = completion.replaceAll("^```\\w*\\n?", "");
        completion = completion.replaceAll("\\n?```\\s*$", "");

        // 移除 <CURSOR> 标记
        completion = completion.replace("<CURSOR>", "");

        // 移除首尾空白
        completion = completion.trim();

        // 如果包含换行，只取第一行作为补全建议
        if (completion.contains("\n")) {
            String[] lines = completion.split("\n", 2);
            completion = lines[0];
        }

        return completion;
    }
}
