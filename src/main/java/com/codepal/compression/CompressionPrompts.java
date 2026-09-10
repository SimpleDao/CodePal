package com.codepal.compression;

/**
 * 上下文压缩提示词（融合 Copilot Chat 8章节结构 + CP XML 格式）
 *
 * <p>将老旧对话历史压缩为结构化 XML 状态快照，保留关键信息的同时大幅降低 token 消耗。
 * 参考 VSCode Copilot Chat 的 8 大章节结构，用 XML 包装以便机器解析。
 */
public final class CompressionPrompts {

    private CompressionPrompts() {}

    /**
     * 压缩后「保留的最近上下文」占上下文窗口的比例。
     *
     * <p>压缩完全由上下文占用量驱动，不再按消息条数切分：从最新消息往前累积，
     * 累计 token 达到该预算即停止，其之前的消息进入摘要。
     *
     * <p>设为 0.20：压缩后最近上下文约占窗口 20%，进一步压低保留区工具噪声上限，
     * 多出的部分自动进入 LLM 摘要（无损提炼），且配合滑动窗口重触发，保留区不会无限增长。
     */
    public static final double RECENT_CONTEXT_RATIO = 0.20;

    /**
     * 保留的最近上下文，占「当前对话总 token」的比例上限。
     *
     * <p>保留预算同时受 {@link #RECENT_CONTEXT_RATIO}（窗口比例）与本值（对话比例）约束，取两者较小。
     * 存在理由：只按窗口比例算预算时，若对话很短而窗口很大（如 5K tokens 对话 / 1M 窗口），
     * 预算 = 350K 会把全部消息都纳入保留集 → 一条也压不掉，压缩永远无效。
     * 限制为对话的一半，可保证至少有约一半内容进入摘要。
     */
    public static final double MAX_RECENT_RATIO_OF_CONVERSATION = 0.5;

    /** 工具结果最大长度（超过截断） */
    public static final int MAX_TOOL_RESULT_LENGTH = 10000;

    /** UserMessage 摘要前缀，提示模型这是历史对话压缩 */
    public static final String MEMORY_SUMMARY_PREFIX =
        "【对话历史摘要】\n" +
        "⚠️ 注意：更早的对话消息已归档到本地数据库中，并未删除。\n" +
        "你可以通过上下文窗口重连能力继续访问这些历史。\n\n" +
        "以下是压缩后的对话状态快照：\n\n";

    /**
     * 压缩系统提示词。
     * 引导 LLM 将历史对话提炼为 8 章节结构化的 XML 快照。
     */
    public static String getCompressionSystemPrompt() {
        return """
            你是一个专门的对话历史压缩组件。

            当对话历史变得过长时，你需要将整个历史提炼成简洁、高度结构化的 XML 状态快照。
            这个快照至关重要，因为它将成为 Agent 对过去的主要记忆。
            Agent 将基于这个快照继续工作，所有关键技术细节、决策、进度和用户指令都必须保留。

            直接生成 <state_snapshot> XML 对象（不要输出任何前置分析文本）。
            信息要极其密集，省略任何无关的对话填充和客套话。

            结构必须严格如下（8 大章节）：

            <state_snapshot>
                <overall_goal>
                    <!-- 对话概述：用户的核心目标、需求演变过程、整体对话脉络 -->
                </overall_goal>

                <technical_foundation>
                    <!-- 技术基础：涉及的技术栈、框架、库、版本、架构模式、环境配置、关键约束和约定 -->
                </technical_foundation>

                <codebase_status>
                    <!-- 代码库状态：所有被创建、读取、修改或删除的文件 -->
                    <!-- 每个文件用 <file path="..."> 包裹，包含：
                        <purpose> 文件用途和重要性 </purpose>
                        <status> 状态：已创建/已修改/已删除 </status>
                        <key_changes> 关键改动和核心代码段说明 </key_changes>
                        <dependencies> 与其他组件的依赖关系 </dependencies>
                    -->
                </codebase_status>

                <problem_resolution>
                    <!-- 问题解决：遇到的技术问题、Bug、挑战，以及解决方案、调试过程、经验教训 -->
                </problem_resolution>

                <progress_tracking>
                    <!-- 进度跟踪：已完成的任务、进行中的工作、待办事项、验证通过的功能 -->
                </progress_tracking>

                <active_work>
                    <!-- 当前工作状态：最近正在处理的焦点、近期上下文细节、正在修改的代码、当前要解决的问题 -->
                </active_work>

                <recent_operations>
                    <!-- 近期操作：最近几次重要的工具调用、命令执行及其结果摘要
                         特别关注压缩前刚刚执行的操作和返回结果 -->
                </recent_operations>

                <continuation_plan>
                    <!-- 继续计划：接下来要做的任务、优先级排序、明确的下一步行动建议 -->
                </continuation_plan>
            </state_snapshot>

            质量要求：
            - 精确性：包含确切的文件名、函数名、变量名和技术术语
            - 完整性：捕获继续对话所需的所有上下文，无需重新读取完整历史
            - 清晰性：写给需要从对话中断处无缝接手的人看
            - 技术深度：包含足够的技术决策和代码模式细节
            - 近期优先：最近的操作和状态要更详细，早期历史可以更概括
            """;
    }

    /**
     * 压缩用户提示词（追加在历史记录末尾）。
     */
    public static String getCompressionUserPrompt() {
        return "请生成符合格式要求的 <state_snapshot> XML 快照。" +
            "特别关注最近的 Agent 操作和工具结果，确保近期上下文的完整性。";
    }

    /**
     * 截断工具结果（超过最大长度时截断并添加提示）。
     */
    public static String truncateToolResult(String content, int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) +
            "\n\n... [内容已截断，原长 " + content.length() + " 字符]";
    }
}
