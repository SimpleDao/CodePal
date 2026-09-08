package com.codepal.agent.subagent;

import com.intellij.openapi.project.Project;
import com.codepal.tools.SearchAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 搜索子智能体 —— 封装现有 SearchAgent，适配 SubAgent 体系
 *
 * <p>职责：
 * <ul>
 *   <li>处理宽泛的代码搜索查询（如"登录在哪里"、"订单怎么实现的"）</li>
 *   <li>独立运行 ReAct 循环，不污染主模型上下文</li>
 *   <li>返回精简的路径+行号结果，供主模型使用</li>
 *   <li>支持并行搜索（多个搜索任务同时执行）</li>
 * </ul>
 *
 * <p>设计思想：
 * 当用户查询比较宽泛、需要大量搜索浏览时，将搜索任务交给子智能体，
 * 避免主智能体上下文被大量搜索结果污染。子智能体独立完成搜索后，
 * 只返回精简的关键信息给主模型。
 *
 * @author CP Multi-Agent
 */
public class SearchSubAgent extends SubAgent<String, AgentResult> {

    private static final String AGENT_NAME = "search-agent";
    private static final String AGENT_DESC = "代码搜索定位器，用于在项目中查找与查询相关的代码位置";

    public SearchSubAgent(Project project) {
        super(AGENT_NAME, AGENT_DESC, project);
        this.priority = 80;
    }

    @Override
    public boolean shouldTrigger(Map<String, Object> context) {
        if (context == null) return false;
        Object query = context.get("query");
        if (query == null) return false;
        String q = query.toString().toLowerCase();
        return q.contains("搜索") || q.contains("查找") || q.contains("在哪里")
                || q.contains("怎么实现") || q.contains("实现的") || q.contains("相关代码")
                || q.contains("定位") || q.contains("找一下") || q.contains("看看");
    }

    @Override
    public AgentResult execute(String query, ProgressCallback callback) {
        if (query == null || query.isBlank()) {
            return AgentResult.error("搜索任务为空");
        }

        try {
            SearchAgent.SearchResult result = SearchAgent.search(query, project,
                    new SearchAgent.SearchProgressCallback() {
                        @Override
                        public void onReasoning(String delta) {
                            if (callback != null) {
                                callback.onStep("thinking", delta);
                            }
                        }

                        @Override
                        public void onToolCall(String toolName, String toolArgs) {
                            if (callback != null) {
                                callback.onStep("tool_call", toolName + "(" + toolArgs + ")");
                            }
                        }

                        @Override
                        public void onToolResult(String toolName, String result) {
                            if (callback != null) {
                                String summary = result.length() > 200
                                        ? result.substring(0, 200) + "..."
                                        : result;
                                callback.onStep("tool_result", toolName + ": " + summary);
                            }
                        }

                        @Override
                        public void onComplete(SearchAgent.SearchResult result) {
                            if (callback != null) {
                                callback.onProgress("搜索完成");
                            }
                        }
                    });

            if (!result.success) {
                return AgentResult.error(result.error);
            }

            List<AgentResult.FileFinding> findings = new ArrayList<>();
            if (result.foundFiles != null) {
                int limit = Math.min(result.foundFiles.size(), 15);
                for (int i = 0; i < limit; i++) {
                    SearchAgent.FileInfo fi = result.foundFiles.get(i);
                    findings.add(new AgentResult.FileFinding(
                            fi.path, 0, 0, fi.description, "info"
                    ));
                }
            }

            String content = buildSearchResultSummary(query, result.summary, findings);

            AgentResult.Builder builder = new AgentResult.Builder()
                    .success(true)
                    .content(content)
                    .findings(findings)
                    .addMetadata("query", query)
                    .addMetadata("foundCount", result.foundFiles != null ? result.foundFiles.size() : 0)
                    .addMetadata("returnedCount", findings.size());

            return builder.build();

        } catch (Exception e) {
            return AgentResult.error("搜索过程出错：" + e.getMessage());
        }
    }

    private String buildSearchResultSummary(String query, String aiSummary, List<AgentResult.FileFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索结果（查询：").append(query).append("）\n");

        if (aiSummary != null && !aiSummary.isBlank()) {
            sb.append("\n摘要：").append(aiSummary).append("\n");
        }

        if (findings.isEmpty()) {
            sb.append("\n未找到相关文件\n");
            return sb.toString();
        }

        sb.append("\n相关文件（前").append(findings.size()).append("个）：\n");
        for (int i = 0; i < findings.size(); i++) {
            AgentResult.FileFinding f = findings.get(i);
            sb.append(i + 1).append(". ").append(f.getFilePath());
            if (f.getDescription() != null && !f.getDescription().isEmpty()) {
                sb.append(" — ").append(f.getDescription());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 并行执行多个搜索任务
     *
     * @param queries  搜索查询列表
     * @param callback 进度回调（可为 null）
     * @return 每个查询对应的结果列表（顺序与输入一致）
     */
    public List<AgentResult> parallelSearch(List<String> queries, ProgressCallback callback) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }

        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            final int idx = i;
            final String query = queries.get(i);
            CompletableFuture<AgentResult> future = executeAsync(query, new ProgressCallback() {
                @Override
                public void onProgress(String message) {
                    if (callback != null) {
                        callback.onProgress("[搜索" + (idx + 1) + "] " + message);
                    }
                }

                @Override
                public void onStep(String stepName, String detail) {
                    if (callback != null) {
                        callback.onStep("[搜索" + (idx + 1) + "] " + stepName, detail);
                    }
                }
            });
            futures.add(future);
        }

        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                results.add(AgentResult.error("并行搜索失败：" + e.getMessage()));
            }
        }
        return results;
    }
}
