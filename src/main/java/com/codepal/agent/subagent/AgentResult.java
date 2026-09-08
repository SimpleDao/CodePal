package com.codepal.agent.subagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子智能体执行结果 —— 统一的结果封装
 *
 * <p>借鉴 AutoDev 的 ToolResult.AgentResult 设计，
 * 所有 SubAgent 的输出都统一为这个结构，便于主智能体处理。
 *
 * @author CP Multi-Agent
 */
public class AgentResult {

    private final boolean success;
    private final String content;
    private final Map<String, Object> metadata;
    private final List<FileFinding> findings;
    private final String errorMessage;

    private AgentResult(Builder builder) {
        this.success = builder.success;
        this.content = builder.content != null ? builder.content : "";
        this.metadata = builder.metadata != null ? builder.metadata : new HashMap<>();
        this.findings = builder.findings != null ? builder.findings : new ArrayList<>();
        this.errorMessage = builder.errorMessage;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public List<FileFinding> getFindings() {
        return findings;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public static AgentResult success(String content) {
        return new Builder().success(true).content(content).build();
    }

    public static AgentResult success(String content, List<FileFinding> findings) {
        return new Builder().success(true).content(content).findings(findings).build();
    }

    public static AgentResult error(String errorMessage) {
        return new Builder().success(false).errorMessage(errorMessage).build();
    }

    public static AgentResult error(String errorMessage, Map<String, Object> metadata) {
        return new Builder().success(false).errorMessage(errorMessage).metadata(metadata).build();
    }

    public static class Builder {
        private boolean success;
        private String content;
        private Map<String, Object> metadata;
        private List<FileFinding> findings;
        private String errorMessage;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public Builder findings(List<FileFinding> findings) {
            this.findings = findings;
            return this;
        }

        public Builder addFinding(FileFinding finding) {
            if (this.findings == null) {
                this.findings = new ArrayList<>();
            }
            this.findings.add(finding);
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public AgentResult build() {
            return new AgentResult(this);
        }
    }

    /**
     * 文件发现项 —— 用于搜索结果、错误定位等
     */
    public static class FileFinding {
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final String description;
        private final String severity;

        public FileFinding(String filePath, int startLine, int endLine, String description, String severity) {
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.description = description;
            this.severity = severity;
        }

        public String getFilePath() {
            return filePath;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public String getDescription() {
            return description;
        }

        public String getSeverity() {
            return severity;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(filePath);
            if (startLine > 0) {
                sb.append(" L").append(startLine);
                if (endLine > startLine) {
                    sb.append("-L").append(endLine);
                }
            }
            if (description != null && !description.isEmpty()) {
                sb.append(" — ").append(description);
            }
            return sb.toString();
        }
    }

    @Override
    public String toString() {
        if (!success) {
            return "[" + name + "] 失败：" + (errorMessage != null ? errorMessage : "未知错误");
        }
        return content != null ? content : "";
    }

    private String name = "";

    public void setName(String name) {
        this.name = name;
    }
}
