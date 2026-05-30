package com.loongc.listener;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import groovyjarjarantlr4.v4.runtime.misc.NotNull;

public class PluginProjectManagerListener implements ProjectManagerListener {

    @Override
    public void projectClosing(@NotNull Project project) {
        // 项目关闭时，尝试释放 H2 连接
//        H2DatabaseManager.closeConnection();
    }
}