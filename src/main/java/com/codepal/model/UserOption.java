package com.codepal.model;

public class UserOption {
    private String label;
    private String description;

    public UserOption() {}

    public UserOption(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
