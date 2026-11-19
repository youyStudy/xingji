package com.xingji.dto;

import com.xingji.vo.RestaurantCandidateVO;
import com.xingji.vo.SpotVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排器(ContextBuilder)收集好、准备交给 Planner LLM 的"规划上下文"。
 * 这是"确定性编排器 + 候选池"架构的核心数据结构:先由纯代码把真实数据(高德景点、天气…)备好,
 * 再让模型只在给定约束内做选择与组织,而不是让它凭空编造。
 * 之所以不复用请求 DTO 或实体:这一层是"喂给模型的原材料快照",既不是用户入参也不是落库结构。
 */
@Data
public class PlannerContext {

    /**
     * 真实景点候选池(全部来自高德,带 amapPoiId)。
     * 模型输出的"景点节点"poiId 必须落在这里,否则视为编造、直接拦截。
     */
    private List<SpotVO> attractions = new ArrayList<>();

    /**
     * 真实餐厅候选池(全部来自高德,带 amapPoiId 与坐标)。
     * 模型输出的"餐饮节点"据此挑选:选中候选池里的餐厅 → 该节点自动获得真实坐标,
     * 也就可导航、可定位。模型若仍自己编店名,该节点保持"未核验"状态,不会被当成真实地点展示。
     */
    private List<RestaurantCandidateVO> restaurants = new ArrayList<>();

    /**
     * 天气简报(编排器收集的第 2 个数据源)。形如"8月1日 晴 22~33℃；8月2日 多云转小雨 ...",
     * 拿不到就留空串——天气是锦上添花,失败绝不能拖垮整条生成链路。
     */
    private String weatherBrief = "";
}
