package com.xingji.vo;

import com.xingji.dto.ChatProfileDTO;
import com.xingji.pojo.ChatMessage;
import com.xingji.pojo.ChatSession;
import lombok.Data;

import java.util.List;

/**
 * 一个会话的完整详情 VO:会话主体 + 它下面的全部消息(按时间旧→新)
 * + 服务端持久化的旅行画像与缺失项。
 * 前端打开某会话时一次性拿到,用来回显对话气泡、恢复画像卡与待补充提示。
 */
@Data
public class ChatSessionVO {

    private ChatSession session;            // 会话主体(id/标题/用户等)
    private List<ChatMessage> messages;     // 该会话的消息,时间升序
    private ChatProfileDTO profile;         // 已确认画像(来自 chat_session.context)
    private List<String> missing;           // 服务端计算的缺失项
    private java.util.List<com.xingji.dto.TravelConstraintDTO> constraints; // 结构化约束
    private com.xingji.dto.ConversationStateDTO conversationState;         // 对话状态
}
