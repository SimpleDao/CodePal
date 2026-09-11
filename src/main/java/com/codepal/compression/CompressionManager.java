package com.codepal.compression;

import com.codepal.model.ModelConfig;
import com.intellij.openapi.project.Project;
import com.codepal.api.ModelLinkDispatcher;
import com.codepal.model.ChatMessage;
import com.codepal.model.ChatRequest;
import com.codepal.settings.CPSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 对话上下文压缩管理器
 *
 * <p>功能：
 * <ul>
 *   <li>将老旧对话压缩为 8 章节 XML 结构化快照</li>
 *   <li>压缩失败直接抛异常，不降级截断（无意义，不如让用户重试）</li>
 *   <li>工具结果统一截断，防止大体积输出撑爆上下文</li>
 * </ul>
 *
 * <p>内存结构（压缩后）：
 * [SystemMessage] → [摘要 UserMessage] → [最近 N 条消息]
 *
 * @author 水龙吟
 */
public class CompressionManager {

    /**
     * 压缩 LLM 调用超时（秒）。不能设太短：压缩要把整段历史发给模型，
     * 上下文越大 prefill 越久，再叠加模型生成时间（TPS 低的网关 8K 输出要 3 分钟+）。
     * 300 秒对流式足够宽裕（HTTP readTimeout 120s 只限"无数据间隔"，不受总时长约束）。
     */
    private static final long COMPRESS_TIMEOUT_SECONDS = 300;

    private final Project project;

    public CompressionManager(Project project) {
        this.project = project;
    }

    /**
     * 检查是否需要压缩：不再看消息条数，改为看上下文使用量占窗口的比例。
     * 使用量 ≥ 阈值（默认 80%）时才建议压缩，更贴合真实上下文占用。
     */
    public boolean shouldCompressByUsage(long usedTokens, long contextWindowTokens) {
        if (contextWindowTokens <= 0) return false;
        return (double) usedTokens / contextWindowTokens >= COMPRESSION_THRESHOLD_RATIO;
    }

    /** 压缩阈值：上下文使用量占上下文窗口的比例达到该值即建议压缩（80%） */
    public static final double COMPRESSION_THRESHOLD_RATIO = 0.80;

    /**
     * token 预算允许时，压缩后仍尽量保留的最小尾部条数（软下限，超出预算也会保底）。
     *
     * <p>这不是「按条数限制压缩」——仅用于避免只留 1 条导致近期上下文过薄。
     * 真正的硬下限是 1 条（见 findSplitPoint）：保留集为空会让 DB 压缩标记失效。
     */
    public static final int MIN_PRESERVE_COUNT = 2;

    /**
     * 获取当前消息数（不含 system）
     */
    public int getMessageCount(List<ChatMessage> messages) {
        return (int) messages.stream()
            .filter(m -> !"system".equals(m.getRole()))
            .count();
    }

    /**
     * 执行压缩。
     *
     * @param messages     当前对话消息列表（会被修改）
     * @param conversationId 会话ID（用于归档）
     * @param statusConsumer 状态回调（用于UI更新）
     * @return 压缩是否成功
     */
    public CompressionResult compress(
        List<ChatMessage> messages,
        String conversationId,
        Consumer<String> statusConsumer,
        long contextWindowTokens
    ) {
        int oldCount = messages.size();
        statusConsumer.accept("正在分析对话历史");

        // 1. 找出分割点：按 token 预算保留最近上下文（不看消息条数）
        SplitPlan plan = planSplit(messages, contextWindowTokens);
        if (plan.splitPoint <= 0) {
            // 文案必须区分两种「压不动」，否则会误导：
            // 消息少到无法切分，与消息够多但全部落在预算内，是完全不同的原因。
            String reason = plan.totalMessages <= 1
                ? "对话消息不足（" + plan.totalMessages + " 条），至少需要 2 条才能压缩"
                : "全部 " + plan.totalMessages + " 条消息（约 " + plan.totalTokens + " tokens）"
                    + "都在保留预算内（预算 " + plan.budgetTokens + " tokens），暂无可压缩内容";
            return new CompressionResult(false, reason, oldCount, oldCount);
        }
        int splitPoint = plan.splitPoint;

        // 2. 提取需要压缩的旧消息（排除 system message）
        List<ChatMessage> oldMessages = new ArrayList<>(messages.subList(0, splitPoint));
        List<ChatMessage> systemMessages = oldMessages.stream()
            .filter(m -> "system".equals(m.getRole()))
            .collect(Collectors.toList());
        List<ChatMessage> toCompress = oldMessages.stream()
            .filter(m -> !"system".equals(m.getRole()))
            .collect(Collectors.toList());

        if (toCompress.isEmpty()) {
            return new CompressionResult(false, "没有可压缩的消息", oldCount, oldCount);
        }

        statusConsumer.accept("正在生成对话摘要（约需5-15秒）");

        // 3. 调用 LLM 生成摘要（不降级，失败直接抛异常）
        String summary;
        try {
            summary = callLlmSummary(toCompress);
        } catch (Exception e) {
            throw new RuntimeException("LLM 摘要生成失败: " + e.getMessage(), e);
        }
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM 返回了空的摘要内容");
        }
        // 提取 <state_snapshot> 部分
        summary = extractStateSnapshot(summary);
        if (summary.isBlank()) {
            throw new RuntimeException("LLM 返回的摘要中未找到有效的 <state_snapshot> 标签");
        }

