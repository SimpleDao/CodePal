package com.codepal.tools;

import com.intellij.openapi.project.Project;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 命令执行器 —— 封装 shell 命令的执行、超时控制、输出截断、安全过滤
 *
 * 职责单一：只负责"安全地运行一条命令并返回结果"，不关心是谁调用、结果如何展示
 *
 * @author 水龙吟
 * @date 2026-06-28
 */
public class CommandRunner {

    /** 单次命令最大输出字符数（防止输出过大撑爆 LLM 上下文） */
    private static final int MAX_OUTPUT_LENGTH = 30000;

    /** 默认超时秒数 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 最大允许超时秒数 */
    private static final int MAX_TIMEOUT_SECONDS = 120;

    /** 高危命令黑名单（大小写不敏感匹配），拒绝执行 */
    private static final Set<String> DANGEROUS_COMMANDS = new HashSet<>(Arrays.asList(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=",
            "format c:", "format c:\\", "del /f /s /q c:\\",
            ":(){:|:&};:", "fork bomb",
            "shutdown", "reboot", "halt", "poweroff",
            "reg delete", "reg add",
            "net user", "net localgroup administrators"
    ));

    /**
     * 命令执行结果
     */
    public static class Result {
        public final boolean success;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long durationMs;
        public final boolean timedOut;
        public final boolean userCancelled;
        public final String error;

        private Result(boolean success, int exitCode, String stdout, String stderr,
                       long durationMs, boolean timedOut, boolean userCancelled, String error) {
            this.success = success;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.durationMs = durationMs;
            this.timedOut = timedOut;
            this.userCancelled = userCancelled;
            this.error = error;
        }

        static Result ok(int exitCode, String stdout, String stderr, long durationMs) {
            return new Result(exitCode == 0, exitCode, truncate(stdout), truncate(stderr),
                    durationMs, false, false, null);
        }

        static Result timeout(String stdout, String stderr, long durationMs) {
            return new Result(false, -1, truncate(stdout), truncate(stderr),
                    durationMs, true, false, "命令执行超时（" + durationMs + "ms），已强制终止");
        }

        static Result cancelled(String stdout, String stderr, long durationMs) {
            return new Result(false, -1, truncate(stdout), truncate(stderr),
                    durationMs, false, true, "用户已终止命令执行");
        }

        static Result error(String msg) {
            return new Result(false, -1, "", "", 0, false, false, msg);
        }

        public String formatOutput() {
            StringBuilder sb = new StringBuilder();
            if (error != null) {
                sb.append("❌ 执行失败: ").append(error).append("\n");
                return sb.toString();
            }
            sb.append("Exit code: ").append(exitCode);
            if (timedOut) sb.append(" (超时)");
            if (userCancelled) sb.append(" (用户终止)");
            sb.append(" | 耗时: ").append(durationMs).append("ms\n");

            if (stdout != null && !stdout.isEmpty()) {
                sb.append("\n--- stdout ---\n").append(stdout);
                if (stdout.length() >= MAX_OUTPUT_LENGTH) {
                    sb.append("\n...(stdout 已截断，输出过长)");
                }
            }
            if (stderr != null && !stderr.isEmpty()) {
                sb.append("\n--- stderr ---\n").append(stderr);
                if (stderr.length() >= MAX_OUTPUT_LENGTH) {
                    sb.append("\n...(stderr 已截断，输出过长)");
                }
            }
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            return sb.toString();
        }
    }

    /**
     * 执行命令
     *
     * @param command  要执行的命令字符串
     * @param project  当前项目（用于确定工作目录）
     * @param cwd      工作目录（可为null，默认项目根目录）
     * @param timeoutSec 超时秒数（0或负数使用默认值）
     * @return 执行结果
     */
    public static Result execute(String command, Project project, String cwd, int timeoutSec) {
        if (command == null || command.isBlank()) {
            return Result.error("命令为空");
        }

        String trimmedCmd = command.trim();

        String danger = checkDangerous(trimmedCmd);
        if (danger != null) {
            return Result.error("安全拦截: " + danger);
        }

        File workDir = resolveWorkDir(cwd, project);
        if (workDir == null || !workDir.exists() || !workDir.isDirectory()) {
            return Result.error("工作目录不存在: " + (cwd != null ? cwd : "项目根目录"));
        }

        int timeout = (timeoutSec > 0) ? Math.min(timeoutSec, MAX_TIMEOUT_SECONDS) : DEFAULT_TIMEOUT_SECONDS;

        return runProcess(trimmedCmd, workDir, timeout);
    }

