package com.codepal.model;

/**
 * 单模型 token 用量桶 —— 会话内某模型累计消耗（只存 token 数）。
 *
 * <p>费用不落库：展示时按该模型当前配置的单价实时折算，改单价即时生效。
 */
public class ModelTokenUsage {

    private long prompt;      // 输入 tokens（含缓存命中 + 未命中）
    private long completion;  // 输出 tokens
    private long cacheHit;    // 输入·缓存命中
    private long cacheMiss;   // 输入·缓存未命中

    public ModelTokenUsage() {}

    /** 累加一次请求的 usage */
    public void add(ChatResponse.Usage u) {
        if (u == null) return;
        prompt     += u.getPromptTokens();
        completion += u.getCompletionTokens();
        cacheHit   += u.getPromptCacheHitTokens();
        cacheMiss  += u.getPromptCacheMissTokens();
    }

    public void add(long prompt, long completion, long cacheHit, long cacheMiss) {
        this.prompt     += prompt;
        this.completion += completion;
        this.cacheHit   += cacheHit;
        this.cacheMiss  += cacheMiss;
    }

    /** 总 token 消耗（输入 + 输出） */
    public long totalTokens() { return prompt + completion; }

    /** 总输入（缓存命中 + 未命中） */
    public long totalInput() { return cacheHit + cacheMiss; }

    public long getPrompt() { return prompt; }
    public void setPrompt(long v) { this.prompt = v; }

    public long getCompletion() { return completion; }
    public void setCompletion(long v) { this.completion = v; }

    public long getCacheHit() { return cacheHit; }
    public void setCacheHit(long v) { this.cacheHit = v; }

    public long getCacheMiss() { return cacheMiss; }
    public void setCacheMiss(long v) { this.cacheMiss = v; }
}
