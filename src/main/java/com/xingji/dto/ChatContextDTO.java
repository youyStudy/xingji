package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * chat_session.context 的存储结构。
 * v2 起不再是裸 ChatProfileDTO：把"用户确认的旅行事实"和"对话过程状态"分开，
 * 读取时能识别旧的裸画像并惰性迁移。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatContextDTO {

    public static final int CURRENT_VERSION = 2;

    private Integer version = CURRENT_VERSION;
    private ChatProfileDTO profile = new ChatProfileDTO();
    private ConversationStateDTO conversationState = new ConversationStateDTO();
}