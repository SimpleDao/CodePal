package com.codepal.enums;

public enum EnumsThinkingIntensity {

    Low("low"),
    Medium("medium"),
    High("high");

    private String code;

    EnumsThinkingIntensity(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static EnumsThinkingIntensity getByCode(String code) {
        for (EnumsThinkingIntensity t: EnumsThinkingIntensity.values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }

}
