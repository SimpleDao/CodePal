package com.loongc.tools;

import com.loongc.model.ChatRequest;

import java.util.*;

/**
 * 工具定义类 —— "四大金刚" 工具集
 * 为 DeepSeek Function Calling 提供工具 JSON Schema 定义
 *
 * 四大金刚：
 *   1. locate_code_by_symbol —— 定位：输入类名或方法名，返回所在文件路径
 *   2. view_file_outline     —— 看大纲：输入文件路径，返回类的属性、方法签名及注释（PSI）
 *   3. read_file_range       —— 精准读：输入文件路径、起始行和结束行，返回具体代码
 *   4. search_grep           —— 全局搜：在整个项目里搜索关键词

 * @author 水龙吟
 * @date 2026-05-24
 */
public class ToolDefinitions {


    /**
     * 获取四大金刚工具列表
     */
    public static List<ChatRequest.ToolDefinition> getAllTools() {
        return Arrays.asList(
                locateCodeBySymbolTool(),
                viewFileOutlineTool(),
                readFileRangeTool(),
                searchGrepTool()
        );
    }

    /**
     * 定位代码工具 —— 输入类名或方法名，返回它所在的文件路径
     */
    public static ChatRequest.ToolDefinition locateCodeBySymbolTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("symbol", createStringProp(
                "要查找的类名或方法名，例如 UserService、cancelOrder、OrderRepository"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("symbol"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "locate_code_by_symbol",
                        "在项目中查找指定类名或方法名所在的文件路径。当你需要知道某个类或方法定义在哪个文件中时使用。",
                        params
                )
        );
    }

    /**
     * 查看文件大纲工具 —— 输入文件路径，返回类的属性、方法签名及注释（利用 PSI）
     */
    public static ChatRequest.ToolDefinition viewFileOutlineTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "文件的完整路径，例如 src/main/java/com/example/UserService.java"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "view_file_outline",
                        "查看指定文件的结构大纲，包括类的字段（属性）、方法签名、参数列表和文档注释。当你需要快速了解一个类的整体结构时使用，不必读取全部代码。",
                        params
                )
        );
    }

    /**
     * 精准读取文件工具 —— 输入文件路径、起始行和结束行，返回具体代码
     */
    public static ChatRequest.ToolDefinition readFileRangeTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "文件的完整路径，例如 src/main/java/com/example/UserService.java"));
        properties.put("start_line", createIntProp(
                "起始行号（从1开始），默认从第一行开始", 1, null));
        properties.put("end_line", createIntProp(
                "结束行号（含），默认读取到文件末尾", 1, null));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "read_file_range",
                        "读取指定文件指定行范围的代码内容。当你已经知道要查看哪个文件的哪一段代码时使用，可以精确控制读取范围，避免加载过多无关内容。",
                        params
                )
        );
    }

    /**
     * 全局搜索工具 —— 在整个项目里搜索关键词
     */
    public static ChatRequest.ToolDefinition searchGrepTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", createStringProp(
                "要搜索的关键词，例如 cancelOrder、@Transactional、inventory"));
        properties.put("file_pattern", createStringProp(
                "可选的文件类型过滤，例如 *.java、*.xml，不传则搜索所有代码文件"));
        properties.put("max_results", createIntProp(
                "最多返回结果数，默认10", 1, 50));
        params.put("properties", properties);
        params.put("required", Arrays.asList("keyword"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "search_grep",
                        "在整个项目中搜索包含指定关键词的代码行，返回文件路径、行号和匹配内容。当你需要从全局视角查找某个功能或逻辑在哪里实现时使用。",
                        params
                )
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 辅助方法：构建 JSON Schema Property
    // ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> createStringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> createIntProp(String description, Integer minimum, Integer maximum) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        if (minimum != null) p.put("minimum", minimum);
        if (maximum != null) p.put("maximum", maximum);
        return p;
    }

    private static Map<String, Object> createBoolProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "boolean");
        p.put("description", description);
        return p;
    }
}
