package com.codepal.model;

import java.util.List;

/**
 * /v1/completions 接口请求体
 * 用于 FIM (Fill-In-the-Middle) 代码补全
 * @author 水龙吟
 * @date 2026-05-24
 */
public class CompletionRequest {
    private String       model;
    /** FIM 前缀（光标前代码）。FIM 标记由服务端负责插入，客户端不要手工拼接 */
    private String       prompt;
    /** FIM 后缀（光标后代码，可选）。DeepSeek beta FIM 要求 prompt/suffix 分开传参 */
    private String       suffix;
    private boolean      stream = true;
    private Integer      max_tokens;
    private Double       temperature;
    /** 停止 token，防止模型输出超出补全范围 */
    private List<String> stop;

    public String getModel()                   { return model; }
    public void   setModel(String model)       { this.model = model; }

    public String getPrompt()                  { return prompt; }
    public void   setPrompt(String prompt)     { this.prompt = prompt; }

    public String getSuffix()                  { return suffix; }
    public void   setSuffix(String suffix)     { this.suffix = suffix; }

    public boolean isStream()                  { return stream; }
    public void    setStream(boolean stream)   { this.stream = stream; }

    public Integer getMax_tokens()             { return max_tokens; }
    public void    setMax_tokens(Integer v)    { this.max_tokens = v; }

    public Double getTemperature()             { return temperature; }
    public void   setTemperature(Double v)     { this.temperature = v; }

    public List<String> getStop()              { return stop; }
    public void         setStop(List<String> s){ this.stop = s; }
}
