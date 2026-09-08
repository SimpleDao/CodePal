# 查看第三方依赖 / Jar 源码指南

当你需要阅读项目引入的某个第三方库（jar / npm 包）的源码时，**不要盲目全局搜索项目目录**——第三方依赖根本不在项目源码里。请按下面的标准流程操作，先确定项目类型，再定位依赖的物理位置，必要时反编译。

## 通用原则

1. **首选 `view_class_source` 工具（Java，零手动操作）**：读 Java 第三方库的类源码，先调 `view_class_source`。**只传一个 `target` 参数即可，没有 `class_name` 之类额外参数**。`target` 按场景选下面三种之一（关键区别见末尾说明）：

   - **形式一：全限定类名**（最常用，推荐优先用）
     例：`com.google.gson.Gson`
     → 适用于**已经在项目 classpath 上的第三方 jar**（pom/Gradle 引入、IDEA 已索引的依赖）。工具用 `JavaPsiFacade` 在 `allScope`（含全部库）里直接定位这个类，**不需要你提供 jar 路径**，也不用知道它在哪个 jar。优先读 `-sources.jar` 源码，没有就自动调 Fernflower 反编译这一（及内部）类。
     ⚠️ **外来 jar / pom 引入的 jar 一律用这种写法**，不要让模型去拼 jar 路径。

   - **形式二：jar 内某个类的路径**（jar 不在 classpath 上时用）
     例：`D:/libs/gson-2.10.1.jar!/com/google/gson/Gson.class`
     → 适用于**磁盘上某 jar 但没引入项目、FQN 搜不到**的情况。用 `jar绝对路径!/类.class` 直接定向反编译**单个类**，避免整包开销。

   - **形式三：整个 `.jar` 绝对路径**（整包反编译）
     例：`D:/repo/x/gson-2.10.1.jar`
     → 同样适用于**不在 classpath 上的 jar**，但需要看里面很多类、且想一次性全反编译时。会用 IDEA 内置 Fernflower 把 jar 内**所有** `.class` 批量反编译到 `temp/sources` 并返回文件清单。

   **`target` 三种形式的关系**：
   - 形式一 与 形式二/三 **不是二选一，而是按 jar 是否已在 classpath 区分**：在 classpath 上 → 用形式一（FQN，最省事）；不在 classpath 上 → 用形式二（定向单类）或形式三（整包）。
   - 形式二、形式三 都是「jar 不在 classpath」时的补充手段，**日常 90% 情况用形式一就够**，不要一上来就整包反编译。

   它会**自动定位**依赖类，**写入临时目录（默认 `temp/sources`）并返回文件路径列表**。随后你用 `read_file_range` **按需读取片段**（类通常很大，不要一次全读）。比 `javap` 完整（javap 只有签名/字节码）。无源码/整 jar 的反编译步骤会弹一次确认（可勾选"本次不再询问"）。项目内源码会直接返回原文件路径，用 `read_file_range` 读即可。
2. **先确定依赖坐标**：从构建文件读取你想看的库的坐标（groupId:artifactId:version 或 npm 包名）。
3. **优先找现成源码**：
   - Java：优先 `view_class_source`（内部已优先读 `-sources.jar`）；技能后续手动流程仅作该工具不可用时的兜底。
   - 前端：直接读 `node_modules/<pkg>/` 下的源码，前端一般自带源码（`.js`/`.ts`），无需反编译。
4. **无源码才反编译**：`view_class_source` 已自动处理；只有该工具失败（如反编译失败）才走下方手动反编译兜底。
5. **产物落点约定（仅手动反编译兜底时使用）**：反编译/解压生成的 `.java` 一律输出到项目根目录下的 `temp/` 目录（如 `temp/gson/`）。
   - **目录创建必须用 `create_directory` 工具**，不要用 `run_command` 跑 `mkdir`：本插件已明确禁止用命令创建目录，且 Windows `cmd` 不支持 `mkdir -p`（会创建一个名为 `-p` 的垃圾目录）。
   - 解压/反编译用 `run_command` 执行，并通过它的 **`cwd` 参数**把工作目录设为刚创建的 `temp/<artifactId>`，**不要用 `cd`**（每次 `run_command` 都是全新 shell，`cd` 不会保留）。
   - 产物就绪后用 `read_file_range` / `search_grep` 读取。**禁止把产物写到源码目录或依赖缓存目录。**
6. **读源码用已有工具**：`view_class_source`（看依赖类源码）、`list_files`（看目录）、`read_file_range`（看具体文件）、`search_grep`（搜关键词）。

