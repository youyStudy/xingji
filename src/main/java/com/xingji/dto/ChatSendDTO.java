package com.xingji.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发送一条对话消息的入参 DTO。
 * 会话必须已存在(sessionId 非空);content 是本轮用户说的话。
 * 不含 userId:登录态从 UserContext 取,防越权往别人会话里塞消息。
 */
@Data
public class ChatSendDTO {

    @NotNull(message = "会话id不能为空")
    private Integer sessionId;   // 目标会话

    @NotBlank(message = "消息内容不能为空")
    private String content;      // 本轮用户输入

    /** 用户表单里已填的旅行画像,可空;注入对话上下文,已填项不重复追问 */
    private ChatProfileDTO profile;
}
