package com.xingji.dto;

/** 用户这句话的意图（模型判断，服务端校验；不确定就 UNKNOWN）。 */
public enum ChatIntent {
    EXPLORE,
    PLAN,
    DISCUSS,
    MODIFY,
    CONFIRM,
    SMALL_TALK,
    OUT_OF_SCOPE,
    UNKNOWN;

    public static ChatIntent parse(String s) {
        if (s == null) return UNKNOWN;
        try {
            return ChatIntent.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}