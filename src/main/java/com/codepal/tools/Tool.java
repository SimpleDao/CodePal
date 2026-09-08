package com.codepal.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.codepal.model.ChatRequest;

import java.util.Set;

/**
 * 工具接口 —— 所有工具的统一抽象
 *
 * <p>借鉴 AutoDev 的 Tool 设计，将内置工具、MCP 工具、SubAgent 工具统一抽象。
 *
 * <p>工具风险等级：
 * <ul>
 *   <li>READ_ONLY - 只读工具，自动执行，无需确认</li>
 *   <li>WRITE - 有写操作的工具，需要确认（Craft 模式可绕过）</li>
 *   <li>DANGEROUS - 高风险工具，必须确认，不可绕过</li>
 * </ul>
 */
public interface Tool {

    enum RiskLevel {
        READ_ONLY,
        WRITE,
        DANGEROUS
    }

    String getName();

    String getDescription();

    Set<String> getKeywords();

    RiskLevel getRiskLevel();

    ChatRequest.ToolDefinition getDefinition();

    String execute(JsonObject params, Project project);

    /**
     * 执行工具（可跳过确认，用于 Craft 模式直接写文件/创建目录等）。
     * 默认回退到 {@link #execute(JsonObject, Project)}（skipConfirmation=false，即 Plan 行为）。
     */
    default String execute(JsonObject params, Project project, boolean skipConfirmation) {
        return execute(params, project);
    }

    /**
     * 执行工具并携带发起窗口的 ToolConfirmManager（用于多窗口隔离，
     * 确保确认/提问 UI 渲染到正确的 ChatWebView）。默认回退到不带 manager 的版本。
     */
    default String execute(JsonObject params, Project project, boolean skipConfirmation, ToolConfirmManager manager) {
        return execute(params, project, skipConfirmation);
    }

    default boolean isAvailable() {
        return true;
    }
}
