package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程主表实体(itinerary 表的 Java 镜像)。
 * 一条行程的"骨架根节点":只描述这趟旅行本身(属于谁、叫什么、几天、多少预算、当前状态),
 * 具体的"每一天"在 ItineraryDay、"每天的活动"在 ItineraryNode 里,通过外键层层挂在这条行程下面。
 * 1 条 Itinerary : N 个 ItineraryDay : N 个 ItineraryNode。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Itinerary {

    private Integer id;
    private Integer userId;     // 所属用户(数据隔离):这条行程是谁的,查询/删除都按它校验越权
    private Integer sessionId;  // 来源会话:若由 AI 对话生成,记录关联的 chat_session.id;手动创建则为空
    private String title;       // 行程标题,如"重庆三日游"
    private String city;        // 目的地城市(生成时写入,校验时用来限定高德搜索范围)
    private LocalDate startDate;// 出发日期,如 2026-10-01
    private Integer days;       // 计划总天数,如 3
    private BigDecimal budget;  // 预算(元)
    private String advice;      // AI 生成的方案说明(JSON 文本):预算拆分/预约与风险提醒/备选方案,前端解析展示
    private Integer pinned;     // 置顶:0-否 1-是(列表接口按 pinned DESC, create_time DESC 排序)
    private Integer status;     // 行程进度:0-草稿(仅壳子) 1-已生成(AI填过内容) 2-已校验(约束校验过)
    private Integer deleted;    // 软删除:0-正常 1-已删除(进回收站,列表查询会过滤掉)
    private LocalDateTime createTime; // 记录创建时间(数据库自动填)
    private LocalDateTime updateTime; // 记录更新时间(数据库自动维护)
}
