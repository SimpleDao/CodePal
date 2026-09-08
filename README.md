# CodePal Assistant — IntelliJ 平台 AI 编程助手插件

CodePal Assistant 是一款运行在 IntelliJ IDEA（以及 Android Studio 等自带 Java 模块的 IntelliJ 平台 IDE）中的 AI 编程助手插件。它把大模型对话、代码生成、文件编辑、数据源查询、任务待办与项目上下文分析整合进一个侧边栏工具窗口，支持 **OpenAI 兼容**与 **Anthropic** 两种接口协议，并能接入 **MCP** 工具生态。

> 开源地址：https://github.com/SimpleDao/CodePal.git
> Marketplace 插件 ID：`com.loongc.plugin`（Java 包名：`com.codepal`）

---

## 核心功能

### 1. AI 聊天助手（侧边栏 Tool Window）
- 右侧栏集成聊天界面，基于 `JBCefBrowser` 渲染 Markdown / 代码高亮 / 工具卡片 / Diff。
- 支持**流式响应**：思考过程（深度思考）与正文分区实时渲染，正文每 500ms 做一次完整 Markdown 重渲染（无闪烁）。
- 支持**多轮对话**，完整保留上下文；支持会话的创建、切换、历史懒加载。
- 支持**停止生成**：点击停止后通过代数机制（`streamGeneration`）丢弃旧回调，避免幽灵写入。
- 支持**图片附件**：可直接粘贴或拖拽图片（有视觉能力的模型直接看图，无视觉能力时转交视觉子智能体）。

### 2. 双协议模型后端
按模型的 `api_format` 自动选择请求链路：

| api_format | 说明 | 入口 |
|------|------|------|
| `openai` | OpenAI 兼容格式（DeepSeek、GLM、通义、本地 vLLM 等均可），支持流式与工具调用 | `DeepSeekBackend` / `AgentBackendManager` |
| `anthropic` | Anthropic 原生 Messages API | `AnthropicBackend` |

两种协议都通过 `AgentBackend` 接口抽象，UI 层无需关心底层协议。另有 `AcpBackend` 用于 ACP Agent 通道。

### 3. 工具调用与文件编辑
模型可调用内置工具，由责任链分发执行：
- **文件工具**：`read_file` / `write_file` / `edit_file` / `create_new_file`，编辑采用三级匹配（精确 → 柔性 → 正则）以容忍 AI 生成的不完美 diff。
- **命令行**：`run_command`，带命令分类器（危险 / 控制台 / 只读）+ 信任名单 + 用户确认。
- **搜索 / 上下文**：`search_project`（基于 `SearchAgent` 的代码语义搜索）、`read_chat_history`、`locate_symbol`、`search_grep`。
- **数据库**：`query_database`，支持 **MySQL / SQLite**，可查表结构（schema 模式）与表数据（data 模式，自动 `LIMIT 10`）；SQL 有四层安全检查（禁分号、仅允许 `SELECT/WITH/SHOW/DESC/EXPLAIN` 开头、危险词过滤、表名白名单）。
- **待办**：`todo` 工具，渲染到任务 Tab（见第 5 点）。
- **文件落盘**：编辑工具经用户确认后真正写入磁盘，并在任务 Tab 展示 Diff。

### 4. 数据源（DataSource）
- 支持配置多个 MySQL / SQLite 数据源，持久化在 SQLite 中。
- 输入框键入 `@` 可弹出数据源选择，插入 `@库<名称>` 语义前缀，让模型明确查哪个库。
- 支持设为默认数据源，模型不指定库时自动回退到默认。

### 5. MCP 工具集成
- 通过 `McpService` 接入 Model Context Protocol，支持 `stdio` / `sse` 等多种传输。
- MCP 工具与内置工具统一出现在工具列表中，由 `ToolExecutor` 末端兜底分发。
- 在 `Settings → Tools → CodePal → MCP` 中配置服务器。

### 6. 任务 / 待办管理
- 模型通过 `todo` 工具创建与更新任务清单，渲染在聊天面板顶部的 **任务 / Diff Tab**（`TaskDiffTabPanel` + `TodoManager`）。
- 任务状态（`pending` / `in_progress` / `completed`）与优先级随对话自动同步，UI 实时刷新。

