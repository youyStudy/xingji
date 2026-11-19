package com.xingji.map;

import lombok.Data;

/** 高德路径规划一条方案的抽取结果（只留我们入库/展示要用的字段）。 */
@Data
public class RouteResult {

    private Integer distance; // 距离(米)
    private Integer duration; // 耗时(秒)
    private String polyline;  // 折线坐标串（GCJ-02，"lng,lat;lng,lat;..."）
}
