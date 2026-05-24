package com.loongc.completion;

import com.loongc.settings.CodeBuddySettings;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

/**
 * CodeBuddy 代码补全贡献者
 * 基于 DeepSeek AI 的智能代码补全
 */
public class CodeBuddyCompletionContributor extends CompletionContributor {
    private static final Logger LOG = Logger.getInstance(CodeBuddyCompletionContributor.class);
    private final CodeBuddyCompletionProvider provider;

    public CodeBuddyCompletionContributor() {
        this.provider = new CodeBuddyCompletionProvider();
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet resultSet) {
                if (!CodeBuddySettings.getInstance().isEnableAutoComplete()) {
                    return;
                }
                provider.provideCompletion(parameters, resultSet);
            }
        });
    }

    @Override
    public boolean invokeAutoPopup(@NotNull CharSequence prefix, char typeChar) {
        return CodeBuddySettings.getInstance().isEnableAutoComplete()
            && (typeChar == '.' || typeChar == '(' || typeChar == ' ' || typeChar == '\n');
    }
}
