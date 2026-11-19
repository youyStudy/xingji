package com.xingji.vo;

import lombok.Data;

/** 路线段对外视图：附带起终点名称、可点的高德导航链接、所属第几天。 */
@Data
public class RouteSegmentVO {

    private Integer id;
    private Integer itineraryId;
    private Integer dayIndex;   // 属于第几天
    private Integer fromNodeId;
    private Integer toNodeId;
    private String fromName;
    private String toName;
    private Integer distance;   // 米
    private Integer duration;   // 秒
    private String polyline;    // GCJ-02 折线串
    private String navUrl;      // 高德导航链接
}
