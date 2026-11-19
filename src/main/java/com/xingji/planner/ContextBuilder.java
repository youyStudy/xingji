package com.xingji.planner;

import com.xingji.dto.GenerateRequestDTO;
import com.xingji.dto.PlannerContext;
import com.xingji.map.AmapClient;
import com.xingji.map.AmapRestaurantClient;
import com.xingji.pojo.Place;
import com.xingji.util.GeoDistance;
import com.xingji.vo.RestaurantCandidateVO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性编排器:行程生成的"取数"阶段,纯代码、一次 LLM 都不调。
 * 职责边界——它只负责"去外部世界把真实素材捞回来、整理成 PlannerContext",
 * 决定"取哪些数据、并行还是串行、取不到怎么降级"这些调度逻辑,全在这里用代码写死,
 * 而不是把"下一步该调哪个工具"的循环控制权交给模型。模型只会在我们给好的候选池里做选择。
 *
 * 已接入两个数据源:景点候选池、天气简报;美食、酒店会作为第 3/4 个数据源顺势挂进 gather,
 * 结构不用改——这正是"先立骨架、再加数据源"的意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    /** 景点池上限:高德浏览接口一次拉 25 个,再多截断意义不大,还会把 prompt 撑肥;留 30 是余量。 */
    private static final int MAX_ATTRACTION_POOL = 30;

    /**
     * 取前几个景点作为"餐厅检索锚点"。每个锚点一次高德请求。
     * 6 个锚点 ≈ 6 次请求(并行成本很低,实测高德单次约 0.3 秒),
     * 但能显著扩大候选池,避免模型无菜可挑。
     */
    private static final int MAX_RESTAURANT_ANCHORS = 8;

    /**
     * 每个锚点取几家餐厅。
     * 给足选择:实测 3 天行程要 6 顿正餐,池子只有 7 家时模型会反复挑同一家,
     * 排出来的"行程"三天吃同一家店。
     */
    private static final int RESTAURANTS_PER_ANCHOR = 20;

    /**
     * 餐厅离锚点景点的最大距离(米)。超过就不进候选池。
     * 3 公里太严会把池子筛到没几家;5 公里是"同一天顺路吃个饭"的合理上限。
     */
    private static final double MAX_RESTAURANT_DISTANCE_M = 5000;

    private final AmapClient amapClient;
    private final AmapRestaurantClient amapRestaurantClient;

    /**
     * 收集规划上下文。当前阶段:拉真实景点候选池 + 预留天气位。
     * fail-safe:高德取数失败不抛异常,只记日志并返回空池——空池时上层会降级为"不强制校验ID",
     * 保证外部偶发抖动不至于让整个生成功能报错。
     */
    public PlannerContext gather(GenerateRequestDTO req) {
        PlannerContext ctx = new PlannerContext();

        // 数据源 1:真实景点候选池(复用已按热度排序、带评分/人均/开放时间的浏览结果)
        List<SpotVO> spots;
        try {
            spots = amapClient.browseSpots(req.getCity());
        } catch (Exception e) {
            log.warn("候选景点池获取失败, city={} —— 本次生成降级为不强制景点ID校验", req.getCity(), e);
            spots = List.of();
        }
        if (spots != null && spots.size() > MAX_ATTRACTION_POOL) {
            spots = spots.subList(0, MAX_ATTRACTION_POOL);
        }
        ctx.setAttractions(spots == null ? List.of() : spots);

        // 数据源 3:真实餐厅候选池。
        // 按"城市 + 景点名"做区域检索:实测只给城市会混进酒店/度假村,
        // 带上景点名才能拿到该景点周边的真实餐厅(它们才有坐标、才能导航)。
        // 只取前几个高热景点做锚点,避免为了一顿饭把高德调爆(每个锚点一次请求)。
        List<RestaurantCandidateVO> restaurants = new ArrayList<>();
        if (spots != null && !spots.isEmpty()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            int anchors = 0;
            for (SpotVO s : spots) {
                if (anchors >= MAX_RESTAURANT_ANCHORS) break;   // 只取前 N 个景点做锚点
                if (s.getName() == null || s.getName().isBlank()) continue;
                if (!seen.add(s.getName())) continue;
                anchors++;
                List<Place> found = amapRestaurantClient.searchNearbyRestaurants(
                        s.getName(), req.getCity(), RESTAURANTS_PER_ANCHOR);
                for (Place p : found) {
                    // 硬过滤:离锚点景点太远的不要。
                    // 实测高德带 keywords 检索也会返回"同名但远在另一个区"的店,
                    // 模型再把它排进当天路线就成了"武侯祠之后跑去二十公里外吃饭"。
                    if (p.getLatitude() != null && p.getLongitude() != null && s.getLat() != null && s.getLng() != null) {
                        double dist = GeoDistance.meters(s.getLat().doubleValue(), s.getLng().doubleValue(),
                                p.getLatitude().doubleValue(), p.getLongitude().doubleValue());
                        if (dist > MAX_RESTAURANT_DISTANCE_M) continue;
                    }
                    RestaurantCandidateVO c = new RestaurantCandidateVO();
                    c.setAmapPoiId(p.getAmapPoiId());
                    c.setName(p.getName());
                    c.setArea(s.getName());          // 记住这家在哪个景点附近,模型据此判断顺路
                    c.setCategory(p.getCategory());
                    c.setLng(p.getLongitude());
                    c.setLat(p.getLatitude());
                    restaurants.add(c);
                }
            }
        }
        log.info("餐饮候选池: {} 家真实餐厅(来自 {} 个景点锚点)", restaurants.size(),
                Math.min(spots == null ? 0 : spots.size(), MAX_RESTAURANT_ANCHORS));
        ctx.setRestaurants(restaurants);

        // 数据源 2:天气简报。weatherBriefForPrompt 内部已 fail-safe(出错返回空串),
        // 这里再包一层纯属保险——天气无论如何都不能影响景点池与后续生成。
        String weatherBrief = "";
        try {
            weatherBrief = amapClient.weatherBriefForPrompt(
                    req.getCity(), req.getStartDate(), req.getDays());
        } catch (Exception e) {
            log.warn("天气简报获取失败, city={} —— 本次生成忽略天气", req.getCity(), e);
        }
        ctx.setWeatherBrief(weatherBrief == null ? "" : weatherBrief);

        return ctx;
    }
}
