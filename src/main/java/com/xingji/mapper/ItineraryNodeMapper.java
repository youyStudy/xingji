package com.xingji.mapper;

import com.xingji.pojo.ItineraryNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ItineraryNodeMapper {

    int insert(ItineraryNode node);

    List<ItineraryNode> findByDayId(@Param("dayId") Integer dayId);

    int deleteByDayId(@Param("dayId") Integer dayId);

    /** 按主键查单个节点(编辑/校验时校验归属用) */
    ItineraryNode findById(@Param("id") Integer id);

    /** 更新节点字段:地点、类型、顺序、时间、花费、高德匹配结果 */
    int update(ItineraryNode node);

    /** 物理删除单节点 */
    int deleteById(@Param("id") Integer id);

    /** 一次取出整条行程的全部节点(校验器用,避免三层循环)。join itinerary_day 过滤行程 */
    List<ItineraryNode> findByItineraryId(@Param("itineraryId") Integer itineraryId);
}