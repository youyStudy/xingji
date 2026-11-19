package com.xingji.mapper;

import com.xingji.pojo.ItineraryDay;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ItineraryDayMapper {

    int insert(ItineraryDay day);

    List<ItineraryDay> findByItineraryId(@Param("itineraryId") Integer itineraryId);

    int deleteByItineraryId(@Param("itineraryId") Integer itineraryId);

    /** 按主键查单天(编辑时校验归属用) */
    ItineraryDay findById(@Param("id") Integer id);

    /** 更新某天的 day_index / theme / date */
    int update(ItineraryDay day);

    /** 物理删除单天(行程天不进回收站) */
    int deleteById(@Param("id") Integer id);
}