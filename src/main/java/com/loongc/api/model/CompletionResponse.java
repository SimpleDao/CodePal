package com.loongc.api.model;

import java.util.List;

/**
 * /v1/completions 接口响应体（流式每帧 / 非流式完整）
 * @author 水龙吟
 * @date 2026-05-24
 */
public class CompletionResponse {
    private String         id;
    private String         object;
    private List<Choice>   choices;

    public String       getId()      { return id; }
    public String       getObject()  { return object; }
    public List<Choice> getChoices() { return choices; }

    public static class Choice {
        private String text;
        private String finish_reason;

        /** 流式帧的增量文本 */
        public String getText()          { return text; }
        public String getFinish_reason() { return finish_reason; }
    }
}
