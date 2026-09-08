package com.codepal.agent;

import com.intellij.openapi.project.Project;
import com.codepal.model.ModelConfig;
import com.codepal.settings.CPSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 后端管理器 —— 工厂模式 + 单例管理
 *
 * <p>借鉴 AutoDev 的 SubAgentManager 思想，统一管理不同的 Agent 后端实现。
 *
 * <p>职责：
 * <ul>
 *   <li>创建和缓存 AgentBackend 实例</li>
 *   <li>根据当前配置选择合适的后端</li>
 *   <li>提供后端切换能力</li>
 * </ul>
 *
 * @author CP Refactor
 */
public class AgentBackendManager {

    private final Project project;
    private final Map<String, AgentBackend> backends = new HashMap<>();

    private AgentBackend currentBackend;

    public AgentBackendManager(Project project) {
        this.project = project;
    }

    public AgentBackend getCurrentBackend() {
        if (currentBackend == null) {
            currentBackend = createBackendForCurrentAgent();
        }
        return currentBackend;
    }

    public AgentBackend getBackend(String name) {
        if (backends.containsKey(name)) {
            return backends.get(name);
        }
        AgentBackend backend = createBackend(name);
        if (backend != null) {
            backends.put(name, backend);
        }
        return backend;
    }

    public void switchBackend(String name) {
        AgentBackend backend = getBackend(name);
        if (backend != null) {
            if (currentBackend != null) {
                currentBackend.cancelCurrent();
            }
            currentBackend = backend;
        }
    }

    public void refreshCurrentBackend() {
        currentBackend = createBackendForCurrentAgent();
    }

    private AgentBackend createBackendForCurrentAgent() {
        // 按当前聊天模型的 apiFormat 选择后端：anthropic 走原生 Anthropic 链路，
        // 其余（openai / deepseek 等 OpenAI 兼容）走 OpenAI 兼容链路。
        String fmt = getCurrentChatModelFormat();
        if (ModelConfig.FORMAT_ANTHROPIC.equals(fmt)) {
            return getOrCreateBackend("anthropic");
        }
        return getOrCreateBackend("openai");
    }

    private String getCurrentChatModelFormat() {
        ModelConfig m = CPSettings.getInstance().getCurrentChatModel();
        return m != null ? m.getApiFormat() : ModelConfig.FORMAT_OPENAI;
    }

    private AgentBackend getOrCreateBackend(String type) {
        if (backends.containsKey(type)) {
            return backends.get(type);
        }
        AgentBackend backend = createBackend(type);
        if (backend != null) {
            backends.put(type, backend);
        }
        return backend;
    }

    private AgentBackend createBackend(String type) {
        switch (type.toLowerCase()) {
            case "openai":
            case "deepseek":
                return new DeepSeekBackend(project);
            case "anthropic":
                return new AnthropicBackend(project);
            default:
                return new DeepSeekBackend(project);
        }
    }

    public static boolean isAcpAgent(String agentName) {
        return false;
    }

    public void shutdown() {
        for (AgentBackend backend : backends.values()) {
            backend.cancelCurrent();
        }
        backends.clear();
        currentBackend = null;
    }
}
