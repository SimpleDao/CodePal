package com.codepal.tools;

import com.codepal.model.ChatRequest;

import java.util.*;

/**
 * 工具定义类 —— 代码编辑基础工具集
 * 为 DeepSeek Function Calling 提供工具 JSON Schema 定义
 *
 * 工具列表：
 *   【代码浏览】
 *   1. locate_code_by_symbol —— 定位：输入类名或方法名，返回所在文件路径（PSI）
 *   2. view_file_outline     —— 看大纲：输入文件路径，返回类的属性、方法签名及注释（PSI）
 *   3. read_file_range       —— 精准读：输入文件路径、起始行和结束行，返回具体代码
 *   4. list_files            —— 列目录：列出指定目录下的文件和子目录，支持glob模式匹配
 *   5. search_tool           —— 全局搜：在整个项目里搜索关键词
 *
 *   【计划管理】
 *   6. todo                  —— 任务清单：创建/更新/查看任务Todo列表，复杂任务保持条理
 *
 *   【命令执行】
 *   7. run_command           —— 执行命令：在项目目录中执行shell/CMD命令（编译、测试、git等）
 *
 *   【代码修改】
 *   8. edit_file             —— 精确编辑：搜索替换文本，Diff确认
 *   9. write_file            —— 整文件写入：用新内容完全覆盖文件，适合大改动或新建文件已有内容覆盖
 *  10. create_new_file       —— 创建文件：创建尚不存在的新文件
 *  11. create_directory      —— 创建目录：创建新的目录（自动创建父目录）
 *  12. delete_file           —— 删除文件：删除文件或目录
 *  13. compile_files         —— 增量编译：只编译指定文件，验证能否编译通过（不受项目其他预存错误影响）
 *
 * @author 水龙吟
 * @date 2026-05-24
 */
public class ToolDefinitions {


    /**
     * 获取全部工具列表
     */
    public static List<ChatRequest.ToolDefinition> getAllTools() {
        return Arrays.asList(
                locateCodeBySymbolTool(),
                viewFileOutlineTool(),
                readFileRangeTool(),
                codeReviewTool(),
                                listFilesTool(),
                                 searchTool(),
                todoTool(),
                // readChatHistoryTool(),  // 已禁用：防止模型读取大量历史导致上下文爆炸
                runCommandTool(),
                validateCodeTool(),
                compileFilesTool(),
                editFileTool(),
                writeFileTool(),
                createNewFileTool(),
                createDirectoryTool(),
                deleteFileTool(),
                viewClassSourceTool(),
                loadSkillTool(),
                askUserQuestionTool(),
                viewImageTool(),
                queryDatabaseTool()
        );
    }

