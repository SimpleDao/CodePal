package com.codepal.cc.acp;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;

/**
 * Claude Code 命令定义（/ 触发）
 */
public class CCCommand {

    public enum Category {
        COMMANDS,   // 通用命令（/compact, /cost, /clear 等）
        SKILLS      // 技能命令（/explain, /fix, /tests 等）
    }

    private final String command;       // 如 "compact"
    private final String description;   // 如 "压缩对话历史，节省上下文空间"
    private final Icon icon;            // 图标
    private final Category category;    // 分类
    private final boolean requiresArgs; // 是否需要额外参数
    private final boolean sendToCC;     // true=发送给CC, false=本地处理

    public CCCommand(String command, String description, Icon icon,
                     Category category, boolean requiresArgs, boolean sendToCC) {
        this.command = command;
        this.description = description;
        this.icon = icon;
        this.category = category;
        this.requiresArgs = requiresArgs;
        this.sendToCC = sendToCC;
    }

    public String getCommand() { return command; }
    public String getDescription() { return description; }
    public Icon getIcon() { return icon; }
    public Category getCategory() { return category; }
    public boolean requiresArgs() { return requiresArgs; }
    public boolean isSendToCC() { return sendToCC; }

    public String getFullCommand() { return "/" + command; }

    /** 预定义的 Claude Code 命令列表（全部归类为 COMMANDS，不再区分 Skills） */
    public static final List<CCCommand> BUILT_IN = Arrays.asList(
        new CCCommand("compact",  "压缩对话历史，节省上下文空间", null, Category.COMMANDS, false, true),
        new CCCommand("cost",     "查看当前对话 token 消耗",     null, Category.COMMANDS, false, true),
        new CCCommand("init",     "初始化项目并生成配置",         null, Category.COMMANDS, false, true),
        new CCCommand("explain",  "解释代码工作原理",             null, Category.COMMANDS, false, true),
        new CCCommand("fix",      "修复代码问题",                 null, Category.COMMANDS, false, true),
        new CCCommand("tests",    "生成单元测试",                 null, Category.COMMANDS, false, true),
        new CCCommand("commit",   "提交代码变更",                 null, Category.COMMANDS, false, true),
        new CCCommand("review",   "审查代码",                     null, Category.COMMANDS, false, true),
        new CCCommand("clear",    "清空对话历史",                 null, Category.COMMANDS, false, false),
        new CCCommand("help",     "查看 CC 帮助",                 null, Category.COMMANDS, false, true)
    );

    /**
     * 根据输入文本匹配命令（忽略大小写，前缀匹配）
     */
    public static CCCommand match(String input) {
        if (input == null || !input.startsWith("/")) return null;
        String cmd = input.substring(1).trim().toLowerCase();
        // 去掉空格后的参数部分
        int spaceIdx = cmd.indexOf(' ');
        String cmdName = spaceIdx > 0 ? cmd.substring(0, spaceIdx) : cmd;
        for (CCCommand c : BUILT_IN) {
            if (c.command.equals(cmdName)) return c;
        }
        return null;
    }
}
