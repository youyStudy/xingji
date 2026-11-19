package com.xingji.mapper;

import com.xingji.pojo.Itinerary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ItineraryMapper {

    int insert(Itinerary itinerary);

    Itinerary findById(@Param("id") Integer id);

    List<Itinerary> findByUserId(@Param("userId") Integer userId);

    int softDelete(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 更新行程主体字段:标题/出发日期/天数/预算(不动 status/deleted/userId) */
    int update(Itinerary itinerary);

    /** 单独更新行程状态:校验完成后置 2(已校验)。只动 status,不碰其它字段 */
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /** 置顶/取消置顶(带归属校验,只动 pinned 一列) */
    int updatePinned(@Param("id") Integer id, @Param("userId") Integer userId, @Param("pinned") Integer pinned);

    /** 某会话下仍未删除的行程数(删行程时判断要不要连带删会话) */
    int countAliveBySessionId(@Param("sessionId") Integer sessionId);

    /** 把引用某会话的行程的 session_id 置空(删会话前解除外键引用,行程本身保留) */
    int clearSessionId(@Param("sessionId") Integer sessionId);
}