        // 4. 重建消息列表（先构建到本地，再做「防膨胀」校验，避免摘要反而更占 token）
        // 保留最近的消息
        List<ChatMessage> recentMessages = new ArrayList<>(messages.subList(splitPoint, messages.size()));

        List<ChatMessage> newMessages = new ArrayList<>();
        // 先加 system messages
        for (ChatMessage sysMsg : systemMessages) {
            newMessages.add(sysMsg);
        }
        // LLM 摘要成功
        String fullSummary = CompressionPrompts.MEMORY_SUMMARY_PREFIX + summary;
        newMessages.add(new ChatMessage("user", fullSummary));
        statusConsumer.accept("摘要生成完成，正在重建上下文");
        // 加回最近的消息
        newMessages.addAll(recentMessages);

        // 防膨胀校验（参考 auto-dev ChatCompressionService 的 INFLATED_TOKEN_COUNT 保护）：
        // 若压缩后 token 未减少，则放弃本次压缩、保留原历史，防止更差的快照雪上加霜。
        long oldTokens = sumTokens(messages);
        long newTokens = sumTokens(newMessages);
        if (newTokens >= oldTokens) {
            return new CompressionResult(false,
                "压缩后 token 未减少（" + newTokens + " ≥ " + oldTokens + "），放弃本次压缩以免上下文更膨胀",
                oldCount, oldCount);
        }

        messages.clear();
        messages.addAll(newMessages);

        int newCount = messages.size();
        String msg = "压缩完成：" + oldCount + " → " + newCount + " 条消息（约 " + oldTokens + " → " + newTokens + " tokens）";

        // 找到第一条保留消息的 qaRound（用于 DB 标记压缩范围）
        int firstPreservedQaRound = 0;
        for (ChatMessage m : recentMessages) {
            if (!"system".equals(m.getRole()) && m.getQaRound() > 0) {
                firstPreservedQaRound = m.getQaRound();
                break;
            }
        }

