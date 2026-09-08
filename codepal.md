# CodePal 项目规则

本项目由 CodePal（原 CP）IntelliJ 插件使用，下列约定在每次对话中**必须严格遵守**：

## 构建与运行
- 命令行 `gradlew` 无法在本机启动（JDK 25 与 Gradle 8.4 + toolchain 17 不兼容）。需要编译验证时，请在 **IDE 内使用 JDK 17/21** 构建。
- 插件依赖 IntelliJ SDK 与 gson，命令行 `javac` 单独编译 `ChatWebView`/`FileReaderUtil` 等会报"找不到符号 com.intellij.*"，属正常（仅 IDE classpath 可编），以 IDE 构建为准。
- `ChatHtmlTemplate` 仅依赖 JDK 标准库，可用 `javac` 单独验证语法。

## Bug 处理铁律
- **遇到 bug，不要怀疑编译的是旧代码，一定是你写的代码有 bug！** 先查自己最近改的逻辑，不要甩锅给"没重新编译"。

## 代码位置与约定
- 包名仍为 `com.codepal`，类名仍为 `CP*`（如 `CPSettings`）；用户可见显示名已改为 `CodePal`，但目录/文件名未改名。
- 聊天 UI：`ChatPanel` + `ChatWebView`(JBCefBrowser) + `ChatHtmlTemplate`(手写 JS 模板)。
- 第三方类源码查看：`view_class_source` 优先用全限定类名；多模块项目类在另一模块时已由代码兜底（扫项目目录树），无需模型手动找 jar。失败时可传绝对路径（整 jar / jar!/类 / 散 class 三种形式）。

## JBCefJSQuery 规范（插件内 WebView 桥接）
- `JBCefJSQuery` 只能可靠用于「Java 触发副作用」（复制、打开文件、`browser.executeJavaScript`），**不能**用它把数据返回给页面调用处；要把数据送回页面，必须在 handler 内 `browser.executeJavaScript(...)` 主动调用页面 resolver。
