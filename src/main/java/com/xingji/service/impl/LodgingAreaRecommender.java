package com.xingji.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.LLM.AiTask;
import com.xingji.LLM.LlmClient;
import com.xingji.LLM.PromptLoader;
import com.xingji.map.AmapClient;
import com.xingji.pojo.Place;
import com.xingji.vo.LodgingAreaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 住宿区域推荐:只给"住哪一带",不给具体酒店。
 *
 * 设计要点(按用户明确的取舍):
 *  ① 不推荐具体酒店 —— 住宿高度个性化,推荐到店容易踩雷;让用户拿区域名自己上高德订。
 *  ② 不编"利与弊" —— "这里安静""那边吵"这类形容 AI 无法证伪,属于编造。
 *     只给区域名,把判断权交给用户。
 *  ③ 区域必须真实存在 —— AI 给的名字逐个过高德核验,查不到的直接丢弃,
 *     并把查到的坐标一并带出去(地图打点、算离景点多远都用得上)。
 *
 * 与景点/餐厅是同一套架构:AI 提名 → 代码验真 → 只把真东西给用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LodgingAreaRecommender {

    /** 城市名直接当区域推荐没意义,直接否决 */
    private static final int MAX_AREAS = 3;

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final AmapClient amapClient;
    private final ObjectMapper objectMapper;

    /**
     * 给某城市推荐 3 个真实存在的住宿区域。
     *
     * @param city        目的地城市
     * @param preferences 用户偏好(可空),仅用于让模型优先挑贴合的区域;不影响验真
     * @return 已通过高德核验的区域列表(可能少于 3 个,甚至为空);绝不抛异常
     */
    public List<LodgingAreaVO> recommend(String city, List<String> preferences) {
        if (city == null || city.isBlank()) return List.of();
        List<String> names = askModel(city, preferences);
        if (names.isEmpty()) return List.of();

        List<LodgingAreaVO> out = new ArrayList<>();
        for (String name : names) {
            if (out.size() >= MAX_AREAS) break;
            if (name == null || name.isBlank()) continue;
            Place real = amapClient.resolveArea(name, city);
            if (real == null) {
                // AI 编的、或者高德查不到的:直接丢弃。宁可少给,不给假区域。
                log.info("住宿区域未通过高德核验,已丢弃: {}", name);
                continue;
            }
            LodgingAreaVO v = new LodgingAreaVO();
            v.setName(real.getName());        // 用高德返回的规范名,不用模型手打的
            v.setAmapPoiId(real.getAmapPoiId());
            v.setLng(real.getLongitude());
            v.setLat(real.getLatitude());
            out.add(v);
        }
        return out;
    }

    /** 让模型提名区域名;失败或解析不出返回空列表 */
    private List<String> askModel(String city, List<String> preferences) {
        try {
            // 用专门的住宿提示词:它明确禁止推荐具体酒店、禁止写特点描述
            String system = promptLoader.getLodgingAreaPrompt();
            StringBuilder user = new StringBuilder();
            user.append("城市:").append(city)
                .append(";任务:推荐适合住宿的区域名,只要区域名不要酒店名;返回3个");
            if (preferences != null && !preferences.isEmpty()) {
                user.append(";用户偏好:").append(String.join("、", preferences));
            }
            String raw = llmClient.chat(AiTask.SPOT_RECOMMEND, system, user.toString());
            return parseNames(raw);
        } catch (Exception e) {
            log.warn("住宿区域推荐失败 city={}: {}", city, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从模型输出里抠出区域名数组。
     * 兼容 ["春熙路","宽窄巷子"] 和 {"areas":[...]} 两种写法,以及带围栏的输出。
     */
    private List<String> parseNames(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = com.xingji.util.JsonRepair.extractAndRepair(raw);
        if (json == null) {
            // 不是 JSON 就按行/逗号硬拆,至少别把结果全丢了
            return splitLoose(raw);
        }
        try {
            List<String> names = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode arr = node.isArray() ? node
                    : node.path("areas");
            if (arr == null || !arr.isArray()) return List.of();
            for (com.fasterxml.jackson.databind.JsonNode e : arr) {
                String v = e.isTextual() ? e.asText() : e.path("name").asText(null);
                if (v != null && !v.isBlank()) names.add(v.trim());
            }
            return names;
        } catch (Exception e) {
            return splitLoose(raw);
        }
    }

    /** 兜底:按换行/逗号拆,去掉引号、括号和编号 */
    private List<String> splitLoose(String raw) {
        List<String> names = new ArrayList<>();
        for (String seg : raw.split("[\n,，、]")) {
            String v = stripDecorations(seg);
            if (!v.isEmpty() && v.length() <= 20) names.add(v);
        }
        return names;
    }

    /** 去掉引号、方括号、花括号和行首编号(逐字符处理，不用正则，避免转义错误) */
    private String stripDecorations(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x22 || c == 0x27 || c == 0x5B || c == 0x5D || c == 0x7B || c == 0x7D) continue; // " ' [ ] { }
            sb.append(c);
        }
        String v = sb.toString().trim();
        // 去掉开头的序号，如 "1." "2、" "3)"
        int i = 0;
        while (i < v.length() && (Character.isDigit(v.charAt(i)) || v.charAt(i) == 0x2E
                || v.charAt(i) == 0x3001 || v.charAt(i) == 0x29 || v.charAt(i) == 0x20)) i++;
        return v.substring(i).trim();
    }
}