        return new CompressionResult(true, msg, oldCount, newCount,
            summary, firstPreservedQaRound, toCompress.size());
    }

    /** 切分方案 + 诊断信息（用于失败时给出准确、不误导的提示文案） */
    private static final class SplitPlan {
        int splitPoint;      // 首条保留消息下标；<=0 表示无可压缩内容
        int totalMessages;   // 非 system 消息数
        long totalTokens;    // 全部非 system 消息的 token 估算
        long budgetTokens;   // 实际生效的保留预算
    }

    /**
     * 计算切分方案：返回首条「保留消息」的下标，其之前的非 system 消息进入压缩。
     *
     * <p><b>纯 token 预算驱动，不看消息条数</b>：从最新消息往前累积估算 token，
     * 累计达到预算即停止纳入，其余（更早的）全部压缩。
     * 这样「消息少但单条超长」和「消息多但每条很短」都能得到合理切分，
     * 也不会出现固定条数导致的断崖（如 81 条时只能压 1 条）。
     *
     * <p><b>预算取两个约束的较小值</b>：
     * <ul>
     *   <li>窗口比例 {@code window × RECENT_CONTEXT_RATIO}：保证压缩后不逼近窗口上限。</li>
     *   <li>对话比例 {@code totalTokens × MAX_RECENT_RATIO_OF_CONVERSATION}：
     *       保证至少有约一半内容进入摘要。缺了这条，对话很短而窗口很大时
     *       （如 5K tokens 对话 / 1M 窗口）全部消息都落在预算内 → 永远压不动。</li>
     * </ul>
     *
     * @param messages           完整消息列表
     * @param contextWindowTokens 模型上下文窗口大小
     */
    private static SplitPlan planSplit(List<ChatMessage> messages, long contextWindowTokens) {
        SplitPlan plan = new SplitPlan();

        int total = 0;
        long totalTokens = 0;
        for (ChatMessage m : messages) {
            if ("system".equals(m.getRole())) continue;
            total++;
            totalTokens += estimateMessageTokens(m);
        }
        plan.totalMessages = total;
        plan.totalTokens = totalTokens;

        // 硬下限：必须保留 ≥1 条。recentMessages 为空 → firstPreservedQaRound=0
        // → DB 不标记压缩范围 → 重载会话把旧消息全部读回，压缩等于白做。
        if (total <= 1) {
            plan.splitPoint = 0;
            return plan;
        }

        long windowBudget = contextWindowTokens > 0
                ? (long) (contextWindowTokens * CompressionPrompts.RECENT_CONTEXT_RATIO)
                : 0;
        long convBudget = (long) (totalTokens
                * CompressionPrompts.MAX_RECENT_RATIO_OF_CONVERSATION);
        long budget = windowBudget > 0 ? Math.min(windowBudget, convBudget) : convBudget;
        plan.budgetTokens = budget;

        // 软下限：预算允许时尽量多留几条，避免只留 1 条导致近期上下文过薄
        int floor = Math.min(MIN_PRESERVE_COUNT, total - 1);

        long acc = 0;
        int kept = 0;
        int split = 0;

        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("system".equals(m.getRole())) continue;

            long t = estimateMessageTokens(m);
            if (kept >= floor && acc + t > budget) {
                split = i + 1;   // 本条不纳入保留集，它是最后一条待压缩消息
                break;
            }
            acc += t;
            kept++;
            split = i;           // 目前已纳入的最早一条
        }

        // 一条都没压到（全部保留）→ 无可压缩
        if (kept >= total) {
            plan.splitPoint = 0;
            return plan;
        }
        plan.splitPoint = split;
        return plan;
    }

    /**
     * 估算单条消息的 token 数（展示级精度）。
     * 口径与 ChatPanel.estimateSessionTokens 一致（≈ 字符数 / 1.6），
     * 保证圆环显示的占用量与压缩切分用的是同一把尺子。
     */
    public static long estimateMessageTokens(ChatMessage m) {
        long chars = 0;
        if (m.getContent() != null) chars += m.getContent().length();
        if (m.getReasoning_content() != null) chars += m.getReasoning_content().length();
        if (m.getTool_calls() != null) {
            for (ChatMessage.ToolCall tc : m.getTool_calls()) {
                if (tc != null && tc.getFunction() != null
                        && tc.getFunction().getArguments() != null) {
                    chars += tc.getFunction().getArguments().length();
                }
            }
        }
        return Math.max(0, chars / 16 * 10);
    }

    /** 估算整段消息列表的 token 总量（口径同 estimateMessageTokens） */
    private static long sumTokens(List<ChatMessage> msgs) {
        long total = 0;
        for (ChatMessage m : msgs) total += estimateMessageTokens(m);
        return total;
    }

    /** 用户是否请求取消当前压缩（压缩可能耗时数分钟，必须允许中止） */
    private static final java.util.concurrent.atomic.AtomicBoolean COMPRESS_CANCELLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 当前压缩的 future，供取消时中断等待 */
    private static volatile CompletableFuture<String> currentCompressFuture = null;

    /** 取消正在进行的压缩：中断等待并置标志（底层 HTTP 流会被丢弃，不影响 UI 可用性） */
    public static void cancelCompress() {
        COMPRESS_CANCELLED.set(true);
        CompletableFuture<String> f = currentCompressFuture;
        if (f != null) f.cancel(true);
    }

    /**
     * 调用 LLM 生成对话摘要
     */
    private String callLlmSummary(List<ChatMessage> toCompress) throws Exception {
        // 构建历史文本
        StringBuilder historyText = new StringBuilder();
        for (ChatMessage msg : toCompress) {
            String role = msg.getRole();
            String text = extractMessageText(msg);
            if (text != null && !text.isBlank()) {
                // 截断过长的工具结果
                if ("tool".equals(role)) {
                    text = CompressionPrompts.truncateToolResult(text, CompressionPrompts.MAX_TOOL_RESULT_LENGTH);
                }
                historyText.append("[").append(role).append("]: ").append(text).append("\n\n");
            }
        }

        // 构建压缩请求
        String systemPrompt = CompressionPrompts.getCompressionSystemPrompt();
        String userPrompt =
            "--- 以下是需要压缩的对话历史 ---\n\n"
            + historyText
            + "\n\n" + CompressionPrompts.getCompressionUserPrompt();

        List<ChatMessage> compressMessages = new ArrayList<>();
        compressMessages.add(new ChatMessage("system", systemPrompt));
        compressMessages.add(new ChatMessage("user", userPrompt));

        CompletableFuture<String> future = new CompletableFuture<>();
        COMPRESS_CANCELLED.set(false);
        currentCompressFuture = future;   // 注册，供 cancelCompress() 中断
        StringBuilder resultBuilder = new StringBuilder();
        AtomicReference<String> errorRef = new AtomicReference<>();

        CPSettings settings = CPSettings.getInstance();
        ModelConfig compressModelCfg = settings.getCompressionOrChatModel(); // 优先已配置压缩模型，否则回退聊天模型
        ChatRequest compressRequest = new ChatRequest();
        compressRequest.setModel(compressModelCfg != null ? compressModelCfg.getName() : settings.getChatModelName());
        compressRequest.setMessages(compressMessages);
        compressRequest.setStream(true);
        // 摘要输出通常 500-2000 token，设 8192 留足余量防止截断
        compressRequest.setMax_tokens(8192);
        compressRequest.setTemperature(0.3);
        // 关闭深度思考：压缩是结构化总结任务，不需要思考链条，节省时间和 token
        compressRequest.setThinking(false);
        // 压缩请求不带工具，必须把 tool_choice 置空——否则默认 "auto" 会被序列化进 JSON，
        // 而 OpenAI / DeepSeek 要求 tool_choice != none 时必须同时有 tools，否则 HTTP 400。
        compressRequest.setTool_choice(null);

        // 使用流式调用，收集完整结果；按当前压缩模型 apiFormat 选 OpenAI / Anthropic 链路
        ModelLinkDispatcher.streamChat(compressRequest, compressMessages, null,
                compressModelCfg, new ModelLinkDispatcher.Relay() {
            @Override
            public void onMessage(String content) {
                if (content != null) {
                    resultBuilder.append(content);
                }
            }

            @Override
            public void onReasoning(String reasoning) {
                // 思考过程忽略，只收集最终内容
            }

            @Override
            public void onComplete() {
                future.complete(resultBuilder.toString());
            }

            @Override
            public void onToolCalls(List<ChatMessage.ToolCall> toolCalls) {
                // 摘要不需要工具调用，直接完成
                future.complete(resultBuilder.toString());
            }

            @Override
            public void onError(Throwable e) {
                errorRef.set(e.getMessage());
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(COMPRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("摘要生成超时（" + COMPRESS_TIMEOUT_SECONDS + "秒）", e);
        } catch (java.util.concurrent.CancellationException e) {
            // 用户点了停止：明确区分"主动取消"，避免显示成普通失败让用户以为出错
            if (COMPRESS_CANCELLED.get()) {
                throw new RuntimeException("已取消压缩", e);
            }
            throw new RuntimeException("摘要生成失败: 请求被取消", e);
        } catch (Exception e) {
            throw new RuntimeException("摘要生成失败: " + (errorRef.get() != null ? errorRef.get() : e.getMessage()), e);
        } finally {
            currentCompressFuture = null;
        }
    }

    /**
     * 从 ChatMessage 中提取文本内容
     */
    private String extractMessageText(ChatMessage msg) {
        StringBuilder sb = new StringBuilder();
        if (msg.getContent() != null) {
            sb.append(msg.getContent());
        }
        if (msg.getTool_calls() != null && !msg.getTool_calls().isEmpty()) {
            sb.append("\n[工具调用]\n");
            for (ChatMessage.ToolCall tc : msg.getTool_calls()) {
                sb.append("- ").append(tc.getFunction().getName());
                if (tc.getFunction().getArguments() != null) {
                    String args = tc.getFunction().getArguments();
                    if (args.length() > 500) {
                        args = args.substring(0, 500) + "...";
                    }
                    sb.append("(").append(args).append(")");
                }
                sb.append("\n");
            }
        }
        if (msg.getName() != null) {
            sb.insert(0, "[工具: " + msg.getName() + "] ");
        }
        return sb.toString();
    }

    /**
     * 从 LLM 输出中提取 <state_snapshot> 部分
     */
    private String extractStateSnapshot(String text) {
        if (text == null) return "";
        int start = text.indexOf("<state_snapshot>");
        int end = text.indexOf("</state_snapshot>");
        if (start >= 0 && end > start) {
            return text.substring(start, end + "</state_snapshot>".length());
        }
        // 如果没找到标签，返回原文（可能是简单模式输出）
        return text.trim();
    }

    /**
     * 压缩结果
     */
    public static class CompressionResult {
        public final boolean success;
        public final String message;
        public final int oldCount;
        public final int newCount;
        public final String summaryText;
        public final int firstPreservedQaRound;
        public final int compressedMessageCount;

        public CompressionResult(boolean success, String message, int oldCount, int newCount) {
            this(success, message, oldCount, newCount, null, 0, 0);
        }

        public CompressionResult(boolean success, String message, int oldCount, int newCount,
                                 String summaryText, int firstPreservedQaRound, int compressedMessageCount) {
            this.success = success;
            this.message = message;
            this.oldCount = oldCount;
            this.newCount = newCount;
            this.summaryText = summaryText;
            this.firstPreservedQaRound = firstPreservedQaRound;
            this.compressedMessageCount = compressedMessageCount;
        }

        public int getSavedCount() {
            return oldCount - newCount;
        }
    }
}
