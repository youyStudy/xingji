package com.xingji.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 编辑行程主体信息(标题/日期/天数/预算)的入参 DTO。
 * 只改"壳子"层面的字段,不动 day/node;那些走各自的编辑接口。
 * 不含 id/userId/status/deleted:id 走路径变量,其余后端管,防越权与状态伪造。
 */
@Data
public class ItineraryEditDTO {
    private String title;        // 行程标题,如"重庆三日游"
    private LocalDate startDate; // 出发日期,如 2026-10-01
    @Min(value = 1, message = "行程天数至少为 1 天")
    @Max(value = 30, message = "行程天数不能超过 30 天")
    private Integer days;        // 计划总天数,如 3
    @DecimalMin(value = "0", message = "预算不能为负数")
    private BigDecimal budget;   // 预算(元),如 2000.00
}