---

## 零、快速 API 速览（javap，零文件、秒级）

如果你只是想**快速确认"这个类有哪些 public/private 方法、字段、构造器"**（不需要看实现），直接用 `javap`，无需解压/反编译/落盘任何文件：

```bash
# 看全部成员签名（含 private），最快
javap -p -cp "<jar完整路径>" <全限定类名>

# 还想看字节码（知道它大概怎么做的，但仍是字节码不是源码）
javap -p -c -cp "<jar完整路径>" <全限定类名>
```

要点：
- `-cp` 直接指向 jar 即可，不必先解压。
- 全限定类名用点分隔，例如 `com.google.gson.Gson`。
- 本插件已把 `javap` 列为只读命令，**静默执行、无需确认**；加 `> nul`（Windows）/ `> /dev/null`（Linux/macOS）丢弃输出也不拦。
- ⚠️ `javap` 给的是**签名/字节码，不是源码**，看不到方法体实现。要看实现，继续下面的反编译流程。

> 仅当速览不满足、需要看**真实 .java 实现**时，才走下面"反编译"一节（或先找 `-sources.jar`）。

---

## 一、Gradle 项目（Java）

1. 从 `build.gradle` 或 `build.gradle.kts` 的 `dependencies { }` 块读取目标依赖坐标，例如本项目的：

   ```
   implementation("com.google.code.gson:gson:2.10.1")
   implementation("com.squareup.okhttp3:okhttp:4.12.0")
   implementation("org.xerial:sqlite-jdbc:3.45.3.0")
   ```

2. **确定 Gradle 缓存根目录（关键，可能被自定义）**：默认是 `~/.gradle`（即 `GRADLE_USER_HOME` 环境变量）。先确认是否被覆盖：
   - Windows：`echo %GRADLE_USER_HOME%`
   - Linux/macOS：`echo $GRADLE_USER_HOME`
   若输出非空，缓存根就是该路径；否则用默认 `~/.gradle`。后续所有 `~/.gradle` 都替换为实际根。

3. 定位 Gradle 缓存中的 jar（路径中的 group 把 `.` 换成 `/`）：

   ```
   <gradle根>/caches/modules-2/files-2.1/<group路径>/<artifactId>/<version>/<hash>/<artifactId>-<version>.jar
   ```

   例如 gson：
   - jar：`~/.gradle/caches/modules-2/files-2.1/com/google/code/gson/gson/2.10.1/<hash>/gson-2.10.1.jar`
   - 源码（若存在）：`gson-2.10.1-sources.jar`（同目录下）

4. 用 `run_command` 查找（cwd 留空，默认项目根，把 `<gradle根>` 换成第 2 步得到的真实路径）：
   ```bash
   dir /s /b "<gradle根>\caches\modules-2\files-2.1\com\google\code\gson\gson\2.10.1\*.jar"
   ```
   在 Linux/macOS 上：
   ```bash
   find "<gradle根>/caches/modules-2" -path "*gson/2.10.1*" -name "*.jar"
   ```

5. 若找到 `-sources.jar`：
   a. 用 `create_directory` 工具创建目录 `temp/gson`（路径相对/绝对项目根均可）。
   b. 用 `run_command` 解压：命令填 `jar xf "<sources.jar的完整路径>"`，并把 `cwd` 参数设为 `temp/gson`
      （Windows / Linux / macOS 通用，无需 `cd`）。
   c. 然后用 `read_file_range` 读 `temp/gson/...` 下的 `.java`。
   示例（工具调用，非 bash）：
   - `create_directory` → `temp/gson`
   - `run_command` → command=`jar xf "C:/Users/me/.gradle/.../gson-2.10.1-sources.jar"`，cwd=`temp/gson`
5. 若只有普通 jar（无 `-sources.jar`）：按下方「反编译」处理。

---

## 二、Maven 项目（Java）

1. 从 `pom.xml` 的 `<dependencies>` 读取坐标。
2. **确定本地仓库实际路径（关键，可能被自定义）**：Maven 本地仓库默认是 `~/.m2/repository`，但用户常在 `~/.m2/settings.xml` 用 `<localRepository>` 自定义，或通过 `-Dmaven.repo.local=` 覆盖。**不要直接假设 `~/.m2/repository`**，先用下面任一方式拿到真实路径：
   - 最可靠（在项目根执行，cwd 留空）：
     ```bash
     mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout
     ```
     输出即为真实本地仓库的绝对路径。
   - 备选：查配置文件
     ```bash
     # 用户级（优先级高于全局）
     type "%USERPROFILE%\.m2\settings.xml" | findstr localRepository
     # 全局级
     type "%MAVEN_HOME%\conf\settings.xml" | findstr localRepository
     ```
   - 若上面都查不到 `<localRepository>`，才回退到默认 `~/.m2/repository`。
