package com.codepal.tools;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IDEA 控制台命令执行器
 *
 * 在 IDEA 的 Run 工具窗口中执行命令，用户可见实时输出，
 * 同时收集输出结果返回给模型。
 */
public class ConsoleCommandRunner {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /**
     * 在 IDEA 控制台中执行命令，同步等待结果
     *
     * @param project        IDEA 项目
     * @param command        要执行的命令
     * @param workDir        工作目录
     * @param timeoutMs      超时时间（毫秒）
     * @param handlerConsumer 可选，ProcessHandler 创建后回调（用于外部取消）
     * @param cancelledRef   可选，外部取消标志（AtomicBoolean）
     * @return 命令执行结果
     */
    public static CommandRunner.Result runInConsole(
            @NotNull Project project,
            @NotNull String command,
            @Nullable String workDir,
            long timeoutMs,
            @Nullable java.util.function.Consumer<com.intellij.execution.process.ProcessHandler> handlerConsumer,
            @Nullable java.util.concurrent.atomic.AtomicBoolean cancelledRef
    ) {
        int id = COUNTER.incrementAndGet();
        String title = "CP: " + StringUtil.shortenTextWithEllipsis(command, 50, 0);

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder outputBuffer = new StringBuilder();
        AtomicInteger exitCodeRef = new AtomicInteger(-1);
        AtomicBoolean timedOut = new AtomicBoolean(false);

        // 1. 在 EDT 中创建控制台并启动
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                CPProcessHandler handler = executeInEdt(
                        project, title, command, workDir, outputBuffer, exitCodeRef, latch
                );
                if (handlerConsumer != null) {
                    handlerConsumer.accept(handler);
                }
            } catch (Exception e) {
                outputBuffer.append("\n[ConsoleRunner] 创建控制台失败: ").append(e.getMessage());
                exitCodeRef.set(-1);
                latch.countDown();
            }
        });

        // 2. 等待命令完成（带超时 + 外部取消检测）
        boolean finished = false;
        try {
            // 分段等待，每隔 200ms 检查一次取消标志
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!finished && System.currentTimeMillis() < deadline) {
                if (cancelledRef != null && cancelledRef.get()) {
                    // 外部取消了，主动销毁进程
                    break;
                }
                long waitMs = Math.min(200, deadline - System.currentTimeMillis());
                if (waitMs <= 0) break;
                finished = latch.await(waitMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outputBuffer.append("\n[ConsoleRunner] 等待被中断");
        }

        // 外部取消处理
        boolean userCancelled = (cancelledRef != null && cancelledRef.get());
        if (userCancelled) {
            outputBuffer.append("\n[ConsoleRunner] 用户已终止命令执行");
        } else if (!finished) {
            timedOut.set(true);
            outputBuffer.append("\n[ConsoleRunner] 命令超时 (").append(timeoutMs).append("ms)");
        }

        // 3. 组装结果
        if (userCancelled) {
            return CommandRunner.Result.cancelled(outputBuffer.toString(), "", 0);
        }
        if (timedOut.get()) {
            return CommandRunner.Result.timeout(outputBuffer.toString(), "", 0);
        }
        return CommandRunner.Result.ok(
                exitCodeRef.get(),
                outputBuffer.toString(),
                "",
                0
        );
    }

    /**
     * 在 EDT 线程中执行控制台相关操作，返回创建的 ProcessHandler
     */
    private static CPProcessHandler executeInEdt(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String command,
            @Nullable String workDir,
            @NotNull StringBuilder outputBuffer,
            @NotNull AtomicInteger exitCodeRef,
            @NotNull CountDownLatch latch
    ) {
        // 用 TextConsoleBuilder 创建标准控制台
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .getConsole();

        // 自定义 ProcessHandler
        CPProcessHandler processHandler = new CPProcessHandler(
                command, workDir, outputBuffer, exitCodeRef, latch
        );

        consoleView.attachToProcess(processHandler);

        // 创建 RunContentDescriptor
        RunContentDescriptor descriptor = new RunContentDescriptor(
                consoleView,
                processHandler,
                consoleView.getComponent(),
                title
        );

        descriptor.setAutoFocusContent(true);
        descriptor.setActivateToolWindowWhenAdded(true);

        Executor executor = DefaultRunExecutor.getRunExecutorInstance();
        ExecutionManager.getInstance(project).getContentManager()
                .showRunContent(executor, descriptor);

        // 启动进程通知
        processHandler.startNotify();

        return processHandler;
    }

    /**
     * 自定义 ProcessHandler，管理外部进程并收集输出
     */
    private static class CPProcessHandler extends com.intellij.execution.process.OSProcessHandler {

        private final StringBuilder outputBuffer;
        private final AtomicInteger exitCodeRef;
        private final CountDownLatch latch;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        public CPProcessHandler(
                String command,
                String workDir,
                StringBuilder outputBuffer,
                AtomicInteger exitCodeRef,
                CountDownLatch latch
        ) {
            super(createProcess(command, workDir), command, null);
            this.outputBuffer = outputBuffer;
            this.exitCodeRef = exitCodeRef;
            this.latch = latch;
        }

        private static Process createProcess(String command, String workDir) {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = new ProcessBuilder();
                if (isWindows) {
                    pb.command("cmd.exe", "/c", command);
                } else {
                    pb.command("sh", "-c", command);
                }
                if (workDir != null && !workDir.isBlank()) {
                    File dir = new File(workDir);
                    if (dir.exists()) {
                        pb.directory(dir);
                    }
                }
                pb.redirectErrorStream(true);
                return pb.start();
            } catch (Exception e) {
                throw new RuntimeException("启动进程失败: " + e.getMessage(), e);
            }
        }

        @Override
        public void notifyTextAvailable(@NotNull String text, @NotNull Key outputType) {
            super.notifyTextAvailable(text, outputType);
            outputBuffer.append(text);
        }

        @Override
        protected void onOSProcessTerminated(int exitCode) {
            super.onOSProcessTerminated(exitCode);
            if (finished.compareAndSet(false, true)) {
                exitCodeRef.set(exitCode);
                latch.countDown();
            }
        }

        @Override
        public boolean isSilentlyDestroyOnClose() {
            return true;
        }
    }
}
