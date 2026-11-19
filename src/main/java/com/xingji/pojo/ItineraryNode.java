package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 行程"活动节点"实体(itinerary_node 表的 Java 镜像)。
 * 三层树的最叶子层:某一天里的一个具体活动(去某景点/吃饭/交通/住宿)。
 * 一个节点通过 dayId 挂在某天下面、通过 placeId 关联一个地点(Place/高德POI)。
 * 字段已含完整含义注释,直接看每个属性即可。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryNode {

    private Integer id;            // 主键，节点id
    private Integer dayId;         // 所属行程日（外键，关联行程日表的id）
    private Integer placeId;       // 关联地点ID，关联Place表（高德POI地点）
    private String placeName;
    private String nodeType;       // 节点类型：景点 / 餐饮 / 交通 / 住宿
    private Integer seq;           // 当天顺序号！用来排序，1、2、3、4，代表当天先去哪、后去哪
    private LocalTime startTime;   // 这个节点计划开始时间，例如 08:30
    private LocalTime endTime;     // 这个节点计划结束时间，例如 10:00
    private BigDecimal cost;       // 本节点花费，BigDecimal存金额（不要用double，浮点精度丢失）
    private Integer isAbnormal;    // 1=高德POI匹配失败异常；0=正常。比如地点搜不到、经纬度为空就标记异常
    private LocalDateTime createTime; // 这条记录创建时间
    private LocalDateTime updateTime; // 这条记录更新时间

}