3. 用真实仓库路径定位 jar（路径 `group` 用 `/` 分隔）：
   ```
   <本地仓库>/<group路径>/<artifactId>/<version>/<artifactId>-<version>.jar
   ```
   例（默认仓库）：`~/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar`，源码为同目录 `gson-2.10.1-sources.jar`。
4. 查找命令（把 `<本地仓库>` 换成第 2 步得到的真实路径）：
   ```bash
   dir /s /b "<本地仓库>\com\google\code\gson\gson\2.10.1\*.jar"
   ```
   Linux/macOS：
   ```bash
   find "<本地仓库>/com/google/code/gson/gson/2.10.1" -name "*.jar"
   ```
5. 有 `-sources.jar`：用 `create_directory` 创建 `temp/<artifactId>`，再用 `run_command`（command=`jar xf "<sources.jar路径>"`，cwd=`temp/<artifactId>`）解压读取；无 `-sources.jar` 则按下方「反编译」处理，产物同样落 `temp/`。

---

## 三、Vue / 前端项目（Node）

1. 从 `package.json` 的 `dependencies` / `devDependencies` 读取包名与版本。
2. 前端依赖自带源码，直接读取 `node_modules/<pkg>/` 即可，无需反编译：
   - 入口通常在 `node_modules/<pkg>/package.json` 的 `main` / `module` / `exports` 字段指向的文件。
   - 读 `node_modules/<pkg>/dist/`、`src/` 或 `lib/` 下的 `.js`/`.ts`。
3. 查找命令：
   ```bash
   dir /s /b node_modules\vue\*.d.ts
   ```
4. 极少数情况需要看编译产物（`.wasm`、压缩后的 min.js）才考虑反编译/解压，一般直接读源码即可。

---

## 反编译（仅 Java 无 `-sources.jar`，且 javap 速览不够看实现时使用）

> 如果只是想看"类有哪些方法/字段"，用上方「零、快速 API 速览（javap）」即可，零文件、秒级，无需反编译。

把 jar 反编译成可读 `.java`，产物输出到 `<项目根>/temp/<artifactId>/`。下面两种工具任选其一。

> 目录创建请用 `create_directory` 工具建好 `temp/<artifactId>`；`run_command` 用 `cwd` 参数指向该目录，不要 `mkdir`/`cd`（原因见上方通用原则 4）。

### 方案 A：CFR（推荐，单 jar、命令简单）
1. 先确认本机是否有 cfr.jar；没有则提示用户下载或用其他方案。
2. 用 `create_directory` 创建 `temp/<artifactId>`，再用 `run_command` 执行（cwd 设为该目录）：
   ```bash
   java -jar <cfr.jar路径> "<jar完整路径>" --outputdir temp/<artifactId>
   ```
   例：`java -jar c:/tools/cfr.jar "C:/Users/me/.gradle/.../gson-2.10.1.jar" --outputdir temp/gson`
   （也可直接用 `cwd`=`temp/gson` 并省略 `--outputdir`，让 cfr 输出到 cwd。）

### 方案 B：IDEA 自带 Fernflower
IDEA 自带反编译器，jar 位于 IDE 安装目录的插件下，**路径随版本变化，先定位再调用**：
```bash
dir /s /b "%LOCALAPPDATA%\JetBrains\IntelliJIdea*\plugins\java-decompiler\lib\java-decompiler.jar"
```
用 `create_directory` 建好 `temp/<artifactId>`，再用 `run_command`（cwd 设为该目录）调用（注意 `java-decompiler.jar` 同时作为 classpath 和入口）：
```bash
java -cp "<java-decompiler.jar完整路径>" org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler "<jar完整路径>" "temp/<artifactId>"
```
反编译结果是一个 zip（需二次解压），解压到 `temp/<artifactId>` 后读取。

---

## 完成后

反编译/解压后，用 `read_file_range` 或 `search_grep` 读取 `temp/<artifactId>/` 下的 `.java` 即可查看第三方源码。阅读完毕无需保留 `temp/` 产物（属临时文件）。