    private static Result runProcess(String command, File workDir, int timeoutSec) {
        ProcessBuilder pb;
        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("win");

        if (isWindows) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
            // ★ Windows 中文乱码修复：cmd.exe 默认代码页 936(GBK)，子进程输出是 GBK 字节；
            //   而 StreamReader 用 UTF-8 解码 → 中文变乱码。两种方案：
            //   a) chcp 65001 切 UTF-8：部分命令（如 findstr）在 65001 下会异常/丢输出，风险高；
            //   b) 按 JDK 推断的系统默认编码解码（Windows 简体中文环境即 GBK），与 cmd 实际输出一致。
            //   选 b）：用 native.encoding（系统 ANSI 代码页）解码，与 cmd 实际输出一致。
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.directory(workDir);
        pb.redirectErrorStream(false);

        Charset outputCharset;
        if (isWindows) {
            // Windows：cmd.exe 输出编码跟随系统 ANSI 代码页（简体中文=GBK）。
            // JDK18+ 里 file.encoding 恒为 UTF-8（JEP 400），不能反映控制台代码页，
            // 故用 native.encoding（JDK17+ 提供，指向系统 ANSI 代码页）；取不到时回退 GBK。
            String nativeEnc = System.getProperty("native.encoding");
            if (nativeEnc == null || nativeEnc.isBlank()) nativeEnc = "GBK";
            outputCharset = safeCharset(nativeEnc, Charset.forName("GBK"));
        } else {
            // Linux/Mac：终端普遍 UTF-8
            outputCharset = StandardCharsets.UTF_8;
        }

        long startMs = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return Result.error("无法启动进程: " + e.getMessage());
        }

                ExecutorService executor = Executors.newFixedThreadPool(2);
                 Future<String> stdoutFuture = executor.submit(new StreamReader(process.getInputStream(), outputCharset));
                 Future<String> stderrFuture = executor.submit(new StreamReader(process.getErrorStream(), outputCharset));

        try {
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startMs;

            if (!finished) {
                process.destroyForcibly();
                String stdout = getFutureResult(stdoutFuture, 2, TimeUnit.SECONDS);
                String stderr = getFutureResult(stderrFuture, 2, TimeUnit.SECONDS);
                executor.shutdownNow();
                return Result.timeout(stdout, stderr, duration);
            }

            int exitCode = process.exitValue();
            String stdout = getFutureResult(stdoutFuture, 5, TimeUnit.SECONDS);
            String stderr = getFutureResult(stderrFuture, 5, TimeUnit.SECONDS);
            executor.shutdown();
            return Result.ok(exitCode, stdout, stderr, duration);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            executor.shutdownNow();
            long duration = System.currentTimeMillis() - startMs;
            return Result.error("命令执行被中断");
        } catch (Exception e) {
            process.destroyForcibly();
            executor.shutdownNow();
            return Result.error("执行异常: " + e.getMessage());
        }
    }

    /** 安全获取字符集：名称非法或平台不支持时返回 fallback */
     private static Charset safeCharset(String name, Charset fallback) {
         try {
             return Charset.forName(name);
         } catch (Exception e) {
             return fallback;
         }
     }

     private static String getFutureResult(Future<String> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 后台读取流内容
     */
    private static class StreamReader implements Callable<String> {
        private final InputStream inputStream;
        private final Charset charset;

        StreamReader(InputStream inputStream, Charset charset) {
            this.inputStream = inputStream;
            this.charset = charset;
        }

        @Override
        public String call() {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                    if (sb.length() > MAX_OUTPUT_LENGTH * 2) {
                        break;
                    }
                }
            } catch (IOException e) {
                // ignore
            }
            return sb.toString();
        }
    }

    private static File resolveWorkDir(String cwd, Project project) {
        if (cwd != null && !cwd.isBlank()) {
            File dir = new File(cwd);
            if (dir.isAbsolute()) {
                return dir;
            }
            if (project != null && project.getBasePath() != null) {
                return new File(project.getBasePath(), cwd);
            }
            return dir;
        }
        if (project != null && project.getBasePath() != null) {
            return new File(project.getBasePath());
        }
        return null;
    }

    /**
     * 高危命令检测
     * @return 命中的危险命令描述；null 表示安全
     */
    private static String checkDangerous(String cmd) {
        String lower = cmd.toLowerCase();
        for (String danger : DANGEROUS_COMMANDS) {
            if (lower.contains(danger)) {
                return "检测到危险命令模式 \"" + danger + "\"，已拒绝执行";
            }
        }
        return null;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_LENGTH) return text;
        return text.substring(0, MAX_OUTPUT_LENGTH);
    }
}
