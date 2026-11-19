package com.xingji.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 编辑"活动节点"的入参 DTO(前端改某天里某个活动时传的数据契约)。
 * 用于修改已有节点(PUT /node/{nodeId})、新增节点(POST /day/{dayId}/node)。
 * 不含 id/dayId:前者走路径变量、后者由 service 关联;
 * placeId 可空——生成阶段只有 placeName,校验阶段才回填高德 placeId。
 */
@Data
public class NodeEditDTO {
    private Integer placeId;     // 关联地点ID(Place表/高德POI),生成初期可空,校验后回填
    private String placeName;    // 地点名称,AI 或用户直接填的文字,校验前展示靠它
    private String nodeType;     // 节点类型:景点 / 餐饮 / 交通 / 住宿
    private Integer seq;         // 当天顺序号,1、2、3…,排当天先后
    private LocalTime startTime; // 计划开始时间,如 08:30
    private LocalTime endTime;   // 计划结束时间,如 10:00
    private BigDecimal cost;     // 本节点花费(元),BigDecimal 防浮点精度丢失
}
