package com.codepal.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 命令分类器 —— 根据命令内容判断执行方式
 *
 * 三级分类：
 *   READ_ONLY   - 只读查询，静默执行，无需确认
 *   CONSOLE     - 有影响但安全，IDEA控制台执行，无需确认
 *   DANGEROUS   - 高风险操作，IDEA控制台执行 + 用户确认
 */
public class CommandClassifier {

    public enum Category {
        READ_ONLY,
        CONSOLE,
        DANGEROUS
    }

    // ═══════════════ 只读命令白名单（精确匹配前缀） ═══════════════

    private static final Set<String> READ_ONLY_COMMANDS = new HashSet<>(Arrays.asList(
            // 文件查询
            "ls", "dir", "find", "pwd", "cd", "tree", "stat", "file", "wc",
            // Windows / PowerShell 查询命令（只读，静默放行）
            "findstr", "select-string",
            // 位置/地址查询（只读，静默放行）
            "where", "which", "locate",
            // 文件查看
            "cat", "head", "tail", "more", "less", "grep", "egrep", "fgrep",
            // Windows cmd 内建 type（等同 cat，只读打印文件内容）
            "type",
            // 系统信息
            "echo", "whoami", "hostname", "uname", "date", "time", "uptime",
            "ps", "top", "free", "df", "du", "id", "groups", "env", "printenv",
            // 版本查询
            "java -version", "javac -version", "node -v", "node --version",
            "npm -v", "npm --version", "python --version", "python3 --version",
            "mvn -v", "mvn --version", "gradle -v", "gradle --version",
            "git --version", "docker --version", "kubectl version --client",
            // 字节码反汇编（读取类结构，不改任何文件，静默放行）
            "javap",
            // 网络查询
            "ping", "curl -I", "curl --head", "wget --spider",
            "ipconfig", "ifconfig", "netstat", "nslookup", "dig", "host"
    ));

    // Git 只读子命令
    private static final Set<String> GIT_READ_ONLY = new HashSet<>(Arrays.asList(
            "status", "log", "diff", "show", "branch", "tag", "blame",
            "remote -v", "remote --verbose", "fetch --dry-run", "pull --dry-run"
    ));

    // ═══════════════ 高风险模式（正则匹配） ═══════════════

    private static final Pattern[] DANGEROUS_PATTERNS = {
            // 删除操作
            Pattern.compile("\\brm\\s+(-rf?|-Rf?|--recursive|--force)\\b", Pattern.CASE_INSENSITIVE),
            // find 的破坏性动作：带 -delete/-exec/-execdir/-ok 的不是纯查询（会删除或执行命令）
            Pattern.compile("\\bfind\\b[^|]*\\s-(delete|exec|execdir|ok)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdel\\s+/[fFsSqQ]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\s+/[sSqQ]", Pattern.CASE_INSENSITIVE),
            // 系统控制
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE),
            // 权限提升
            Pattern.compile("^\\s*sudo\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brunas\\s+", Pattern.CASE_INSENSITIVE),
            // 磁盘操作
            Pattern.compile("\\b(mkfs|dd\\s+if=|fdisk|diskpart)\\b", Pattern.CASE_INSENSITIVE),
            // 注册表
            Pattern.compile("\\breg\\s+(add|delete|import|export)\\b", Pattern.CASE_INSENSITIVE),
            // 用户管理
            Pattern.compile("\\bnet\\s+(user|localgroup\\s+administrators)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\buser(add|mod|del)\\b", Pattern.CASE_INSENSITIVE),
            // Git 高风险
            Pattern.compile("\\bgit\\s+push\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+clean\\s+(-f|--force|-fd|-df)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+rebase\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgit\\s+cherry-pick\\b", Pattern.CASE_INSENSITIVE),
            // 重定向写文件（排除"丢弃输出"：> nul / > /dev/null / 2> nul / 2> /dev/null 不是写文件，安全放行）
            Pattern.compile("\\s>(?!\\s*(nul|/dev/null)\\b)\\s*[^>]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s>>\\s", Pattern.CASE_INSENSITIVE),
            // 管道到高危命令
            Pattern.compile("\\|\\s*(rm|sh|bash|zsh|ksh|csh|tcsh|cmd|powershell)\\b", Pattern.CASE_INSENSITIVE),
            // Fork bomb
            Pattern.compile(":\\(\\)\\{\\s*:\\s*\\|:\\s*&\\s*\\};:\\s*$", Pattern.CASE_INSENSITIVE),
            // 包管理器全局安装（可能改系统）
            Pattern.compile("\\bnpm\\s+install\\s+(-g|--global)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpip\\s+install\\s+(--user\\s+)?(-U|--upgrade)?\\s*$", Pattern.CASE_INSENSITIVE),
            // 下载并执行
            Pattern.compile("(curl|wget)\\s+.*\\|\\s*(sh|bash|zsh|python|perl|ruby)", Pattern.CASE_INSENSITIVE),
    };

    // ═══════════════ 分类主逻辑 ═══════════════

    public static Category classify(String command) {
        if (command == null || command.isBlank()) {
            return Category.READ_ONLY;
        }

        String trimmed = command.trim();
        String lower = trimmed.toLowerCase();

        // 1. 先检查高风险模式
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                return Category.DANGEROUS;
            }
        }

        // 2. 检查 Git 子命令
        if (lower.startsWith("git ")) {
            return classifyGit(trimmed);
        }

        // 3. 检查只读命令白名单（前缀匹配）
        for (String roCmd : READ_ONLY_COMMANDS) {
            if (lower.startsWith(roCmd + " ") || lower.equals(roCmd)) {
                // 特殊：grep 如果有重定向就是危险
                return Category.READ_ONLY;
            }
        }

        // 4. 特殊：带管道的命令，只要管道后不是只读命令，归为 console
        if (trimmed.contains("|")) {
            return Category.CONSOLE;
        }

        // 5. 特殊：mvn/npm/gradle 构建类命令归为 console
        if (lower.startsWith("mvn ") || lower.startsWith("mvnw ")
                || lower.startsWith("gradle ") || lower.startsWith("gradlew ")
                || lower.startsWith("npm run ") || lower.startsWith("npm test ")
                || lower.startsWith("yarn ") || lower.startsWith("pnpm ")
                || lower.startsWith("python ") || lower.startsWith("python3 ")
                || lower.startsWith("java ") || lower.startsWith("javac ")
                || lower.startsWith("make ") || lower.startsWith("cmake ")
                || lower.startsWith("docker ") || lower.startsWith("kubectl ")
                || lower.startsWith("terraform ") || lower.startsWith("ansible ")) {
            return Category.CONSOLE;
        }

        // 6. 默认：控制台执行（宁可保守一点，也不要静默执行有风险的命令）
        return Category.CONSOLE;
    }

    /**
     * 分类 Git 命令
     */
    private static Category classifyGit(String command) {
        String lower = command.toLowerCase();
        // 去掉 "git " 前缀
        String rest = lower.substring(4).trim();

        for (String ro : GIT_READ_ONLY) {
            if (rest.startsWith(ro + " ") || rest.equals(ro)) {
                return Category.READ_ONLY;
            }
        }

        // 高风险在 DANGEROUS_PATTERNS 中已判断，这里只处理普通修改类
        return Category.CONSOLE;
    }

    /**
     * 仅用于调试：返回分类描述
     */
    public static String describe(Category cat) {
        return switch (cat) {
            case READ_ONLY -> "只读查询（静默执行）";
            case CONSOLE -> "控制台执行（需用户可见）";
            case DANGEROUS -> "高风险（需用户确认 + 控制台）";
        };
    }
}
