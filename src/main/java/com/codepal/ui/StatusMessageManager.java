package com.codepal.ui;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 状态提示词管理器 — 统一管理各场景下 AI 加载 / 工具执行等轮播提示语。
 *
 * <p>所有提示词列表按场景分类，支持占位符替换。
 */
public final class StatusMessageManager {

    private StatusMessageManager() {}

    // ==================== 场景分类 ====================

    /** AI 启动加载：模型推理等待时的轮播词 */
    public static final List<String> AI_LOADING = List.of(
        "正在深度推理中...",
        "思考时间越长，效果越好",
        "模型正在分析您的请求...",
        "即将为您生成最佳回复",
        "正在加载上下文信息..."
    );

    /** 工具执行中：通用轮播词 */
    public static final List<String> TOOL_EXECUTING = List.of(
        "模型正在努力构思...",
        "正在处理任务，请稍候...",
        "工具正在运行中...",
        "处理完成后将立即返回结果",
        "正在优化输出内容..."
    );

    /** 文件写入中：write_file / create_new_file 专用（{file} 占位符） */
    private static final List<String> FILE_WRITING_TPL = List.of(
        "正在写入 {file}...",
        "文件 {file} 正在生成内容...",
        "模型正在为 {file} 构思最佳方案...",
        "正在保存 {file} 到磁盘...",
        "请稍候，{file} 即将完成"
    );

    /** 文件编辑中：edit_file 专用（{file} 占位符） */
    private static final List<String> FILE_EDITING_TPL = List.of(
        "正在编辑 {file}...",
        "模型正在为 {file} 定位修改点...",
        "正在对 {file} 搜索替换...",
        "模型正在应用对 {file} 的修改...",
        "请稍候，{file} 即将更新完成"
    );

    /** 代码审查中：code_review 专用 */
    public static final List<String> CODE_REVIEW = List.of(
        "正在审查代码质量...",
        "模型中正在逐行分析代码...",
        "代码审查中，请耐心等待...",
        "正在检查潜在问题...",
        "审查完成后将给出建议..."
    );

    /** 搜索中：search_agent / search_tool 专用 */
    public static final List<String> SEARCHING = List.of(
        "正在搜索代码库...",
        "模型中正在定位相关文件...",
        "搜索中，请稍候...",
        "正在遍历项目文件...",
        "找到后将立即展示结果..."
    );

    // ==================== 占位符替换 ====================

    /** 获取文件写入提示词（替换 {file} 占位符） */
    public static List<String> getFileWriting(String filename) {
        String shortName = filename.contains("/") || filename.contains("\\")
                ? filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1)
                : filename;
        return FILE_WRITING_TPL.stream()
                .map(s -> s.replace("{file}", shortName))
                .collect(Collectors.toList());
    }

    /** 运行时动态注册/更新文件写入提示词（供 ChatPanel 在 onToolCalls 时调用） */
    public static void registerFileWriting(String key, String filename) {
        // 标记供 ChatWebView 下次 startAiStreamLoading 前注入
        pendingFileWritingKey = key;
        pendingFileWritingMsgs = toJsArray(getFileWriting(filename));
    }

    /** 运行时动态注册/更新文件编辑提示词（供 ChatPanel 在 onToolCalls 时调用） */
    public static void registerFileEditing(String key, String filename) {
        pendingFileWritingKey = key;
        pendingFileWritingMsgs = toJsArray(getFileEditing(filename));
    }

    /** 获取文件编辑提示词（替换 {file} 占位符） */
    public static List<String> getFileEditing(String filename) {
        String shortName = filename.contains("/") || filename.contains("\\")
                ? filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1)
                : filename;
        return FILE_EDITING_TPL.stream()
                .map(s -> s.replace("{file}", shortName))
                .collect(Collectors.toList());
    }

    /** 生成 JS 注册代码（供 ChatWebView 调用） */
    public static String getPendingJsRegistration() {
        if (pendingFileWritingKey == null) return null;
        String js = "registerLoadingMsgs('" + pendingFileWritingKey + "'," + pendingFileWritingMsgs + ");";
        pendingFileWritingKey = null;
        pendingFileWritingMsgs = null;
        return js;
    }

    private static String pendingFileWritingKey;
    private static String pendingFileWritingMsgs;

    // ==================== JSON 序列化（注入前端用） ====================

    /** 输出为 JS 数组字面量，供 HTML 模板注入 */
    public static String toJsArray(List<String> list) {
        return list.stream()
                .map(s -> '\'' + escapeJs(s) + '\'')
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }
}
