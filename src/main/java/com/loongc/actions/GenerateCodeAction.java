package com.loongc.actions;

import com.loongc.api.DeepSeekClient;
import com.loongc.model.ChatMessage;
import com.loongc.settings.LoongCSettings;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 AI 生成代码的 Action
 *
 * @author 水龙吟
 * @date 2026-05-24
 */
public class GenerateCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || editor == null) {
            return;
        }

        if (!LoongCSettings.getInstance().isConfigured()) {
            Messages.showWarningDialog(project,
                    "请先配置 DeepSeek API Key\n(Settings -> LoongC)",
                    "LoongC 未配置");
            return;
        }

        String prompt = Messages.showInputDialog(project,
                "描述你想要生成的代码：",
                "LoongC 生成代码",
                Messages.getQuestionIcon());

        if (prompt == null || prompt.trim().isEmpty()) {
            return;
        }

        // 获取选中内容作为上下文
        SelectionModel selectionModel = editor.getSelectionModel();
        String selectedText = selectionModel.getSelectedText();

        String language = file != null ? detectLanguage(file.getName()) : "Java";

        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("请用 ").append(language).append(" 编写以下功能的代码：\n\n");
        fullPrompt.append(prompt).append("\n\n");

        if (selectedText != null && !selectedText.isEmpty()) {
            fullPrompt.append("参考上下文代码：\n```\n").append(selectedText).append("\n```\n\n");
        }

        fullPrompt.append("请只输出代码，不要添加解释说明。");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system",
                "你是一个专业的程序员，擅长编写高质量、可读性强的代码。"));
        messages.add(new ChatMessage("user", fullPrompt.toString()));

        // 使用 IDEA Task.Backgroundable 执行耗时网络请求，避免用 new Thread 导致内存泄漏
        final String finalSelectedText = selectedText;
        final Editor   finalEditor     = editor;
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "LoongC 生成代码") {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);

                        DeepSeekClient client = new DeepSeekClient();
                        String response = client.chat(messages);

                        // 清理响应
                        response = cleanCodeResponse(response);

                        final String code = response;
                        if (code != null && !code.isEmpty()) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    if (finalSelectedText != null && !finalSelectedText.isEmpty()) {
                                        // 替换选中内容
                                        finalEditor.getDocument().replaceString(
                                                selectionModel.getSelectionStart(),
                                                selectionModel.getSelectionEnd(),
                                                code
                                        );
                                    } else {
                                        // 在光标位置插入
                                        finalEditor.getDocument().insertString(
                                                finalEditor.getCaretModel().getOffset(),
                                                code
                                        );
                                    }
                                });
                            });
                        }
                    }
                }
        );
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null
        );
    }

    private String detectLanguage(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".java")) return "Java";
        if (lower.endsWith(".kt")) return "Kotlin";
        if (lower.endsWith(".py")) return "Python";
        if (lower.endsWith(".js")) return "JavaScript";
        if (lower.endsWith(".ts")) return "TypeScript";
        if (lower.endsWith(".go")) return "Go";
        if (lower.endsWith(".rs")) return "Rust";
        return "Java";
    }

    private String cleanCodeResponse(String response) {
        if (response == null) return "";
        response = response.replaceAll("^```\\w*\\n?", "");
        response = response.replaceAll("\\n?```\\s*$", "");
        return response.trim();
    }
}
