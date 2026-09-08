package com.codepal.cc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Claude Code ACP 配置。
 */
public class CCConfig {

    private static final String DEFAULT_ACP_SCRIPT =
            "D:/development-tool/nvm/nvm/v24.15.0/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js";

    private static final String DEFAULT_NODE_EXE = "node";

    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private String workingDirectory;
    private String acpScriptPath;
    private String nodeExe;
    private String claudeCodeExecutable;
    private String model;

    private static CCConfig instance;

    private CCConfig() {
        this.acpScriptPath = envOr("CLAUDE_ACP_SCRIPT", DEFAULT_ACP_SCRIPT);
        this.nodeExe = envOr("CLAUDE_ACP_NODE", DEFAULT_NODE_EXE);
        this.claudeCodeExecutable = System.getenv("CLAUDE_CODE_EXECUTABLE");
        this.workingDirectory = System.getProperty("user.dir");
        this.model = detectModel();
    }

    public static CCConfig getInstance() {
        if (instance == null) {
            instance = new CCConfig();
        }
        return instance;
    }

    public boolean isAcpAvailable() {
        Path p = Paths.get(acpScriptPath);
        return Files.exists(p);
    }

    public String[] buildAcpCommand() {
        return new String[]{ nodeExe, acpScriptPath };
    }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
        this.model = detectModel();
    }
    public String getAcpScriptPath() { return acpScriptPath; }
    public void setAcpScriptPath(String acpScriptPath) { this.acpScriptPath = acpScriptPath; }
    public String getNodeExe() { return nodeExe; }
    public void setNodeExe(String nodeExe) { this.nodeExe = nodeExe; }
    public String getClaudeCodeExecutable() { return claudeCodeExecutable; }
    public void setClaudeCodeExecutable(String claudeCodeExecutable) { this.claudeCodeExecutable = claudeCodeExecutable; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    private String detectModel() {
        // 1. 环境变量优先
        String envModel = System.getenv("ANTHROPIC_MODEL");
        if (envModel != null && !envModel.isEmpty()) return envModel;
        envModel = System.getenv("CLAUDE_MODEL");
        if (envModel != null && !envModel.isEmpty()) return envModel;

        // 2. 项目级 .claude/settings.json
        if (workingDirectory != null) {
            Path projectConfig = Paths.get(workingDirectory, ".claude", "settings.json");
            String m = readModelFromJson(projectConfig);
            if (m != null) return m;
        }

        // 3. 用户级 C:\Users\xxx\.claude\settings.json
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path userDirConfig = Paths.get(userHome, ".claude", "settings.json");
            String m = readModelFromJson(userDirConfig);
            if (m != null) return m;
        }

        // 4. 用户级 ~/.claude.json（兼容旧版）
        if (userHome != null) {
            Path userFileConfig = Paths.get(userHome, ".claude.json");
            String m = readModelFromJson(userFileConfig);
            if (m != null) return m;
        }

        // 5. 默认
        return DEFAULT_MODEL;
    }

    private static String readModelFromJson(Path configPath) {
        try {
            if (!Files.exists(configPath)) return null;
            try (Reader reader = Files.newBufferedReader(configPath)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("model") && !root.get("model").isJsonNull()) {
                    return root.get("model").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }
}
