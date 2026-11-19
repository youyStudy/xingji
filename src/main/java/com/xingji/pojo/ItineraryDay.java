package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程"某一天"实体(itinerary_day 表的 Java 镜像)。
 * 是 Itinerary 和 ItineraryNode 之间的中间层:一条行程按天数拆成若干"天",
 * 每天再挂若干活动节点。它只存"这是第几天/主题/对应日期",当天具体干什么由节点表负责。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryDay {

    private Integer id;
    private Integer itineraryId; // 所属行程:外键,关联 itinerary.id
    private Integer dayIndex;    // 第几天:从 1 开始的序号,排序和展示"Day 1/2/3"靠它
    private String theme;        // 当天主题:如"解放碑-洪崖洞线",可空
    private LocalDate date;      // 具体日期:如 2026-10-01,由出发日期推算
    private LocalDateTime createTime; // 记录创建时间(数据库自动填)
    private LocalDateTime updateTime; // 记录更新时间(数据库自动维护)
}
