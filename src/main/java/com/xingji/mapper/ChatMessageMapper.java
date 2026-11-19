package com.xingji.mapper;

import com.xingji.pojo.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {

    /** 存一条消息(user 或 assistant),回填自增 id */
    int insert(ChatMessage message);

    /** 按会话取全部消息,时间从旧到新(拼多轮上下文靠这个顺序) */
    List<ChatMessage> findBySessionId(@Param("sessionId") Integer sessionId);

    /** 删除某会话下所有消息(会话删除时级联清理用) */
    int deleteBySessionId(@Param("sessionId") Integer sessionId);
}
