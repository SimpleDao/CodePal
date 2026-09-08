package com.codepal.agent.subagent;

import com.intellij.openapi.project.Project;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 子智能体基类 —— 借鉴 AutoDev 的 SubAgent 设计思想
 *
 * <p>SubAgent 是专注于单一职责的智能体，可被主智能体当作工具调用。
 * 每个 SubAgent 拥有独立的 LLM 上下文，避免污染主对话。
 *
 * <p>特点：
 * <ul>
 *   <li>聚焦于单一职责（搜索、验证、总结等）</li>
 *   <li>拥有独立的 LLM 上下文</li>
 *   <li>输入输出结构化</li>
 *   <li>可被主智能体当作 Tool 调用</li>
 *   <li>支持异步执行和进度回调</li>
 * </ul>
 *
 * @param <TInput>  输入类型
 * @param <TOutput> 输出类型
 * @author CP Multi-Agent
 */
public abstract class SubAgent<TInput, TOutput> {

    protected final Project project;
    protected final String name;
    protected final String description;
    protected int priority = 100;

    protected SubAgent(String name, String description, Project project) {
        this.name = name;
        this.description = description;
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isAvailable() {
        return true;
    }

    public abstract boolean shouldTrigger(Map<String, Object> context);

    public abstract TOutput execute(TInput input, ProgressCallback callback);

    public CompletableFuture<TOutput> executeAsync(TInput input, ProgressCallback callback) {
        CompletableFuture<TOutput> future = new CompletableFuture<>();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                TOutput result = execute(input, callback);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public Map<String, Object> getStateSummary() {
        return Map.of(
                "name", name,
                "description", description,
                "priority", priority
        );
    }

    public interface ProgressCallback {
        void onProgress(String message);

        void onStep(String stepName, String detail);
    }
}