### 7. 上下文与项目感知
- 自动读取当前编辑器文件 / 选区作为上下文，并以 `@文件名:行号` 引用插入输入框。
- `ContextMemory` 负责上下文裁剪、工具调用配对补齐与系统提示注入。
- 工具卡片上的文件路径可点击直接跳转并选中对应行。

### 8. 会话与配置持久化（以数据库为准）
- 所有消息通过 `DBChatHistoryRepository`（SQLite）持久化，重启 IDE 后历史不丢失。
- **模型配置（含 API Key、上下文上限等）统一存放在 `model_configs` 表**，以数据库为唯一真源：下拉列表与当前模型的配置读取都直接查库，切换模型按 id 落库，不存在内存快照覆盖数据库的问题。
- 支持多会话管理与历史懒加载（滚动到顶部加载更早消息）。
- 数据库文件位置：`<IDE system path>/CPPlugin/db/chat_history.db`。

### 9. 上下文压缩
- 上下文圆环（context circle）显示当前 token 占用（分母取当前模型的 `max_tokens`）；点击或达到阈值时触发压缩。
- 由 `CompressionManager` 执行 AI 摘要压缩，纯 token 预算驱动（占用 ≥80% 才放行）。

### 10. 内联幽灵补全
- 编辑时自动触发内联补全（ghost text），出现后按 `Tab` 接受。
- 由 `CPEditorListener` 监听文档变化、`CPTabHandler` 拦截 Tab 键。

### 11. Skills（技能）
- 通过 `SkillStore` 管理可复用的领域技能文档（首次使用从内置资源拷贝到用户目录，用户可自定义）。
- 模型在对话中通过 `load_skill` 工具按需加载技能全文（如 `source-navigation`），作为提示注入。

---

## 安装

### 方式一：Marketplace 安装
在 IDE 中 `Settings → Plugins → Marketplace` 搜索 **CodePal Assistant**，安装并重启。

### 方式二：本地构建安装（推荐开发用）
```bat
:: Windows：需要 JDK 21，把 JDK 的 bin 加到 PATH；不要设置 JAVA_HOME
set PATH=D:\JDK\jdk-21.0.2\bin;%PATH%
gradlew buildPlugin
```
产物位于 `build/distributions/codepal-<version>.zip`。

在 IDE 中：`Settings → Plugins → ⚙ → Install Plugin from Disk`，选择该 zip，重启后右侧出现 **CodePal** 工具窗口。

### 方式三：从源码运行（沙箱 IDE）
```bat
set PATH=D:\JDK\jdk-21.0.2\bin;%PATH%
gradlew runIde
```
会启动一个加载了本插件的新 IDE 实例。

发布签名包（上传到 Marketplace 用）：
```bat
gradlew signPlugin -Dorg.gradle.java.home=D:\JDK\jdk-21.0.2 --rerun-tasks
```

---

## 配置

安装后打开 `Settings → Tools → CodePal`：

- **General**：模型列表管理（新增 / 编辑 / 删除）、API Key、API Base URL、`api_format`（OpenAI 兼容 / Anthropic）、上下文上限、Temperature、最大输出、内联补全开关与延迟。
- **MCP**：配置 MCP 服务器（stdio / sse）。

模型既可以在设置页配置，也可以通过聊天面板顶部的模型下拉框中的「配置自定义模型」添加。

> 模型配置与 API Key 存放在本地 SQLite 数据库中，不会上传到任何第三方服务器。

---

## 使用指南

### 快捷键
- `Ctrl + Shift + C`：打开 CodePal 聊天窗口
- 内联补全出现时按 `Tab` 接受幽灵文本建议

### 聊天命令
- `@文件名` / `@文件名:行号1-行号2`：引用项目文件内容作为上下文
- `@库数据源名`：指定要查询的数据源，让模型用 `query_database` 查库

### 聊天界面
- 输入问题后 `Enter` 发送，AI 以流式方式实时回复
- 顶部任务 Tab 展示 `todo` 工具产生的待办清单与文件 Diff
- 点击 🗑 清空对话历史；点击 ⚙ 打开设置

