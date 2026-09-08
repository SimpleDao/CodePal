package com.codepal.model;

import java.util.ArrayList;
import java.util.List;

public class UserQuestion {
    private String question;
    private List<UserOption> options;
    private boolean multiSelect;

    public UserQuestion() {
        this.options = new ArrayList<>();
        this.multiSelect = false;
    }

    public UserQuestion(String question, List<UserOption> options, boolean multiSelect) {
        this.question = question;
        this.options = options != null ? options : new ArrayList<>();
        this.multiSelect = multiSelect;
    }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<UserOption> getOptions() { return options; }
    public void setOptions(List<UserOption> options) { this.options = options; }
    public boolean isMultiSelect() { return multiSelect; }
    public void setMultiSelect(boolean multiSelect) { this.multiSelect = multiSelect; }
}