    /**
     * 获取工具关键词映射（工具名 → 搜索关键词集合）。
     * 用于 ToolIndex 构建，支撑动态工具发现。
     */
    public static Map<String, Set<String>> getToolKeywordsMap() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("locate_code_by_symbol", Set.of("locate", "symbol", "定位", "符号", "类名", "方法名", "class", "method", "find"));
        map.put("view_file_outline", Set.of("outline", "view", "大纲", "文件结构", "结构", "概览", "overview", "class", "methods"));
        map.put("read_file_range", Set.of("read", "file", "读取", "阅读", "查看", "代码", "code", "line", "行", "range"));
        map.put("code_review", Set.of("review", "审查", "检查", "lint", "质量", "check", "quality", "安全", "security"));
        map.put("list_files", Set.of("list", "列目录", "浏览", "目录", "文件列表", "dir", "ls", "browse", "structure"));
        map.put("search_tool", Set.of("搜索", "grep", "查找", "关键词", "内容", "find", "search", "contain",
                "引用", "调用", "usages", "references", "调用点", "谁调用", "哪里用"));
        map.put("todo", Set.of("todo", "任务", "计划", "task", "清单", "list", "plan", "步骤", "step"));
        map.put("run_command", Set.of("执行", "运行", "命令", "command", "shell", "cmd", "编译", "测试", "build", "test", "git", "mvn", "gradle"));
        map.put("validate_code", Set.of("验证", "检查", "lint", "错误", "bug", "质量", "validate", "check", "verify", "错误验证", "代码检查"));
        map.put("compile_files", Set.of("编译", "compile", "javac", "增量编译", "验证编译", "编译文件", "只编译", "build", "编译检查"));
        map.put("edit_file", Set.of("编辑", "修改", "edit", "替换", "replace", "改代码", "change", "modify", "更新", "update"));
        map.put("write_file", Set.of("写入", "覆盖", "write", "重写", "创建文件", "覆盖写入", "overwrite", "新文件"));
        map.put("create_new_file", Set.of("新建", "创建", "create", "new file", "新文件", "create file", "新建文件"));
        map.put("create_directory", Set.of("目录", "创建目录", "文件夹", "mkdir", "directory", "new folder", "新建目录"));
        map.put("delete_file", Set.of("删除", "delete", "移除", "remove", "删文件", "rm", "del"));
        map.put("view_class_source", Set.of("查看源码", "类源码", "依赖源码", "jar源码", "反编译", "看实现", "decompile", "source", "class source", "库源码"));
        map.put("load_skill", Set.of("skill", "技能", "源码", "依赖", "jar", "第三方", "source", "navigation", "指南", "文档"));
        map.put("ask_user_question", Set.of("提问", "询问", "澄清", "确认", "选择", "ask", "question", "clarify", "选择方案", "用户意见"));
        map.put("view_image", Set.of("图片", "图像", "截图", "视觉", "看图", "识别", "image", "vision", "ocr", "照片", "看截图"));
        map.put("query_database", Set.of("数据库", "查询", "表", "SQL", "mysql", "sqlite", "数据", "schema", "表结构", "表数据", "database", "query", "select", "sql"));
        return map;
    }

    /**
     * 项目搜索工具 —— 调用搜索子智能体进行代码探索
     */
    public static ChatRequest.ToolDefinition searchProjectTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", createStringProp(
                "搜索任务描述，用自然语言说明你想找什么。例如：「查找用户认证相关的代码」、「看看订单服务的主要入口在哪里」、「搜索数据库连接池的配置位置」"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("query"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "search_agent",
                        "调用搜索子智能体在项目中定位代码位置。" +
                        "当搜索范围过于宽泛、不确定代码在哪、或需要大量搜索探索时," +
                        "使用此工具将搜索任务委托给子智能体完成。子智能体返回精简的 路径+行号 列表，" +
                        "不会产生大量搜索结果污染你的上下文。" +
                        "如果你已明确知道要看哪个文件哪段代码，直接用 read_file_range，不需要此工具。",
                        params
                )
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
                "文件的完整路径。可以是项目内相对路径（如 src/main/java/com/example/UserService.java），也可以是 IDEA 之外的任意本地文件绝对路径（如 D:/repos/other/foo.java 或 /usr/local/foo.java）；插件不限制目录，项目外文件同样可读。"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "view_file_outline",
                        "查看指定文件的结构大纲：类的字段、方法签名（返回类型/参数/异常）、文档注释，以及每个元素的行号（L 标记）。" +
                        "这是「看全一个大文件」的廉价首选——无论文件多少行，outline 都只有几十行，能让你在不消耗大量上下文的情况下掌握全貌。" +
                        "当你需要了解一个类的整体结构、有哪些方法/endpoint，或准备定点读取某个方法体时使用。" +
                        "拿到 outline 后，用 read_file_range(start_line, end_line) 按行号精确读取你关心的那个方法，避免顺序通读整文件。",
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
                "文件的完整路径。可以是项目内相对路径（如 src/main/java/com/example/UserService.java），也可以是 IDEA 之外的任意本地文件绝对路径（如 D:/repos/other/foo.java 或 /usr/local/foo.java）；插件不限制目录，项目外文件同样可读。"));
        properties.put("start_line", createIntProp(
                "起始行号（从1开始），传0或不传则从第一行开始", 0, null));
        properties.put("end_line", createIntProp(
                "结束行号（含），传0或不传则读取到文件末尾。如果start_line和end_line都传0，则读取整个文件", 0, null));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "read_file_range",
                        "读取指定文件指定行范围的代码内容。可精确控制读取范围，避免加载过多无关内容。" +
                        "⚠️ 大文件阅读策略（重要，避免上下文爆炸）：" +
                        "① 先用 view_file_outline 看结构（廉价，一次返回全部字段/方法签名，且含行号）；" +
                        "② 只对你关心的那个方法/字段，用 start_line+end_line 精确读取其区间，不要顺序通读整文件；" +
                        "③ 整文件读取（不传 start_line 和 end_line）会被截断到约 5 万字符，要看全部内容请显式指定 start_line=1、end_line=<总行数>（行号可从 outline 获取），一次拿全而非 200-500 行分段。" +
                        "返回内容含文件路径与读取范围；行号以 L 标记便于二次定点读取。",
                        params
                )
        );
    }

    /**
     * 代码审查工具 —— 两级审查：Linter 优先，LLM 兜底
     */
    public static ChatRequest.ToolDefinition codeReviewTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "要审查的文件路径，例如 src/main/java/com/example/UserService.java"));
        properties.put("mode", createEnumProp(
                "审查模式：auto=自动（linter优先，没linter用LLM）、linter=仅linter快速检查、llm=仅LLM深度审查",
                Arrays.asList("auto", "linter", "llm")));
        properties.put("focus", createEnumProp(
                "审查重点（llm模式有效）：all=全面审查、security=安全、performance=性能、bug=潜在Bug、style=代码风格、best-practice=最佳实践",
                Arrays.asList("all", "security", "performance", "bug", "style", "best-practice")));
        properties.put("start_line", createIntProp(
                "可选：起始行号，只审查代码片段。不传则审查整个文件", 1, null));
        properties.put("end_line", createIntProp(
                "可选：结束行号，只审查代码片段。不传则审查到文件末尾", 1, null));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "code_review",
                        "对指定文件进行代码审查。采用两级审查机制：先用项目本地的linter工具快速检查（省token、速度快），" +
                                "如果没有安装linter则用LLM进行深度审查。支持JavaScript/TypeScript/Vue (ESLint)、Python (Ruff)、Java (PMD)、Shell (ShellCheck)等。" +
                                "当你写完代码想检查质量、或想分析某段代码是否有问题时使用。",
                        params
                )
        );
    }

    /**
     * 全局搜索工具 —— 在整个项目里搜索关键词
     */
    public static ChatRequest.ToolDefinition searchTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", createStringProp(
                "要搜索的关键词或正则表达式（regex=true 时），例如 cancelOrder、@Transactional、inventory。"
                        + "mode=usages 时此参数改为传类名或方法名（如 UserService、cancelOrder）"));
        properties.put("mode", createEnumProp(
                "搜索模式：text=文本匹配（默认）；usages=语义引用查找——基于 IDEA ReferencesSearch，"
                        + "能区分同名但无关的符号（不同类的同名方法、重载），重构前评估影响面、理解某 API 被谁使用时首选。"
                        + "usages 模式下 keyword 传符号名，仅支持当前项目，忽略 path/file_pattern/regex",
                Arrays.asList("text", "usages")));
        properties.put("regex", createBoolProp(
                "可选。设为 true 时 keyword 按正则表达式匹配（如 \\binsertBefore\\(.*?,ts\\)）；"
                + "默认 false 按字面量文本匹配。搜索含括号/点号等特殊字符的代码片段时，要么保持 regex=false，要么正确转义。"));
        properties.put("file_pattern", createStringProp(
                "可选的文件类型过滤，例如 *.java、*.xml，不传则搜索所有代码文件"));
        properties.put("path", createStringProp(
                "可选。指定一个本地目录的绝对路径（例如 D:/repos/other-project），"
                + "则在该目录（不依赖当前 IDEA 项目索引）中按关键词搜索；"
                + "传 \".\" 或不传则在当前打开的 IDEA 项目内用索引搜索。"
                + "选择指引：要搜的是当前 IDEA 工程（或其中子模块）的代码 -> 不传 path；"
                + "要搜的是未在 IDEA 中打开的任意本地目录 -> 传该目录的绝对路径。"));
        properties.put("max_results", createIntProp(
                "最多返回结果数，默认10", 1, 50));
        params.put("properties", properties);
        params.put("required", Arrays.asList("keyword"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "search_tool",
                        "在整个项目（或指定本地目录）中搜索代码，返回文件路径、行号和匹配内容。"
                        + "两种模式：① text（默认）：文本匹配，设 regex=true 可用正则表达式；"
                        + "② usages（mode=\"usages\"）：语义引用查找，keyword 传类名/方法名，返回其所有引用/调用点，"
                        + "能区分同名但无关的符号——查「这个方法被谁调用」、重构评估影响面时首选，比文本匹配精准。"
                        + "默认在当前 IDEA 项目内用索引搜索（覆盖所有子模块）；"
                        + "传 path 指定 IDEA 之外的本地目录时，改用文件系统遍历（不依赖项目索引）。"
                        + "★ 自动排除点开头的隐藏目录（如 .git/.codebuddy/.gradle）及 node_modules/target/build 等构建产物目录，无需手动排除。",
                        params
                )
        );
    }

    /**
     * 任务清单工具 —— 创建/更新/查看Todo列表，帮助复杂任务保持条理
     */
    public static ChatRequest.ToolDefinition todoTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", createStringProp(
                "操作类型：create（创建/替换整个任务列表）、update（更新任务状态/追加新任务）、list（查看当前任务列表）"));

        Map<String, Object> todoItem = new LinkedHashMap<>();
        todoItem.put("type", "object");
        Map<String, Object> todoItemProps = new LinkedHashMap<>();
        todoItemProps.put("id", createStringProp(
                "任务唯一标识，简单数字即可（如1、2、3），用于后续更新时引用"));
        todoItemProps.put("content", createStringProp(
                "任务内容描述，简洁明确说明要做什么"));
        todoItemProps.put("status", createStringProp(
                "任务状态：pending（待处理）、in_progress（进行中）、completed（已完成）。创建时默认pending"));
        todoItemProps.put("priority", createStringProp(
                "优先级：high（高）、medium（中，默认）、low（低）"));
        todoItem.put("properties", todoItemProps);
        todoItem.put("required", Arrays.asList("id"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("description", "任务列表。create时传入完整列表（替换旧的），update时传入要更新的任务（自动追加新任务）");
        todosProp.put("items", todoItem);
        properties.put("todos", todosProp);

        params.put("properties", properties);
        params.put("required", Arrays.asList("action"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "todo",
                        "管理任务Todo列表。面对涉及3个以上文件修改或多个步骤的复杂任务时，"
                                + "你应该在开始前调用todo(action=create)拆解为具体步骤清单；"
                                + "每开始一项任务前调用todo(action=update)将其标记为in_progress；"
                                + "每完成一项任务后调用todo(action=update)将其标记为completed并开始下一项；"
                                + "随时可以调用todo(action=list)查看当前进度。"
                                + "简单任务（只改1-2个文件）不需要使用此工具。"
                                + "注意：todos列表中同一时间只能有一个任务处于in_progress状态。",
                        params
                )
        );
    }

    /**
     * 读取归档历史消息工具 —— 对话压缩后，用于查询更早的历史消息
     */
    @Deprecated
    public static ChatRequest.ToolDefinition readChatHistoryTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("limit", createIntProp(
            "要读取的消息条数，默认20条，最大100条", 1, 100));
        properties.put("offset", createIntProp(
            "偏移量，从第几条开始读（从0开始），默认0", 0, null));
        properties.put("keyword", createStringProp(
            "可选的关键词过滤，只返回包含该关键词的消息。不传则返回所有消息"));

        params.put("properties", properties);
        params.put("required", Arrays.asList());

        return new ChatRequest.ToolDefinition(
            new ChatRequest.ToolDefinition.FunctionDef(
                "read_chat_history",
                "读取已归档的对话历史消息。当对话被压缩后，更早的消息会被归档到本地数据库。"
                + "如果你需要参考之前的讨论内容、代码片段、错误信息等，可以使用此工具查询归档消息。"
                + "默认读取最近的20条归档消息，可通过 limit 和 offset 分页浏览。"
                + "注意：只有被压缩过的对话才需要使用此工具，当前上下文中的消息直接可见。",
                params
            )
        );
    }

    /**
     * 编辑文件工具（可写）—— 支持多组搜索替换，统一 Diff 确认
     */
    public static ChatRequest.ToolDefinition editFileTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "要编辑的文件的完整路径，例如 src/main/java/com/example/UserService.java"));

        // edits 是核心字段；search/replace 是单次简写
        Map<String, Object> editItem = new LinkedHashMap<>();
        editItem.put("type", "object");
        Map<String, Object> editItemProps = new LinkedHashMap<>();
        editItemProps.put("search", createStringProp(
                "要在文件中精确匹配的文本片段，必须与文件中内容完全一致（包括空格、缩进、换行）。"
                + "必须在文件中匹配恰好一处。"));
        editItemProps.put("replace", createStringProp(
                "用于替换的新文本内容。要删除则传空字符串。"));
        editItem.put("properties", editItemProps);
        editItem.put("required", Arrays.asList("search", "replace"));

        Map<String, Object> editsProp = new LinkedHashMap<>();
        editsProp.put("type", "array");
        editsProp.put("description",
                "编辑列表，每个元素为一组 {search, replace}。"
                + "所有修改按列表顺序执行，合并为一次 Diff 展示。"
                + "如果只有一处修改，可直接用顶层的 search+replace 简写。");
        editsProp.put("items", editItem);
        properties.put("edits", editsProp);

        properties.put("search", createStringProp(
                "单次替换的匹配文本（edits 的简写形式）"));
        properties.put("replace", createStringProp(
                "单次替换的新内容（与 search 配对使用）"));

        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "edit_file",
                        "编辑文件：通过 edit_file 批量搜索替换文本。"
                        + "推荐使用 edits:[{search,replace},…] 一次完成多处修改；"
                        + "单处修改也可直接用顶层 search+replace。"
                        + "每个 search 必须精确匹配（含空格缩进）且在文件中唯一。"
                        + "所有修改合并为一个 Diff 由用户确认。",
                        params
                )
        );
    }

    /**
     * 新建文件工具（可写）—— 在项目目录下创建新文件，路径不存在则自动创建父目录
     */
    public static ChatRequest.ToolDefinition createNewFileTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "要创建的文件路径（相对于项目根目录或绝对路径），例如 src/main/java/com/example/NewService.java"));
        properties.put("content", createStringProp(
                "文件的初始内容，可选。不传则创建空文件"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "create_new_file",
                        "在项目中创建一个新文件。如果父目录不存在会自动创建。可选指定文件的初始内容。"
                        + "当你需要新建一个尚不存在的代码文件、配置文件或文档文件时使用。",
                        params
                )
        );
    }

    public static ChatRequest.ToolDefinition createDirectoryTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dir_path", createStringProp(
                "要创建的目录路径（相对于项目根目录或绝对路径），例如 src/main/java/com/example/utils。"
                + "父目录不存在会自动创建。"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("dir_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "create_directory",
                        "创建一个新目录。如果父目录不存在会自动创建。"
                        + "当你需要先建立目录结构再创建文件时使用，避免用 .gitkeep 等占位文件。"
                        + "注意：禁止用 run_command 执行 mkdir 等创建目录的命令，必须使用此工具。",
                        params
                )
        );
    }

    /**
     * 删除文件工具（可写）—— 删除指定文件或空目录
     */
    public static ChatRequest.ToolDefinition deleteFileTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "要删除的文件路径（相对于项目根目录或绝对路径），例如 src/main/java/com/example/OldService.java"));
        properties.put("recursive", createBoolProp(
                "是否递归删除目录及其内容，默认false。"
                + "仅当需要删除整个目录时设为true，删除单个文件不需要。"
                + "⚠️ 警告：递归删除不可逆，请谨慎使用！"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "delete_file",
                        "删除文件或目录。比用命令行删除更安全：有明确的删除确认机制，防止误删。"
                        + "删除单个文件直接使用；删除目录需要指定 recursive=true。"
                        + "⚠️ 删除操作不可逆，请确认文件路径后再调用。"
                        + "注意：禁止用 run_command 执行 rm/del 等删除命令，必须使用此工具。",
                        params
                )
        );
    }

    /**
     * 查看类源码工具（只读）—— 自动定位依赖/库中的类并反编译，源码落盘 temp，返回路径
     *
     * <p>底层用 IDEA PSI（JavaPsiFacade）直接定位类，无需模型手动去找 jar 路径：
     * 优先读取 -sources.jar 的真实源码；无源码时调用 IDEA 内置反编译器（Fernflower）得到可读 .java，
     * 写入临时目录（默认 temp/sources），返回文件路径，模型再用 read_file_range 按需读取片段。
     * 比 javap 更完整（javap 只有方法签名/字节码，看不到实现）。
     */
    public static ChatRequest.ToolDefinition viewClassSourceTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("target", createStringProp(
                "要查看源码的目标，支持三种形式："
                        + "① 全限定类名，如 com.google.gson.Gson、okhttp3.OkHttpClient；"
                        + "② .class 文件绝对路径；"
                        + "③ 整个 .jar 的绝对路径（如 D:/repo/x/gson-2.10.1.jar，会整包反编译）"));
        properties.put("output_dir", createStringProp(
                "可选：源码写入的临时目录（相对项目根或绝对路径），默认 temp/sources。"
                        + "反编译后请用 read_file_range 读取该目录下的 .java 文件，类较大时分批读取避免占满上下文"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("target"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "view_class_source",
                        "查看任意第三方依赖 / jar 里某个 Java 类的源码。自动定位（无需手动找 jar 路径或坐标），"
                                + "优先读取官方 -sources.jar 源码，否则调用 IDEA 内置反编译器（Fernflower）得到可读 .java 实现，"
                                + "并写入临时目录（默认 temp/sources），返回文件路径——你再用 read_file_range 按需读取片段。"
                                + "支持传入全限定类名、.class 绝对路径、或整个 .jar 绝对路径（整包反编译）。"
                                + "比 javap 更完整：javap 只能看到方法签名/字节码，本工具能看到方法体实现。"
                                + "适合：想读懂某个库的类是怎么实现的、确认 API 行为、排查依赖冲突。"
                                + "项目内源码会直接返回原文件路径，请用 read_file_range 读取。",
                        params
                )
        );
    }

    /**
     * 列目录/查找文件工具 —— 列出目录内容，支持glob模式匹配文件名
     */
    public static ChatRequest.ToolDefinition listFilesTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", createStringProp(
                "要列出的目录路径。两种情况："
                + "① 不传或传 \".\" -> 列出当前 IDEA 项目根目录；"
                + "② 传一个绝对路径（例如 D:/repos/other-project）-> 列出该目录（用文件系统遍历，不依赖 IDEA 项目索引）。"
                + "选择指引：要查看的是当前 IDEA 工程内的目录 -> 传相对项目根的路径或不传；"
                + "要查看未在 IDEA 中打开的任意本地目录 -> 传该目录的绝对路径。"
                + "注意：这是只读的目录概览工具，不要反复调用本工具来探索项目——"
                + "除非你明确需要查看某一目录下的文件清单，否则请优先用 search_tool 按关键词搜内容、"
                + "或用 search_agent 委托子智能体定位代码，避免无谓地反复浏览根目录。"));
        properties.put("pattern", createStringProp(
                "可选的glob文件名过滤模式，例如 *.java、**/*.xml、**/test/**。"
                + "不传则列出该目录下所有文件和子目录。"));
        properties.put("recursive", createBoolProp(
                "是否递归列出子目录内容，默认false。如果指定了pattern且包含**则自动递归。"));
        properties.put("max_depth", createIntProp(
                "递归最大深度，默认3层。仅当recursive=true时有效。", 1, 10));
        params.put("properties", properties);

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "list_files",
                        "列出指定目录下的文件和子目录，支持glob文件名模式匹配。"
                        + "默认在当前 IDEA 项目内用索引列出（传相对路径或不传）；"
                        + "传绝对路径时可列出未在 IDEA 中打开的任意本地目录（用文件系统遍历）。"
                        + "仅在需要查看某个具体目录的文件清单时使用（例如确认某模块下有哪些类）。"
                        + "不要把它当作反复探索项目的入口——定位代码优先用 search_tool，"
                        + "宽泛搜索交给 search_agent，避免无意义地反复浏览根目录。",
                        params
                )
        );
    }

    /**
     * 整文件写入工具（可写）—— 用新内容完全覆盖现有文件，或创建文件时写入完整内容
     */
    public static ChatRequest.ToolDefinition writeFileTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", createStringProp(
                "要写入的文件路径（相对于项目根目录或绝对路径），例如 src/main/java/com/example/UserService.java"));
        properties.put("file_content", createStringProp(
                "要写入文件的完整内容。将完全覆盖文件原有内容。"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_path", "file_content"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "write_file",
                        "将完整内容写入文件，覆盖原有内容。"
                        + "适用于：1) 需要大幅重写整个文件的场景（search/replace难以表达时）；"
                        + "2) 从头创建一个有完整内容的文件（文件已存在会被覆盖，不存在则创建）。"
                        + "修改后会弹出Diff面板供用户确认。"
                        + "注意：如果文件已存在且你只需要做少量修改，请使用edit_file工具。",
                        params
                )
        );
    }

    /**
     * 执行命令行工具 —— 在项目目录中执行 shell/CMD 命令
     */
    public static ChatRequest.ToolDefinition runCommandTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", createStringProp(
                "要执行的完整命令，例如：mvn compile -q、gradle build、git status、mvn test -Dtest=UserServiceTest"));
        properties.put("cwd", createStringProp(
                "可选，执行命令的工作目录，相对于项目根目录。不传则在项目根目录执行。"));
        properties.put("timeout", createIntProp(
                "可选，超时秒数，默认30秒，最大120秒。", 5, 120));
        params.put("properties", properties);
        params.put("required", Arrays.asList("command"));
        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "run_command",
                        "在项目目录中执行命令行命令（如编译、测试、git、构建等），"
                                + "返回命令的退出码、stdout和stderr输出。"
                                + "Windows下使用cmd.exe /c执行，Linux/Mac下使用sh -c执行。"
                                + "输出超过30000字符会被截断。包含内置安全黑名单拦截高危命令。"
                                + "常用于：验证编译是否通过(mvn compile / gradle build)、运行测试(mvn test)、查看git状态等。",
                        params
                )
        );
    }

    public static ChatRequest.ToolDefinition validateCodeTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> filePathsItem = new LinkedHashMap<>();
        filePathsItem.put("type", "string");

        Map<String, Object> filePathsProp = new LinkedHashMap<>();
        filePathsProp.put("type", "array");
        filePathsProp.put("description", "要验证的文件路径列表，相对于项目根目录。例如：[\"src/main/java/com/example/UserService.java\"]");
        filePathsProp.put("items", filePathsItem);
        properties.put("file_paths", filePathsProp);

        params.put("properties", properties);
        params.put("required", Arrays.asList("file_paths"));
        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "validate_code",
                        "调用错误验证子智能体对指定文件进行 linter 错误检查。" +
                        "当你完成了代码生成、重构或修改了多个文件后，使用此工具进行兜底验证。" +
                        "只检查 ERROR 级别的错误（不包括 WARNING 和 INFO）。" +
                        "返回结构化报告：错误类型、文件路径、行号、错误描述。" +
                        "注意：此工具只负责发现错误，不提供修复建议。修复后需要再次调用此工具验证。",
                        params
                )
        );
    }

    /**
     * 增量编译工具 —— 调用 IDEA 自带编译器只编译指定文件（及其必需依赖），
     * 不受项目其他文件预存错误影响，用于验证「我改过的文件」能否编译通过。
     */
    public static ChatRequest.ToolDefinition compileFilesTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> filePathsItem = new LinkedHashMap<>();
        filePathsItem.put("type", "string");
        Map<String, Object> filePathsProp = new LinkedHashMap<>();
        filePathsProp.put("type", "array");
        filePathsProp.put("description", "要编译的文件路径列表（相对于项目根目录），例如 [\"src/main/java/com/example/UserService.java\"]。只编译这些文件及其编译必需的依赖。");
        filePathsProp.put("items", filePathsItem);
        properties.put("file_paths", filePathsProp);

        properties.put("scope", createEnumProp(
                "编译范围：files=只编译 file_paths 列出的文件（默认，最精准，不会因项目其他地方的错误而失败）；"
                        + "module=编译这些文件所属模块（可能暴露模块内其他不相关错误，仅在需要整模块验证时使用）",
                Arrays.asList("files", "module")));
        params.put("properties", properties);
        params.put("required", Arrays.asList("file_paths"));
        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "compile_files",
                        "调用 IDEA 内置增量编译器，只编译你指定的文件及其编译必需的依赖，并返回编译结果（错误数 / 警告数）。"
                                + "用于验证「你修改过的文件」能否真正编译通过——这比 linter 静态检查更可靠（linter 不解析真实类型/依赖关系）。"
                                + "⚠️ 重要：验证自己改动的文件时，优先用本工具，不要用 run_command 跑 mvn compile / gradle build 全量编译——"
                                + "全量编译会因项目里任何一处预存错误而整体失败，掩盖你改的文件本身是否正确。"
                                + "本工具只针对你点名的文件，项目其他位置的预存错误不会干扰，能精确反映你改动的文件本身的编译状态（返回 0 错误即编译通过）。"
                                + "若编译未通过，错误计数可能包含你未改动文件的预存问题，请忽略它们，专注修复你改动的文件；逐条错误位置可在 IDEA 的 Problems / 编译输出面板查看。",
                        params
                )
        );
    }

    /**
     * 技能加载工具（按需读取） —— 从用户机器可写目录加载技能说明文档，
     * 缺失时回退到插件内置资源。用于把长文档从 system prompt 中移出、按需加载以节省 token。
     */
    public static ChatRequest.ToolDefinition loadSkillTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", createStringProp(
                "要加载的技能名称（不含 .md 后缀），例如 source-navigation。"
                + "不传默认加载 source-navigation（查看第三方依赖/jar 源码指南）。"));
        properties.put("list", createBoolProp(
                "可选，设为 true 时列出当前所有可用技能名称，不加载具体内容。"));
        params.put("properties", properties);
        params.put("required", Arrays.asList());

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "load_skill",
                        "按需加载技能（skill）说明文档。当你需要执行某个专业流程但不知道具体步骤时调用，"
                                + "例如：需要阅读项目引入的第三方 jar / npm 依赖的源码时，调用 load_skill(name=\"source-navigation\")"
                                + "获取按项目类型（Gradle/Maven/Vue）定位依赖、必要时反编译的完整指南。"
                                + "技能文件位于用户机器可写目录，可由用户自行修改而无需重新打包插件。"
                                + "技能内容加载一次后会留在对话历史中，请先优先检查对话历史是否已存在技能内容，避免重复加载，浪费用户token。",
                        params
                )
        );
    }

     // ─────────────────────────────────────────────────────────────────────────
     // 模型主动提问工具
     // ─────────────────────────────────────────────────────────────────────────

    public static ChatRequest.ToolDefinition askUserQuestionTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> optionItem = new LinkedHashMap<>();
        optionItem.put("type", "object");
        Map<String, Object> optionItemProps = new LinkedHashMap<>();
        optionItemProps.put("label", createStringProp("选项短标签，简短几个词，例如：TypeScript、Vue3、PostgreSQL"));
        optionItemProps.put("description", createStringProp("选项含义说明，用一句话解释该选项的适用场景或区别"));
        optionItem.put("properties", optionItemProps);
        optionItem.put("required", Arrays.asList("label"));

        Map<String, Object> questionItem = new LinkedHashMap<>();
        questionItem.put("type", "object");
        Map<String, Object> questionItemProps = new LinkedHashMap<>();
        questionItemProps.put("question", createStringProp("完整问句，例如：「你希望使用哪种前端框架？」"));
        Map<String, Object> optionsProp = new LinkedHashMap<>();
        optionsProp.put("type", "array");
        optionsProp.put("description", "选项列表；如省略则用户自由输入文本回答");
        optionsProp.put("items", optionItem);
        questionItemProps.put("options", optionsProp);
        questionItemProps.put("multiSelect", createBoolProp("是否允许多选，默认 false（单选）"));
        questionItem.put("properties", questionItemProps);
        questionItem.put("required", Arrays.asList("question"));

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> questionsProp = new LinkedHashMap<>();
        questionsProp.put("type", "array");
        questionsProp.put("description", "要问用户的问题数组，至少 1 道，建议一次不超过 3 道");
        questionsProp.put("items", questionItem);
        properties.put("questions", questionsProp);

        params.put("properties", properties);
        params.put("required", Arrays.asList("questions"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "ask_user_question",
                        "当你需要向用户澄清信息（而非猜测）时调用此工具：提出一道或多道选择题，用户在聊天窗内作答后结果返回给你。"
                                + "适用场景：需求不确定、技术选型需要用户拍板、缺少关键信息会导致走错方向时。"
                                + "每道题可提供若干选项（label简短，description说明含义）；省略options则为自由输入题。"
                                + "multiSelect=true 表示可多选。每题自动附「其他」输入框供自由补充。"
                                + "把你推荐的选项放在第一个并在label中标注「(推荐)」。"
                                + "注意：只在真正不确定时才提问，不要用此工具替代自主调研；能用 search_agent/read_file_range 确认的就不要问用户。",
                        params
                )
        );
    }

    /**
     * 查看图片工具 —— 把图片交给独立的视觉模型理解，返回文字描述/答案。
     * 主模型（可能是纯文本模型）通过它间接"看"用户附带的图片。
     */
    public static ChatRequest.ToolDefinition viewImageTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("image_path", createStringProp(
                "图片文件的绝对路径。即用户消息中出现的「[图片: <路径>]」里的路径，" +
                "或你自己此前记录的图片路径。插件会把该文件交给视觉模型查看。"));
        properties.put("question", createStringProp(
                "可选：针对这张图片的具体问题或关注点。例如「这张架构图里数据库在哪一层？」" +
                "不传则让视觉模型做通用描述。"));
        params.put("properties", properties);
        params.put("required", Arrays.asList("image_path"));

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "view_image",
                        "当用户消息附带图片（消息中含「[图片: <路径>]」标记）且你需要理解图片内容时调用此工具。" +
                        "它会把图片发给独立的视觉模型，返回图片的文字描述或对你所提问题的回答。" +
                        "注意：你（主模型）本身看不到图片字节，必须经由本工具获取图片信息后再组织回答。" +
                        "若用户未附带图片，不要调用此工具。",
                        params
                )
        );
    }

    /**
     * 数据库查询工具 —— 连接已配置的数据源（MySQL / SQLite），执行 SQL。
     * 两种模式：schema=表结构查询；data=表数据查询（硬性最多 10 行）。
     */
    public static ChatRequest.ToolDefinition queryDatabaseTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");

        // ★ 动态拼出「当前已配置的数据源清单」，直接写进工具描述与 db_name 说明。
        //   这是让模型敢直接调用本工具的关键：模型要填 db_name，却不知道有哪些可选，
        //   就会反过来向用户索要连接信息（表现为"请确认数据库连接配置"而根本不调用工具）。
        //   工具描述每次请求都会带上，比系统提示更即时、更可靠。
        String sourceList = buildDataSourceListForPrompt();
        String defaultName = com.codepal.tools.ToolExecutor.getDefaultDataSourceName();
        String dbNameDesc = (sourceList.isEmpty())
                ? "已配置的数据源名称（当前【没有任何】已配置数据源，请提示用户先在工具栏左侧「数据源」中配置）。"
                : "数据源名称，必须从以下【已配置数据源】中选择："
                        + sourceList + "。不可臆造未列出的名字。"
                        + ((defaultName == null || defaultName.isBlank())
                                ? "（当前未默认选中，请从上面清单里挑一个最匹配的）"
                                : ("（用户已默认选中 `" + defaultName
                                        + "`，省略本字段即可用它；若用户明确指名了别的库，则填清单中对应的那个）"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("db_name", createStringProp(dbNameDesc));
        properties.put("db_type", createEnumProp(
                "数据库类型，决定 JDBC 驱动与方言：mysql=MySQL；sqlite=SQLite",
                Arrays.asList("mysql", "sqlite")));
        properties.put("mode", createEnumProp(
                "查询模式：schema=表结构查询（返回指定表的列名/类型/是否可空/注释，不返回数据行）；"
                        + "data=表数据查询（执行给定 SQL，最多返回 10 行数据，超出部分被截断）",
                Arrays.asList("schema", "data")));
        properties.put("table_name", createStringProp(
                "schema 模式下必填：要查看结构的表名，例如 users、orders。data 模式忽略此字段。"));
        properties.put("sql", createStringProp(
                "data 模式下必填：要执行的只读 SQL，支持 SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN，"
                        + "例如 SELECT * FROM users WHERE age>18，或 SHOW TABLES 列出所有表、DESC users 看结构。"
                        + "SELECT 会自动在末尾拼接 LIMIT 10，无需也不应自己写 LIMIT（SHOW/DESC 等不拼 LIMIT）。"
                        + "禁止写操作（INSERT/UPDATE/DELETE/DROP 等），且不得包含分号。"
                        + "schema 模式忽略此字段。"));
        params.put("properties", properties);
        // ★ db_name / db_type 不再是 required：
        //   db_name 可回退到用户默认选中的数据源；db_type 可从数据源配置自动推断。
        //   若强制 required，模型在拿不准时会放弃调用、转而向用户索要连接信息。
        params.put("required", Arrays.asList("mode"));

        StringBuilder desc = new StringBuilder();
        desc.append("连接用户已配置的数据源执行 SQL 查询，只读、自动执行无需确认。");
        if (!sourceList.isEmpty()) {
            desc.append("【当前已配置的数据源】").append(sourceList);
            if (defaultName != null && !defaultName.isBlank()) {
                desc.append("；其中用户已默认选中 `").append(defaultName)
                        .append("`，未指定 db_name 时自动使用它");
            }
            desc.append("。");
        } else {
            desc.append("【当前没有任何已配置数据源】，请提示用户先在工具栏左侧「数据源」按钮中配置后再查询。");
        }
        desc.append("两种模式：① mode=\"schema\" 传 table_name，返回该表的列结构（列名、类型、可空、键、注释），"
                        + "适合先了解表有哪些字段再决定怎么查；"
                        + "② mode=\"data\" 传 sql，执行只读查询并返回数据（SELECT 硬性最多 10 行；"
                        + "SHOW/DESC/DESCRIBE/EXPLAIN 原样执行、不拼 LIMIT，可用于探索有哪些表）。"
                        + "★ @库 约定：用户在输入框用 @ 选中数据源后，消息中会出现形如「@库codepalSQL」的标记，"
                        + "其中「@库」后面的名字就是 db_name，直接取用即可。"
                        + "★ 用户提到某个已配置库/表时【必须直接调用本工具】，不要向用户索要连接信息、URL、用户名密码——"
                        + "这些都在数据源配置里，工具会自行读取。"
                        + "★ 失败时本工具会返回【完整诊断】（SQLState、ErrorCode、异常链、堆栈、实际执行的 SQL），"
                        + "请据此自行判断并修正（例如 1146=表不存在，可改用 SHOW TABLES 确认真实表名），"
                        + "而不是直接告诉用户「配置有问题」。"
                        + "注意：本工具只能查询已配置的库，不能访问任意未配置的数据库。");

        return new ChatRequest.ToolDefinition(
                new ChatRequest.ToolDefinition.FunctionDef(
                        "query_database",
                        desc.toString(),
                        params
                )
        );
    }

    /**
     * 拼出「已配置数据源」清单文本（供工具描述注入），形如：`codepalSQL`(mysql)、`app`(sqlite)。
     * 读取失败或无配置时返回空串（调用方据此给出不同提示）。
     */
    private static String buildDataSourceListForPrompt() {
        try {
            // getAll() 返回 DatabaseComboItem（仅 id/name/type），清单只需 name+type，够用
            java.util.List<com.codepal.model.DatabaseComboItem> sources =
                    com.codepal.db.DataSourceDao.getAll();
            if (sources.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (com.codepal.model.DatabaseComboItem info : sources) {
                if (info == null || info.name == null || info.name.isBlank()) continue;
                if (!sb.isEmpty()) sb.append("、");
                sb.append("`").append(info.name).append("`(")
                        .append(info.type == null ? "mysql" : info.type).append(")");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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

    private static Map<String, Object> createEnumProp(String description, List<String> enumValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", enumValues);
        return p;
    }
}
