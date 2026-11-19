package com.xingji.mapper;

import com.xingji.pojo.RouteSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RouteSegmentMapper {

    int insert(RouteSegment segment);

    /** 某行程的全部路线段（按 id 升序，即写入顺序） */
    List<RouteSegment> findByItineraryId(@Param("itineraryId") Integer itineraryId);

    /** 重算前清空该行程旧段 */
    int deleteByItineraryId(@Param("itineraryId") Integer itineraryId);
}
