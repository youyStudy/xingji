package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteSegment {

    private Integer id;
    private Integer itineraryId; // 所属行程
    private Integer fromNodeId;  // 起点节点
    private Integer toNodeId;    // 终点节点
    private Integer distance;    // 距离(米)
    private Integer duration;    // 耗时(秒)
    private String polyline;     // 折线坐标串
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
