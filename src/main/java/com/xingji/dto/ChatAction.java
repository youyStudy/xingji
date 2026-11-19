package com.xingji.dto;

/** 服务端最终动作：信息齐全不等于用户授权生成。 */
public enum ChatAction {
    CHAT,
    READY,
    GENERATE;

    public String jsonValue() {
        return name().toLowerCase();
    }
}