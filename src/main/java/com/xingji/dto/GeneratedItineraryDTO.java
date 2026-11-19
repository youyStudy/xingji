package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 大模型"生成行程"返回结果的解析目标(入站数据契约,不是数据库实体)。
 * 描述一棵"还没落库的行程草稿树":模型按我们要求的 JSON 格式吐出来,先用这个类接住。
 * 之所以不直接用 Itinerary/Day/Node 实体来接:模型给的东西和表结构不是一回事
 * (它只能给地名 placeName,给不了 placeId),中间隔这一层翻译,以后换模型只改这里、不动实体。
 */
@Data
public class GeneratedItineraryDTO {

    private String title;                 // 模型顺便起的行程名,如"重庆经典三日游"
    private List<GeneratedDay> days;      // 模型规划出的每一天
    private GeneratedAdvice advice;       // 方案说明(预算拆分/预约与风险提醒/备选方案),可空

    /**
     * 模型返回的"某一天"。
     */
    @Data
    public static class GeneratedDay {
        private Integer dayIndex;          // 第几天,从 1 开始
        private String theme;              // 当天主题,如"解放碑-洪崖洞线"
        private List<GeneratedNode> nodes; // 当天的活动节点
    }

    /**
     * 模型返回的"方案说明"。day/node 结构承载不了的行程级信息都在这里,
     * 落库时整体序列化成 JSON 文本存 itinerary.advice,前端解析分块展示。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeneratedAdvice {
        private String budgetPlan;   // 预算拆分:按交通/住宿/餐饮/门票/弹性给出金额或占比
        private String reminders;    // 预约与风险提醒:需提前预约的、周一闭馆、排队高峰、天气影响
        private String backups;      // 备选方案:下雨怎么办、约不上怎么办、太累怎么替换
    }

    /**
     * 模型返回的"一个活动节点"。
     */
    @Data
    public static class GeneratedNode {
        private String nodeType;    // 节点类型:景点/餐饮/交通/住宿
        private String poiId;       // 模型从"可选景点池"中选中的高德 POI ID;餐饮/住宿/交通或编造景点会为空/非法,由服务层据此回填真实名称并拦截越界
        private String placeName;   // 模型给的地名(如"洪崖洞");注意是名字不是 placeId,落库前再去高德查转成 placeId
        private String startTime;   // 建议开始时间,原始字符串如"09:00";这里故意用 String,转 LocalTime 放到落库那步做
        private String endTime;     // 建议结束时间,如"11:00"
        private BigDecimal cost;    // 模型估算的花费(元),可空
        private String suggestion;  // 一句话玩法/提示,如"傍晚去看夜景最佳"
    }
}