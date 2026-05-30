package com.loongc.tools;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.loongc.model.ChatMessage;
import com.intellij.openapi.project.Project;
import com.loongc.utils.FileReaderUtil;

import java.util.List;

/**
 * 工具执行器 —— "四大金刚" 工具执行
 * 接收 LM 模型返回的工具调用，在本地执行相应操作并返回结果
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ToolExecutor {

    private static final Gson GSON = new Gson();

    /**
     * 执行单个工具调用，返回工具执行结果字符串
     */
    public static String execute(ChatMessage.ToolCall toolCall, Project project) {
        if (toolCall == null || toolCall.getFunction() == null) return "错误：无效的工具调用";
        String name = toolCall.getFunction().getName();
        String args = toolCall.getFunction().getArguments();

        try {
            JsonObject params = args != null && !args.isBlank()
                    ? JsonParser.parseString(args).getAsJsonObject()
                    : new JsonObject();

            switch (name) {
                case "locate_code_by_symbol":
                    return execLocateCodeBySymbol(params, project);
                case "view_file_outline":
                    return execViewFileOutline(params, project);
                case "read_file_range":
                    return execReadFileRange(params, project);
                case "search_grep":
                    return execSearchGrep(params, project);
                default:
                    return "错误：未知的工具 \"" + name + "\"";
            }
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 四大金刚工具具体实现
    // ─────────────────────────────────────────────────────────────────────────

    private static String execLocateCodeBySymbol(JsonObject params, Project project) {
        String symbol = getString(params, "symbol", "");
        if (symbol.isBlank()) return "错误：请提供类名或方法名";
        if (project == null) return "错误：未打开项目";

        List<String> paths = FileReaderUtil.locateSymbol(symbol, project);
        if (paths.isEmpty()) {
            return "未找到符号 \"" + symbol + "\" 的定义位置";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("找到符号 \"").append(symbol).append("\" 的定义：\n\n");
        for (int i = 0; i < paths.size(); i++) {
            sb.append(i + 1).append(". ").append(paths.get(i)).append("\n");
        }
        return sb.toString();
    }

    private static String execViewFileOutline(JsonObject params, Project project) {
        String filePath = getString(params, "file_path", "");
        if (filePath.isBlank()) return "错误：请提供文件路径";
        if (project == null) return "错误：未打开项目";
        return FileReaderUtil.viewFileOutline(filePath, project);
    }

    private static String execReadFileRange(JsonObject params, Project project) {
        String filePath = getString(params, "file_path", "");
        int startLine = getInt(params, "start_line", 0);
        int endLine   = getInt(params, "end_line", 0);
        if (filePath.isBlank()) return "错误：请提供文件路径";

        VirtualFile file = findFile(filePath, project);
        if (file == null) {
            return "错误：文件不存在——" + filePath;
        }
        String content = FileReaderUtil.readFileContentLines(file, startLine, endLine, 5000);
        if (content == null) {
            return "错误：无法读取文件——" + filePath;
        }
        return content;
    }

    private static String execSearchGrep(JsonObject params, Project project) {
        String keyword = getString(params, "keyword", "");
        String filePattern = getString(params, "file_pattern", "");
        int maxResults = getInt(params, "max_results", 10);
        if (keyword.isBlank()) return "错误：请提供搜索关键词";
        if (project == null) return "错误：未打开项目";
        return FileReaderUtil.searchGrep(keyword, filePattern, maxResults, project);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    private static VirtualFile findFile(String filePath, Project project) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (file != null && file.exists()) return file;
        if (project != null && project.getBaseDir() != null) {
            file = project.getBaseDir().findFileByRelativePath(filePath);
            if (file != null && file.exists()) return file;
        }
        for (Project p : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (p.getBaseDir() == null) continue;
            VirtualFile found = findByName(p.getBaseDir(), filePath);
            if (found != null) return found;
        }
        return null;
    }

    private static VirtualFile findByName(com.intellij.openapi.vfs.VirtualFile dir, String name) {
        if (dir.isDirectory()) {
            for (com.intellij.openapi.vfs.VirtualFile child : dir.getChildren()) {
                if (child.getName().equals(name)) return child;
                if (child.isDirectory()) {
                    VirtualFile found = findByName(child, name);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static String getString(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    private static int getInt(JsonObject obj, String key, int defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    private static boolean getBool(JsonObject obj, String key, boolean defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsBoolean(); } catch (Exception ignored) {}
        }
        return defaultVal;
    }
}
