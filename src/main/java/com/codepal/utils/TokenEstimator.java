package com.codepal.utils;

import com.codepal.model.ChatMessage;

import java.util.List;

/**
 * 统一的启发式 token 估算器（展示级精度）——全插件唯一的估算口径。
 *
 * <p>【标准说明】token 由各家私有 tokenizer 切分，不存在跨厂商通用换算系数：
 * <ul>
 *   <li>DeepSeek 官方：1 个中文字符 ≈ 0.6 token，1 个英文字符 ≈ 0.3 token
 *       （docs/deepseek-token计费示例.md）；</li>
 *   <li>OpenAI：不公布换算系数，提供 tiktoken 精确计数；cl100k/o200k 对常见汉字
 *       约 1 token/字；</li>
 *   <li>Claude/Gemini：私有词表，量级与 OpenAI 相近。</li>
 * </ul>
 * 本估算器按主力后端 DeepSeek 的官方系数实现；对其它厂商属已知近似值
 * （OpenAI 口径下会低估）。精确数字一律以 API 返回的 usage 为准。
 */
public final class TokenEstimator {

    /** 每个 CJK 字符的 token 数（DeepSeek 官方：1 中文字符 ≈ 0.6 token） */
    private static final double TOKENS_PER_CJK = 0.6;
    /** 每个非 CJK 字符的 token 数（DeepSeek 官方：1 英文字符 ≈ 0.3 token） */
    private static final double TOKENS_PER_OTHER = 0.3;

    private TokenEstimator() {}

    /** 估算一段文本的 token 数（中文 0.6/字，其它字符 0.3/个） */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        double other = 0;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            if ((code >= 0x4e00 && code <= 0x9fff)      // CJK 统一表意文字
                    || (code >= 0x3000 && code <= 0x30ff)) { // CJK 标点/假名
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(code);
        }
        return Math.max(0, Math.round((float) (cjk * TOKENS_PER_CJK + other * TOKENS_PER_OTHER)));
    }

    /**
     * 估算单条消息的 token 数：content + reasoning_content + tool_calls 参数
     * （这三者都会随请求回传给 API，属于真实上下文的一部分）。
     */
    public static long estimateMessageTokens(ChatMessage m) {
        if (m == null) return 0;
        long total = estimateTokens(m.getContent());
        total += estimateTokens(m.getReasoning_content());
        if (m.getTool_calls() != null) {
            for (ChatMessage.ToolCall tc : m.getTool_calls()) {
                if (tc != null && tc.getFunction() != null
                        && tc.getFunction().getArguments() != null) {
                    total += estimateTokens(tc.getFunction().getArguments());
                }
            }
        }
        return Math.max(0, total);
    }

    /** 估算消息列表（会话上下文）的 token 总量 */
    public static long estimateSession(List<ChatMessage> msgs) {
        if (msgs == null) return 0;
        long total = 0;
        for (ChatMessage m : msgs) total += estimateMessageTokens(m);
        return Math.max(0, total);
    }
}
