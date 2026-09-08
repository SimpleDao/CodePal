package com.codepal.tools;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.codepal.model.UserAnswer;
import com.codepal.model.UserQuestion;
import com.codepal.ui.ChatWebView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ToolConfirmManager implements ToolExecutor.ToolConfirmProvider, ToolExecutor.AskUserQuestionProvider {

    private static final Gson GSON = new Gson();

    private final Project project;
    private ChatWebView chatWebView;

    private final Map<String, CompletableFuture<Boolean>> pendingConfirms = new ConcurrentHashMap<>();
    private final Map<String, String> pendingConfirmCommands = new ConcurrentHashMap<>();
    private final Set<String> trustedCommands = ConcurrentHashMap.newKeySet();

    private final Map<String, CompletableFuture<List<UserAnswer>>> pendingQuestions = new ConcurrentHashMap<>();

    public ToolConfirmManager(Project project, ChatWebView chatWebView) {
        this.project = project;
        this.chatWebView = chatWebView;
    }

    /** 切换到新的 WebView（多标签隔离时调用） */
    public void setWebView(ChatWebView webView) {
        this.chatWebView = webView;
        registerCallbacks();
    }

    public static String getCommandTrustKey(String command) {
        String cmd = command.trim().toLowerCase();
        int spaceIdx = cmd.indexOf(' ');
        return spaceIdx > 0 ? cmd.substring(0, spaceIdx) : cmd;
    }

    public void registerCallbacks() {
        chatWebView.setToolConfirmCallback((toolCallId, approved, trusted) -> {
            String command = pendingConfirmCommands.remove(toolCallId);
            CompletableFuture<Boolean> future = pendingConfirms.remove(toolCallId);
            if (future != null) {
                future.complete(approved);
            }
            if (approved && trusted && command != null) {
                String prefix = getCommandTrustKey(command);
                trustedCommands.add(prefix);
            }
        });

        chatWebView.setAskQuestionCallback(this::onQuestionAnswered);

        ToolExecutor.setConfirmProvider(this);
        ToolExecutor.setAskQuestionProvider(this);
    }

    private void onQuestionAnswered(String questionId, String answersJson) {
        CompletableFuture<List<UserAnswer>> future = pendingQuestions.remove(questionId);
        if (future == null) return;
        try {
            List<UserAnswer> answers = GSON.fromJson(answersJson,
                    new com.google.gson.reflect.TypeToken<List<UserAnswer>>() {}.getType());
            future.complete(answers != null ? answers : new ArrayList<>());
        } catch (Exception e) {
            future.complete(new ArrayList<>());
        }
    }

    @Override
    public CompletableFuture<Boolean> requestConfirm(
            String toolCallId, String command, String level, boolean canTrust, String kind) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConfirms.put(toolCallId, future);
        pendingConfirmCommands.put(toolCallId, command);

        ApplicationManager.getApplication().invokeLater(() -> {
            chatWebView.showToolConfirm(toolCallId, command, level, canTrust, kind);
        });

        return future;
    }

    @Override
    public CompletableFuture<List<UserAnswer>> requestAskUserQuestion(List<UserQuestion> questions) {
        CompletableFuture<List<UserAnswer>> future = new CompletableFuture<>();
        String questionId = UUID.randomUUID().toString();
        pendingQuestions.put(questionId, future);

        String questionsJson = GSON.toJson(questions);

        ApplicationManager.getApplication().invokeLater(() -> {
            chatWebView.showAskUserQuestion(questionId, questionsJson);
        });

        return future;
    }

    @Override
    public boolean isTrusted(String command) {
        String prefix = getCommandTrustKey(command);
        return trustedCommands.contains(prefix);
    }

    @Override
    public void addTrusted(String command) {
        String prefix = getCommandTrustKey(command);
        trustedCommands.add(prefix);
    }

    public void clearAllPending() {
        for (CompletableFuture<Boolean> future : pendingConfirms.values()) {
            future.complete(false);
        }
        pendingConfirms.clear();
        pendingConfirmCommands.clear();
        for (CompletableFuture<List<UserAnswer>> f : pendingQuestions.values()) {
            f.complete(new ArrayList<>());
        }
        pendingQuestions.clear();
    }

    public void clearTrusted() {
        trustedCommands.clear();
    }

    public void reset() {
        clearAllPending();
        clearTrusted();
    }

    public boolean hasPending() {
        return !pendingConfirms.isEmpty() || !pendingQuestions.isEmpty();
    }
}
