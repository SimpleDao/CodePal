package com.codepal.linter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 shell 命令的 linter 基类
 */
public abstract class ShellBasedLinter implements Linter {

    /**
     * 获取检查版本的命令（判断 linter 是否安装）
     */
    protected abstract String getVersionCommand();

    /**
     * 获取 lint 单个文件的命令
     */
    protected abstract String getLintCommand(String filePath, String projectPath);

    /**
     * 解析 linter 输出为问题列表
     */
    protected abstract List<LintIssue> parseOutput(String stdout, String stderr, String filePath);

    @Override
    public boolean isAvailable(String projectPath) {
        try {
            String localCmd = findLocalCommand(projectPath);
            if (localCmd != null) {
                return runCommand(localCmd + " --version", projectPath, 5000).exitCode == 0;
            }
            return runCommand(getVersionCommand(), projectPath, 5000).exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查找项目本地的 linter 命令（如 node_modules/.bin/eslint）
     */
    protected String findLocalCommand(String projectPath) {
        return null;
    }

    /**
     * 获取实际执行的 lint 命令（优先本地，再全局）
     */
    protected String resolveCommand(String projectPath) {
        String localCmd = findLocalCommand(projectPath);
        if (localCmd != null) {
            try {
                CmdResult r = runCommand(localCmd + " --version", projectPath, 5000);
                if (r.exitCode == 0) return localCmd;
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public LintResult lintFile(String filePath, String projectPath) {
        try {
            if (!isAvailable(projectPath)) {
                return new LintResult(filePath, getName(), new ArrayList<>(), false);
            }

            String command = getLintCommand(filePath, projectPath);
            CmdResult result = runCommand(command, projectPath, 30000);

            List<LintIssue> issues = parseOutput(result.stdout, result.stderr, filePath);

            boolean success = (result.exitCode == 0) || !issues.isEmpty();
            String errorMsg = null;
            if (!success && issues.isEmpty() && result.stderr != null && !result.stderr.isBlank()) {
                errorMsg = result.stderr;
            }

            LintResult lr = new LintResult(filePath, getName(), issues, success);
            lr.errorMessage = errorMsg;
            return lr;
        } catch (Exception e) {
            LintResult r = new LintResult(filePath, getName(), new ArrayList<>(), false);
            r.errorMessage = "执行失败: " + e.getMessage();
            return r;
        }
    }

    /**
     * 执行命令并返回结果
     */
    protected CmdResult runCommand(String command, String workDir, int timeoutMs) {
        try {
            File dir = (workDir != null && !workDir.isBlank()) ? new File(workDir) : null;
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                dir = new File(".");
            }

            String osName = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = osName.contains("win");

            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }
            pb.directory(dir);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CmdResult(-1, "", "命令执行超时");
            }

            outThread.join(1000);
            errThread.join(1000);

            return new CmdResult(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (Exception e) {
            return new CmdResult(-1, "", e.getMessage());
        }
    }

    protected String getExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) return "";
        return filePath.substring(dot + 1).toLowerCase();
    }

    protected static class CmdResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        CmdResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
