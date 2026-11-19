package com.xingji.mapper;

import com.xingji.pojo.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    /** 新建会话,回填自增 id */
    int insert(ChatSession session);

    /** 按主键查会话(发消息前先确认会话存在且属于当前用户) */
    ChatSession findById(@Param("id") Integer id);

    /** 列出某用户的全部会话,最近活跃在前(每轮对话会刷新 update_time) */
    List<ChatSession> findByUserId(@Param("userId") Integer userId);

    /** 改会话标题(首轮对话可用用户第一句话兜底命名) */
    int updateTitle(@Param("id") Integer id, @Param("title") String title);

    /** 把合并后的旅行画像 JSON 写回 context;update_time 由数据库自动刷新 */
    int updateContext(@Param("id") Integer id, @Param("context") String context);

    /** 物理删除会话(会话不进回收站;调用前应先清掉其消息) */
    int deleteSession(@Param("id") Integer id);
}
