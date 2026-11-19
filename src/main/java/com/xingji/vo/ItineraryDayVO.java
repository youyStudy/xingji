package com.xingji.vo;

import com.xingji.map.AmapNavigationBuilder;
import com.xingji.pojo.ItineraryDay;
import lombok.Data;

import java.util.List;

/**
 * 行程中"某一天"的输出 VO,挂在 ItineraryDetailVO.days 列表里。
 * 一天 = 天的基本信息 + 这天按顺序排列的活动节点列表 + 当天的导航路线。
 */
@Data
public class ItineraryDayVO {

    private ItineraryDay day;                 // 当天信息(第几天/主题/具体日期)
    private List<ItineraryNodeVO> nodes;      // 当天按 seq 排好序的节点(带坐标与导航链接)

    /**
     * 当天两点之间的分段导航(相邻有效节点按 seq 串起来)。
     * 只有前后两个节点都有真实坐标时才产生一段——缺坐标的那一段直接跳过,
     * 宁可少给一段路线,也不把用户导到一个猜出来的位置。
     */
    private List<AmapNavigationBuilder.RouteSegmentLink> routeLinks;
}
