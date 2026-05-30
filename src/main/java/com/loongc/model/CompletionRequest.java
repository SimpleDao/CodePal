package com.loongc.model;

import java.util.List;

/**
 * /v1/completions 接口请求体
 * 用于 FIM (Fill-In-the-Middle) 代码补全
 * @author 水龙吟
 * @date 2026-05-24
 */
public class CompletionRequest {
    private String       model;
    /** FIM 格式的完整 prompt：<|fim_prefix|>...before...<|fim_suffix|>...after...<|fim_middle|> */
    private String       prompt;
    private boolean      stream = true;
    private Integer      max_tokens;
    private Double       temperature;
    private Boolean logRequests;
    private Boolean logResponses;
    /** 停止 token，防止模型输出超出补全范围 */
    private List<String> stop;

    public String getModel()                   { return model; }
    public void   setModel(String model)       { this.model = model; }

    public String getPrompt()                  { return prompt; }
    public void   setPrompt(String prompt)     { this.prompt = prompt; }

    public boolean isStream()                  { return stream; }
    public void    setStream(boolean stream)   { this.stream = stream; }

    public Integer getMax_tokens()             { return max_tokens; }
    public void    setMax_tokens(Integer v)    { this.max_tokens = v; }

    public Double getTemperature()             { return temperature; }
    public void   setTemperature(Double v)     { this.temperature = v; }

    public List<String> getStop()              { return stop; }
    public void         setStop(List<String> s){ this.stop = s; }

    public Boolean getLogRequests() {
        return logRequests;
    }

    public void setLogRequests(Boolean logRequests) {
        this.logRequests = logRequests;
    }

    public Boolean getLogResponses() {
        return logResponses;
    }

    public void setLogResponses(Boolean logResponses) {
        this.logResponses = logResponses;
    }
}
