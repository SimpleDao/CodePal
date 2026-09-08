package com.codepal.cc.acp.model;

/**
 * session/prompt 响应中的停止原因。
 */
public enum StopReason {

    /** Agent 正常完成本轮对话 */
    END_TURN("end_turn"),

    /** 达到最大 token 限制 */
    MAX_TOKENS("max_tokens"),

    /** 达到最大请求次数 */
    MAX_TURN_REQUESTS("max_turn_requests"),

    /** Agent 拒绝继续 */
    REFUSAL("refusal"),

    /** 被 Client 取消 */
    CANCELLED("cancelled");

    private final String value;

    StopReason(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static StopReason fromString(String s) {
        for (StopReason r : values()) {
            if (r.value.equals(s)) return r;
        }
        return END_TURN;
    }
}
