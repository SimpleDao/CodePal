package com.codepal.mcp.transport;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Stdio 传输 — 启动子进程，通过 stdin/stdout 进行 JSON-RPC 通信。
 */
public class StdioTransport implements McpTransport {

    private final String[] command;
    private final String workingDir;
    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean running = false;

    public StdioTransport(String[] command, String workingDir) {
        this.command = fixWindowsCommand(command);
        this.workingDir = workingDir;
    }

    /** Windows 下 npx/uvx 等需要 .cmd 后缀 */
    private static String[] fixWindowsCommand(String[] cmd) {
        if (cmd == null || cmd.length == 0) return cmd;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return cmd;
        String exe = cmd[0];
        if (exe.contains(".")) return cmd;
        if (exe.contains("/") || exe.contains("\\")) return cmd;
        String[] fixed = cmd.clone();
        fixed[0] = exe + ".cmd";
        return fixed;
    }

    @Override
    public void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && !workingDir.isEmpty()) {
            pb.directory(new File(workingDir));
        }
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        running = true;

        Thread stderrThread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while (running && (line = err.readLine()) != null) {
                    System.err.println("[MCP:stderr] " + line);
                }
            } catch (IOException ignored) {}
        }, "MCP-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (stdin != null) try { stdin.close(); } catch (IOException ignored) {}
        if (stdout != null) try { stdout.close(); } catch (IOException ignored) {}
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Override
    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    @Override
    public void send(String json) throws IOException {
        if (!running || stdin == null) throw new IOException("Transport not running");
        stdin.write(json);
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public String readLine() throws IOException {
        if (!running || stdout == null) throw new IOException("Transport not running");
        return stdout.readLine();
    }

    @Override
    public String getType() {
        return "stdio";
    }

    @Override
    public String toString() {
        return "StdioTransport{" + String.join(" ", command) + "}";
    }
}
