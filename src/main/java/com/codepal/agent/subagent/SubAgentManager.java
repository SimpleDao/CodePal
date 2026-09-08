package com.codepal.agent.subagent;

import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子智能体管理器 —— 统一管理所有 SubAgent
 *
 * <p>借鉴 AutoDev 的 SubAgentManager 设计，负责：
 * <ul>
 *   <li>SubAgent 实例的注册和生命周期管理</li>
 *   <li>根据上下文自动触发合适的 SubAgent</li>
 *   <li>协调多个 SubAgent 并行/串行执行</li>
 *   <li>提供统一的调用接口</li>
 * </ul>
 *
 * @author CP Multi-Agent
 */
public class SubAgentManager {

    private final Project project;
    private final Map<String, SubAgent<?, ?>> subAgents = new ConcurrentHashMap<>();
    private boolean initialized = false;

    public SubAgentManager(Project project) {
        this.project = project;
    }

    public synchronized void initialize() {
        if (initialized) return;

        registerAgent(new SearchSubAgent(project));
        registerAgent(new ErrorValidationAgent(project));
        registerAgent(new VisionSubAgent(project));

        initialized = true;
    }

    public <TInput, TOutput> void registerAgent(SubAgent<TInput, TOutput> agent) {
        if (agent == null || !agent.isAvailable()) return;
        subAgents.put(agent.getName(), agent);
    }

    @SuppressWarnings("unchecked")
    public <TInput, TOutput> SubAgent<TInput, TOutput> getAgent(String name) {
        return (SubAgent<TInput, TOutput>) subAgents.get(name);
    }

    public SearchSubAgent getSearchAgent() {
        return (SearchSubAgent) subAgents.get("search-agent");
    }

    public VisionSubAgent getVisionAgent() {
        return (VisionSubAgent) subAgents.get("vision-agent");
    }

    public ErrorValidationAgent getErrorValidationAgent() {
        return (ErrorValidationAgent) subAgents.get("error-validation-agent");
    }

    public List<String> getAvailableAgentNames() {
        List<String> names = new ArrayList<>(subAgents.keySet());
        Collections.sort(names);
        return names;
    }

    public List<SubAgent<?, ?>> getAvailableAgents() {
        List<SubAgent<?, ?>> agents = new ArrayList<>(subAgents.values());
        agents.sort(Comparator.comparingInt(SubAgent::getPriority));
        return agents;
    }

    /**
     * 根据上下文查找应该触发的 SubAgent 列表
     *
     * @param context 上下文信息
     * @return 应该触发的 agent 列表（按优先级排序）
     */
    public List<SubAgent<?, ?>> findTriggeredAgents(Map<String, Object> context) {
        List<SubAgent<?, ?>> triggered = new ArrayList<>();
        for (SubAgent<?, ?> agent : subAgents.values()) {
            try {
                if (agent.shouldTrigger(context)) {
                    triggered.add(agent);
                }
            } catch (Exception e) {
                    System.err.println("[SubAgentManager] 检查触发条件失败: " + agent.getName() + ", " + e.getMessage());
                }
        }
        triggered.sort(Comparator.comparingInt(SubAgent::getPriority));
        return triggered;
    }

    /**
     * 执行搜索任务的快捷方法
     */
    public AgentResult search(String query, SubAgent.ProgressCallback callback) {
        SearchSubAgent agent = getSearchAgent();
        if (agent == null) {
            return AgentResult.error("搜索智能体未初始化");
        }
        return agent.execute(query, callback);
    }

    /**
     * 执行视觉分析任务的快捷方法
     */
    public AgentResult viewImage(String imagePath, String mimeType, String question, SubAgent.ProgressCallback callback) {
        VisionSubAgent agent = getVisionAgent();
        if (agent == null) {
            return AgentResult.error("视觉子智能体未初始化");
        }
        return agent.execute(new VisionSubAgent.VisionTask(imagePath, mimeType, question), callback);
    }

    /**
     * 并行执行多个搜索任务
     */
    public List<AgentResult> parallelSearch(List<String> queries, SubAgent.ProgressCallback callback) {
        SearchSubAgent agent = getSearchAgent();
        if (agent == null) {
            return List.of(AgentResult.error("搜索智能体未初始化"));
        }
        return agent.parallelSearch(queries, callback);
    }

    /**
     * 验证文件的快捷方法
     */
    public AgentResult validateFiles(List<String> filePaths, SubAgent.ProgressCallback callback) {
        ErrorValidationAgent agent = getErrorValidationAgent();
        if (agent == null) {
            return AgentResult.error("错误验证智能体未初始化");
        }
        return agent.execute(filePaths, callback);
    }

    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("registeredAgents", subAgents.size());
        status.put("agentNames", new ArrayList<>(subAgents.keySet()));

        Map<String, Map<String, Object>> agentStates = new HashMap<>();
        for (Map.Entry<String, SubAgent<?, ?>> entry : subAgents.entrySet()) {
            agentStates.put(entry.getKey(), entry.getValue().getStateSummary());
        }
        status.put("agentStates", agentStates);

        return status;
    }

    public void shutdown() {
        subAgents.clear();
        initialized = false;
    }
}
