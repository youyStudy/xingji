package com.xingji.dto;

/** 本轮回复策略：服务端或模型建议，不改变用户看到的事实。 */
public enum ChatStrategy {
    /** 直接回答用户的问题 */
    ANSWER,
    /** 给建议 / 方案思路 */
    SUGGEST,
    /** 追问一个必要信息 */
    ASK,
    /** 继续讨论、比较、解释 */
    DISCUSS,
    /** 确认用户刚说的内容 */
    CONFIRM,
    /** 信息够了，提示可以生成 */
    COMPLETE;

    public static ChatStrategy parse(String s) {
        if (s == null) return null;
        try {
            return ChatStrategy.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}