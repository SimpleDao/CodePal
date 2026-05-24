# CodeBuddy - IntelliJ IDEA AI 编程助手插件

CodeBuddy 是一个基于 DeepSeek 大模型的 IntelliJ IDEA 插件，提供智能代码补全、AI 聊天助手和项目文件分析功能。

## 功能特性

### 1. 智能代码自动补全
- 基于 DeepSeek AI 模型的实时代码补全
- 支持 Java、Kotlin、Python、JavaScript、TypeScript、Go、Rust 等多种语言
- 根据光标前后代码上下文智能预测
- 可在设置中开启/关闭

### 2. AI 聊天助手（侧边栏 Tool Window）
- 右侧边栏集成聊天界面
- 支持流式响应，实时显示 AI 回复
- 支持多轮对话，保留上下文
- 支持模型切换（deepseek-chat / deepseek-reasoner / deepseek-coder）

### 3. 项目文件读取与分析
- 自动读取当前项目中的代码文件作为上下文
- 输入 `/file` 命令让 AI 分析整个项目结构
- 使用 `@文件名` 引用特定文件内容
- 自动获取当前编辑器中的文件内容作为上下文

### 4. 代码生成功能
- 选中代码后右键选择 "CodeBuddy 生成代码"
- 根据描述自动生成代码片段
- 支持替换选中内容或在光标处插入

## 安装方法

### 方式一：本地安装（推荐）
1. 克隆或下载本项目
2. 使用 IntelliJ IDEA 打开项目
3. 运行 Gradle 任务：`gradle buildPlugin`
4. 在 IDEA 中：`Settings -> Plugins -> Install from Disk`
5. 选择 `build/distributions/CodeBuddy-1.0.0.zip`

### 方式二：从源码运行
1. 使用 IntelliJ IDEA 打开本项目
2. 配置 Gradle 和 JDK 17+
3. 运行 Gradle 任务：`runIde`
4. 将启动一个新的 IDE 实例并加载插件

## 配置说明

### 首次使用配置
1. 安装插件后，打开 `Settings -> CodeBuddy`
2. 输入你的 DeepSeek API Key（从 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取）
3. 可选：修改 API Base URL、选择模型、调整参数

### 配置项
| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| API Key | DeepSeek API 密钥 | (空) |
| API Base URL | API 服务地址 | https://api.deepseek.com |
| 模型 | 使用的 AI 模型 | deepseek-chat |
| 启用自动补全 | 是否开启 AI 代码补全 | true |
| 最大 Token 数 | 单次请求最大生成 Token | 4096 |
| Temperature | 生成随机性（0-2） | 0.7 |

## 使用指南

### 快捷键
- `Ctrl + Shift + C`：打开 CodeBuddy 聊天窗口
- `Ctrl + Shift + G`：使用 AI 生成代码

### 聊天命令
- `/file` 或 `/files`：读取当前项目所有代码文件并让 AI 分析
- `@文件名`：引用特定文件内容作为上下文（例如：`@UserService.java 这个类有什么问题？`）

### 聊天界面
- 输入问题后按 `Enter` 发送
- AI 会以流式方式回复，实时显示生成内容
- 底部可切换 AI 模型
- 点击 🗑 清空对话历史
- 点击 ⚙ 打开设置页面

## 项目结构

```
codebuddy/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts           # Gradle 设置
├── src/
│   └── main/
│       ├── java/com/codebuddy/
│       │   ├── api/
│       │   │   ├── DeepSeekClient.java          # DeepSeek API 客户端
│       │   │   └── model/                       # API 请求/响应模型
│       │   ├── completion/
│       │   │   ├── CodeBuddyCompletionContributor.java   # 代码补全入口
│       │   │   └── CodeBuddyCompletionProvider.java      # 补全逻辑
│       │   ├── settings/
│       │   │   ├── CodeBuddySettings.java       # 设置持久化
│       │   │   └── CodeBuddySettingsConfigurable.java    # 设置 UI
│       │   ├── toolwindow/
│       │   │   ├── CodeBuddyToolWindowFactory.java       # 工具窗口工厂
│       │   │   └── ChatPanel.java               # 聊天面板 UI
│       │   ├── actions/
│       │   │   ├── OpenChatAction.java          # 打开聊天 Action
│       │   │   └── GenerateCodeAction.java      # 生成代码 Action
│       │   └── utils/
│       │       └── FileReaderUtil.java          # 项目文件读取工具
│       └── resources/
│           ├── META-INF/
│           │   └── plugin.xml     # 插件配置文件
│           └── icons/
│               └── codebuddy.svg  # 插件图标
```

## 技术栈

- **语言**：Java 17
- **构建工具**：Gradle + IntelliJ Platform Gradle Plugin
- **IDE 平台**：IntelliJ Platform SDK 2023.2.5+
- **HTTP 客户端**：OkHttp + OkHttp-SSE（流式响应）
- **JSON 序列化**：Gson
- **AI 模型**：DeepSeek API（兼容 OpenAI API 格式）

## 兼容性

- IntelliJ IDEA 2023.2 及更高版本
- 支持所有基于 IntelliJ 平台的 IDE（WebStorm、PyCharm、GoLand 等）

## 注意事项

1. **API Key 安全**：API Key 存储在 IDE 的持久化配置中，不会上传到任何服务器
2. **文件读取限制**：项目文件分析时，自动跳过 `node_modules`、`target`、`build`、`.git` 等目录，单个文件最大读取 100KB，最多读取 20 个文件
3. **网络要求**：使用插件需要能访问 DeepSeek API 服务
4. **代码补全延迟**：AI 补全需要网络请求，首次触发可能有 1-3 秒延迟

## License

MIT License
