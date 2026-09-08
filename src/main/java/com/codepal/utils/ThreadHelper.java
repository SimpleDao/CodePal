package com.codepal.utils;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class ThreadHelper {

    /**
     * 1. 通用查询方法 (适合有返回值的操作，支持任何数据类型)
     * @param dbTask  【后台线程】执行的代码，必须 return 一个结果
     * @param uiTask  【UI 线程】执行的代码，用来接收并使用这个结果
     */
    public static <T> void executeAsync(Project project, Supplier<T> dbTask, Consumer<T> uiTask) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            System.out.println("queryAsync SQL执行：");
            // 自动在后台执行查询
            T result = dbTask.get();

            // 自动切回 UI 线程
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project != null && project.isDisposed()) return;
                if (uiTask != null) {
                    uiTask.accept(result); // 把结果送给 UI
                }
            });
            System.out.println("queryAsync SQL结束：");
        });
    }

    /**
     * 2. 通用执行方法 (适合增、删、改等无返回值的操作)
     * @param dbTask  【后台线程】执行的增删改动作
     * @param uiTask  【UI 线程】操作完成后想做的事（比如弹个提示、刷新列表），不需要可传 null
     */
    public static void executeAsync(Project project, Runnable dbTask, Runnable uiTask) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            System.out.println("executeAsync SQL执行：");
            // 自动在后台执行增删改
            if (dbTask != null) {
                dbTask.run();
            }

            // 自动切回 UI 线程
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project != null && project.isDisposed()) return;
                if (uiTask != null) {
                    uiTask.run(); // 触发 UI 回调
                }
            });
            System.out.println("executeAsync SQL结束：");
        });
    }

    /**
     * 新增：2.5 通用执行方法 (无返回值，无 UI 回调)
     */
    public static void executeAsync(Project project, Runnable dbTask) {
        executeAsync(project, dbTask, null);
    }

    /**
     * 新增：3. 纯 UI 刷新方法
     */
    public static void runOnUi(Project project, Runnable uiTask) {
        if (uiTask == null) return;
        System.out.println("runOnUi 执行：");
        // 自适应检查：如果本身就是 UI 线程，直接执行（零延迟，瞬间刷新）
        if (ApplicationManager.getApplication().isDispatchThread()) {
            if (project != null && project.isDisposed()) return;
            uiTask.run();
            System.out.println("runOnUi 结束：");
        } else {
            // 如果在后台线程（比如流式输出、网络请求中），自动排队切回 UI 线程
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project != null && project.isDisposed()) return;
                uiTask.run();
            });
            System.out.println("runOnUi else  结束：");
        }

    }
}
