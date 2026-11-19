package com.xingji.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * "校验行程"结果 VO(一次校验跑完后返回给前端的汇总,不落库)。
 * 前端据此高亮异常节点、展示约束提醒。
 */
@Data
public class ValidationResultVO {

    private Integer itineraryId;         // 被校验的行程 id
    private int total;                   // 本次参与校验的节点总数
    private int matched;                 // 高德匹配成功、已回填 placeId 的节点数
    private int abnormal;                // 匹配失败、被标 is_abnormal=1 的节点数
    private int suggested;               // 餐饮/住宿/交通等"建议项"数量(不做 POI 核验)
    private List<String> messages = new ArrayList<>(); // 所有问题/提醒的可读文案(异常地点、预算超支、时间重叠等)
}
