package com.codepal.common;

public class StringUtils {

    public static boolean isBlank(String object){
        return object == null || object.isEmpty();
    }

    public static boolean isNotBlank(String object){
        return !isBlank(object);
    }
}
