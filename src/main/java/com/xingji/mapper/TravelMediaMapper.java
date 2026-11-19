package com.xingji.mapper;

import com.xingji.pojo.TravelMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TravelMediaMapper {

    int insert(TravelMedia media);

    List<TravelMedia> findByUserId(@Param("userId") Integer userId);

    TravelMedia findById(@Param("id") Integer id);

    int softDelete(@Param("id") Integer id);

    int restore(@Param("id") Integer id);

    List<TravelMedia> findDeletedByUserId(@Param("userId") Integer userId);

    /** 定时清理用:查出软删除且回收站里待够天数(update_time 早于 cutoff)的素材 */
    List<TravelMedia> findStaleDeleted(@Param("cutoffTime") java.time.LocalDateTime cutoffTime);

    /** 物理删除一行(定时清理,连库带文件一起清) */
    int hardDeleteById(@Param("id") Integer id);
}