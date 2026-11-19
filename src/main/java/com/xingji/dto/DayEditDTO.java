package com.xingji.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 编辑"某一天"的入参 DTO(前端改某天信息时传的数据契约)。
 * 用于两个场景:修改已有某天(PUT /day/{dayId})、新增某天(POST /{id}/day)。
 * 不含 id/itineraryId:前者走路径变量、后者由 service 关联,防前端伪造归属。
 */
@Data
public class DayEditDTO {
    private Integer dayIndex;    // 第几天:从 1 开始的序号,决定展示顺序 Day 1/2/3
    private String theme;        // 当天主题,如"解放碑-洪崖洞线",可空
    private LocalDate date;      // 具体日期,如 2026-10-01,可空(允许还没定死日期)
}