---

## 项目结构

```
src/main/java/com/codepal/
├── actions/         快捷入口（打开聊天 CP.OpenChat、反馈报告 CP.ReportIssue）
├── agent/           后端抽象：AgentBackend / DeepSeekBackend(OpenAI 兼容) / AnthropicBackend
│   │                / AcpBackend / AgentBackendManager
│   └── subagent/    SubAgentManager / SearchSubAgent / VisionSubAgent / ErrorValidationAgent
├── api/             API 客户端
├── common/          公共工具
├── compression/     对话压缩管理（CompressionManager / ContextCircleProgress）
├── db/              SQLite 持久化（DBChatHistoryRepository / DBModelConfigRepository
│                     / DataSourceDao / SqliteDatabaseManager）
├── enums/           枚举定义
├── inline/          内联（幽灵文本）补全：CPEditorListener / CPTabHandler / CPInlineManager
├── linter/          代码检查集成
├── mcp/             MCP 客户端 / 配置 / 传输
├── memory/          对话历史与上下文管理（ConversationManager / ContextMemory）
├── model/           数据模型（ChatMessage / ModelConfig 等）
├── session/         会话管理（ChatSessionManager / IterationGuard / SessionContext）
├── settings/        设置服务与页面（CPSettings / General / MCP）
├── skills/          SkillStore 技能管理
├── tools/           工具定义与执行：ToolDefinitions / ToolExecutor / ToolOrchestrator
│                      / TodoManager / SearchAgent / CommandRunner / FileOperationService …
├── toolwindow/      聊天面板（ChatPanel）与工具窗口工厂（CPToolWindowFactory）
├── ui/              UI 组件（下拉框、对话框、WebView 等）
└── utils/           工具类
```

### 核心调用链路（概述）
```
用户输入 → ChatPanel.sendMessage()
        → 按当前模型 api_format 路由后端（OpenAI 兼容 / Anthropic）
        → 上下文准备 (ContextMemory) + 工具收集 (ToolDefinitions / McpService / DataSource)
        → 流式回调：onReasoning / onMessage / onToolCalls / onComplete
        → 工具责任链分发 → 文件落盘 / 命令执行 / 数据库查询 / 待办更新
        → ChatWebView 渲染 → DBChatHistoryRepository 持久化
```

---

## 技术栈

- **语言**：Java（构建工具链使用 JDK 21；`since-build` 覆盖 2023.2+）
- **构建**：Gradle + `org.jetbrains.intellij` 插件（`buildPlugin` / `runIde` / `signPlugin`）
- **平台**：IntelliJ Platform SDK 2023.2（`since-build="232"`，未设 `until-build`，兼容此后所有版本）
- **UI**：Swing + JBCef（`JBCefBrowser`），Markdown 经 commonmark 渲染
- **HTTP**：OkHttp + OkHttp-SSE（流式）
- **JSON**：Gson
- **持久化**：SQLite（sqlite-jdbc），数据库查询用 mysql-connector-j
- **协议**：OpenAI 兼容 API、Anthropic Messages API、MCP

---

## 兼容性

- IntelliJ IDEA 2023.2 及更高版本（Community / Ultimate）
- Android Studio **Iguana（2023.2.1，build 232）及更高版本**
- ⚠️ 不支持 WebStorm / PyCharm / GoLand / RubyMine 等**不含 Java 模块**的 IDE——本插件依赖 `com.intellij.java` 的 Java PSI（项目结构分析、文件读取等），这些 IDE 未提供该模块，无法安装。

---

## 注意事项

1. **API Key 安全**：仅存于本地 SQLite 数据库，不上传。
2. **文件读取限制**：项目文件分析自动跳过 `node_modules` / `target` / `build` / `.git` 等目录；单文件与文件数量有上限。
3. **网络要求**：使用需能访问所配置模型的 API 服务。
4. **内联补全延迟**：AI 补全依赖网络请求，首次触发可能有延迟。
5. **文件修改需确认**：涉及写盘的工具调用会经用户确认后才落盘。
6. **数据库查询为只读**：`query_database` 仅允许查询语句，写入类 SQL 会被安全策略拦截。

---

## License

MIT
