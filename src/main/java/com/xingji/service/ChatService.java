package com.xingji.service;

import com.xingji.dto.ChatSendDTO;
import com.xingji.pojo.ChatSession;
import com.xingji.vo.ChatSessionVO;
import com.xingji.vo.ChatTurnVO;

import java.util.List;

/**
 * 对话服务(UC02 / Phase 5)。多轮对话的"记忆"由后端维持:
 * 每轮把历史消息 + 服务端画像一起发给大模型,模型返回
 * {reply, profile_patch, action},服务端校验/合并/落库后再把回复存回。
 */
public interface ChatService {

    /** 新建会话,返回会话 id */
    Integer createSession(Integer userId, String title);

    /** 列出当前用户的会话 */
    List<ChatSession> listSessions(Integer userId);

    /** 取某会话详情(含全部消息、持久化画像与缺失项);越权访问他人会话会报错 */
    ChatSessionVO getSession(Integer sessionId, Integer userId);

    /**
     * 发一条消息:存 user 消息 → 读历史/画像 → 模型输出结构化结果
     * → 服务端校验合并 profile_patch → 写回 context → 存 assistant 消息。
     * 返回本轮回复、最新画像、缺失项与下一步动作。
     */
    ChatTurnVO send(Integer userId, ChatSendDTO dto);

    /** 删除会话(连带清空其消息) */
    void deleteSession(Integer sessionId, Integer userId);
}
