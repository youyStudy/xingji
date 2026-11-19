package com.xingji.mapper;

import com.xingji.pojo.Place;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlaceMapper {

    /** 新增地点(高德POI缓存),回填自增 id */
    int insert(Place place);

    /** 按主键查地点 */
    Place findById(@Param("id") Integer id);

    /** 校验回填时按高德POI id 去重:已缓存过就直接复用,不重复插入 */
    Place findByAmapPoiId(@Param("amapPoiId") String amapPoiId);
}
