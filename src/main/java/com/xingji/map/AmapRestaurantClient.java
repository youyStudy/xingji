package com.xingji.map;

import com.xingji.config.AmapProperties;
import com.xingji.dto.AmapPoi;
import com.xingji.dto.AmapSearchResponse;
import com.xingji.pojo.Place;
import com.xingji.util.GeoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 餐饮 POI 检索:给行程生成提供"真实存在的餐厅候选”。
 *
 * 解决什么问题:以前餐饮节点是模型编出来的描述(如"武侯祠附近的川菜馆"),
 * 这类节点没有 placeId、没有坐标,既不能导航也不能核验,用户到了当地根本找不到。
 * 现在改成:代码先按"景点位置 + 城市"去高德捞出真实餐厅,把候选交给模型挑选,
 * 模型从候选里选中的餐厅自然带真实坐标,餐饮节点也就可导航了。
 *
 * 实测要点:按 city 全局搜 types=050000 会混进酒店、度假村;
 * 带 keywords(景点名)做区域检索才准确 —— 返回的都是该景点周边的餐厅。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmapRestaurantClient {

    /** 高德 POI 分类码:餐饮服务 */
    private static final String TYPE_RESTAURANT = "050000";

    private final RestTemplate restTemplate;
    private final AmapProperties props;

    /**
     * 在某景点周边检索餐厅。
     *
     * @param keyword 景点/区域名,如"武侯祠"——带它才能搜到周边餐厅,只给 city 会返回全城混杂结果
     * @param city    城市名,限定范围
     * @param limit   最多返回几家
     * @return 真实餐厅(带坐标与人均);失败返回空列表,绝不抛异常拖垮生成链路
     */
    public List<Place> searchNearbyRestaurants(String keyword, String city, int limit) {
        if (city == null || city.isBlank()) return List.of();
        try {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromHttpUrl(props.getBaseUrl() + props.getPoiSearchPath())
                    .queryParam("types", TYPE_RESTAURANT)
                    .queryParam("city", city)
                    .queryParam("offset", Math.min(limit, 25))
                    .queryParam("page", 1)
                    .queryParam("extensions", "all")    // 拿 biz_ext 里的人均消费
                    .queryParam("sortrule", "weight")    // 按热度排,比默认更像"值得去"
                    .queryParam("key", props.getKey());
            if (keyword != null && !keyword.isBlank()) {
                b.queryParam("keywords", keyword);
            }

            AmapSearchResponse resp = restTemplate.getForObject(b.build().toUriString(), AmapSearchResponse.class);
            if (resp == null || !"1".equals(resp.getStatus()) || resp.getPois() == null) return List.of();

            List<Place> out = new ArrayList<>();
            for (AmapPoi poi : resp.getPois()) {
                if (out.size() >= limit) break;
                if (poi.getLocation() == null || poi.getLocation().isEmpty()) continue;
                if (poi.getName() == null || poi.getName().isBlank()) continue;
                // 过滤掉明显不是"吃饭的地方":实测 types=050000 会混进酒店、度假村、茶馆
                String type = poi.getType() == null ? "" : poi.getType();
                if (type.contains("住宿服务") || type.contains("宾馆") || type.contains("度假")) continue;
                if (type.contains("茶艺馆") || type.contains("咖啡馆") || type.contains("酒吧")) continue;

                String[] lnglat = poi.getLocation().split(",");
                double[] wgs = GeoUtil.gcj02ToWgs84(
                        Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
                Place p = new Place();
                p.setAmapPoiId(poi.getId());
                p.setName(poi.getName());
                p.setCategory(poi.getType());
                p.setAddress(poi.getAddress());
                p.setCity(poi.getCityname());
                p.setLongitude(BigDecimal.valueOf(wgs[0]));
                p.setLatitude(BigDecimal.valueOf(wgs[1]));
                out.add(p);
            }
            return out;
        } catch (Exception e) {
            // fail-safe:餐饮是锦上添花,取不到就让模型按原方式给名字,不能让整条生成失败
            log.warn("餐饮 POI 检索失败, keyword={}, city={}: {}", keyword, city, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从高德原始响应里取人均消费(元)。
     * 单独一个方法是因为它需要原始 AmapPoi,而 searchNearbyRestaurants 返回的是已落库的 Place。
     */
    public static String costOf(AmapPoi poi) {
        if (poi == null || poi.getBizExt() == null) return null;
        return poi.getBizExt().getCost();
    }
}
