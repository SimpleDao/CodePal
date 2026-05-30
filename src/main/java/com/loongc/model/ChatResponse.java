package com.loongc.model;

import java.util.List;

/**
 * DeepSeek 聊天响应模型
 */
public class ChatResponse {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    /** 最后一帧（finish_reason 不为 null 时）返回的 token 用量统计 */
    private Usage usage;

    // ── Usage 嵌套类 ──────────────────────────────────────────────────────────
    public static class Usage {
        /** 本次请求输入的总 token 数（含缓存命中 + 未命中） */
        private int prompt_tokens;
        /** 本次请求输出的 token 数 */
        private int completion_tokens;
        /** 输入 + 输出总计 */
        private int total_tokens;
        /** 输入中缓存命中的 token 数 */
        private int prompt_cache_hit_tokens;
        /** 输入中缓存未命中的 token 数 */
        private int prompt_cache_miss_tokens;

        public int getPromptTokens()            { return prompt_tokens; }
        public void setPromptTokens(int v)      { this.prompt_tokens = v; }
        public int getCompletionTokens()         { return completion_tokens; }
        public void setCompletionTokens(int v)   { this.completion_tokens = v; }
        public int getTotalTokens()              { return total_tokens; }
        public void setTotalTokens(int v)        { this.total_tokens = v; }
        public int getPromptCacheHitTokens()     { return prompt_cache_hit_tokens; }
        public void setPromptCacheHitTokens(int v){ this.prompt_cache_hit_tokens = v; }
        public int getPromptCacheMissTokens()    { return prompt_cache_miss_tokens; }
        public void setPromptCacheMissTokens(int v){ this.prompt_cache_miss_tokens = v; }

        @Override
        public String toString() {
            return "Usage{" +
                    "prompt_tokens=" + prompt_tokens +
                    ", completion_tokens=" + completion_tokens +
                    ", total_tokens=" + total_tokens +
                    ", prompt_cache_hit_tokens=" + prompt_cache_hit_tokens +
                    ", prompt_cache_miss_tokens=" + prompt_cache_miss_tokens +
                    '}';
        }
    }

    public static class Choice {
        private int index;
        private ChatMessage delta;
        private ChatMessage message;
        private String finish_reason;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public ChatMessage getDelta() { return delta; }
        public void setDelta(ChatMessage delta) { this.delta = delta; }
        public ChatMessage getMessage() { return message; }
        public void setMessage(ChatMessage message) { this.message = message; }
        public String getFinish_reason() { return finish_reason; }
        public void setFinish_reason(String finish_reason) { this.finish_reason = finish_reason; }

        @Override
        public String toString() {
            return "Choice{" +
                    "index=" + index +
                    ", delta=" + delta +
                    ", message=" + message +
                    ", finish_reason='" + finish_reason + '\'' +
                    '}';
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }
    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    @Override
    public String toString() {
        return "ChatResponse{" +
                "id='" + id + '\'' +
                ", object='" + object + '\'' +
                ", created=" + created +
                ", model='" + model + '\'' +
                ", choices=" + choices +
                ", usage=" + usage +
                '}';
    }
}
