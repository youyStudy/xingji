package com.xingji.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.LLM.AiTask;
import com.xingji.LLM.LlmClient;
import com.xingji.LLM.PromptLoader;
import com.xingji.cache.AmapCache;
import com.xingji.dto.SpotRecommendDTO;
import com.xingji.map.AmapClient;
import com.xingji.service.PlaceService;
import com.xingji.vo.CityVO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceServiceImpl implements PlaceService {

    private final AmapClient amapClient;
    private final LlmClient llmClient;          // 底层按 AiTask 路由（mock 或真模型）
    private final PromptLoader promptLoader;    // 取各任务的 system 规则
    private final ObjectMapper objectMapper;
    private final AmapCache amapCache;

    // 城市清单基本不变，缓存 6h，避免每次开地球都烧一次高德配额；
    // 另存一份 7 天的 stale 副本，高德抖动时城市词表不开天窗
    private static final String CITY_KEY = "amap:cities";
    private static final String CITY_STALE_KEY = "amap:cities:stale";
    private static final TypeReference<List<CityVO>> CITY_LIST = new TypeReference<>() { };
    private static final Duration CITY_TTL = Duration.ofHours(6);
    private static final Duration CITY_STALE_TTL = Duration.ofDays(7);

    @Override
    public List<CityVO> listCities() {
        List<CityVO> cached = amapCache.get(CITY_KEY, CITY_LIST);
        if (cached != null && !cached.isEmpty()) {
            return cached;   // 命中缓存，直接给
        }
        try {
            List<CityVO> fresh = amapClient.listCities();
            amapCache.put(CITY_KEY, fresh, CITY_TTL);
            amapCache.put(CITY_STALE_KEY, fresh, CITY_STALE_TTL);
            return fresh;
        } catch (Exception e) {
            // 高德抖动(如 DNS 瞬断)时不让城市词表开天窗:有过期缓存就先用旧的,真没有才抛。
            // 词表空会导致前端识别不出目的地,整个"锚点优先"流程被跳过
            List<CityVO> stale = amapCache.get(CITY_STALE_KEY, CITY_LIST);
            if (stale != null && !stale.isEmpty()) {
                log.warn("高德城市清单刷新失败,先用过期缓存({} 条): {}", stale.size(), e.getMessage());
                return stale;
            }
            throw e;
        }
    }

    @Override
    public List<SpotVO> browseSpots(String city) {
        List<SpotVO> spots = amapClient.browseSpots(city);
        // 评分高者优先；无评分（高德对冷门点常空）排最后
        spots.sort(Comparator.comparingDouble(this::ratingOf).reversed());
        return spots;
    }

    @Override
    public List<SpotVO> smartRecommend(SpotRecommendDTO dto) {
        String city = dto.getCity();
        String pref = dto.getPreference() == null ? "" : dto.getPreference().trim();

        List<SpotVO> candidates = browseSpots(city);
        if (candidates.isEmpty()) return candidates;

        // 没给偏好：不烧 LLM，直接用高德数据补一句通用推荐理由后返回
        if (pref.isEmpty()) {
            for (SpotVO s : candidates) s.setReason(genReason(s));
            return candidates;
        }

        // 有偏好：把候选喂给 LLM 挑选 + 写理由
        Map<String, String> reasonByName;
        try {
            String system = promptLoader.getPrompt(AiTask.SPOT_RECOMMEND);
            String user = buildUserPrompt(city, pref, candidates);
            String raw = llmClient.chat(AiTask.SPOT_RECOMMEND, system, user);
            reasonByName = parseReasons(extractJsonArray(raw));
        } catch (Exception e) {
            log.warn("智能推荐 LLM 调用/解析失败，退回按评分排序：{}", e.getMessage());
            for (SpotVO s : candidates) s.setReason(genReason(s));
            return candidates;
        }

        if (reasonByName.isEmpty()) {
            for (SpotVO s : candidates) s.setReason(genReason(s));
            return candidates;
        }

        // 命中 LLM 挑选的排前面、带 AI 理由；其余补通用理由跟在后面
        List<SpotVO> picked = new ArrayList<>();
        List<SpotVO> rest = new ArrayList<>();
        for (SpotVO s : candidates) {
            String r = reasonByName.get(s.getName());
            if (r != null && !r.isBlank()) {
                s.setReason(r);
                picked.add(s);
            } else {
                s.setReason(genReason(s));
                rest.add(s);
            }
        }
        picked.addAll(rest);
        return picked;
    }

    @Override
    public String generateInspiration(String city, List<String> spots) {
        String spotText = (spots == null || spots.isEmpty())
                ? "（暂无指定景点）"
                : String.join("、", spots);
        String user = "城市：" + (city == null ? "" : city) + "；可写进文案的真实景点：" + spotText;
        String system = promptLoader.getPrompt(AiTask.INSPIRATION);
        return llmClient.chat(AiTask.INSPIRATION, system, user);
    }

    // ============ 内部小工具 ============

    /** 评分字符串 → double；空/非数字给 -1（排最后） */
    private double ratingOf(SpotVO s) {
        try {
            return s.getRating() == null || s.getRating().isBlank()
                    ? -1 : Double.parseDouble(s.getRating().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 给 LLM 的候选清单（最多 15 条，控制 token），每行一条精简属性 */
    private String buildUserPrompt(String city, String pref, List<SpotVO> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("城市：").append(city).append("\n");
        sb.append("用户偏好：").append(pref).append("\n");
        sb.append("候选景点（只能从下列 name 中选，输出用完全相同的 name）：\n");
        int limit = Math.min(15, candidates.size());
        sb.append("[\n");
        for (int i = 0; i < limit; i++) {
            SpotVO s = candidates.get(i);
            sb.append("  {\"name\":\"").append(safe(s.getName()))
              .append("\",\"rating\":\"").append(safe(s.getRating()))
              .append("\",\"cost\":\"").append(safe(s.getCost()))
              .append("\",\"openTime\":\"").append(safe(s.getOpenTime())).append("\"}");
            if (i < limit - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 去掉可能包裹的代码围栏/前后废话，截出第一个 [ 到最后一个 ] 的 JSON 数组 */
    private String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        int l = raw.indexOf('[');
        int r = raw.lastIndexOf(']');
        if (l >= 0 && r > l) return raw.substring(l, r + 1);
        return "[]";
    }

    /** 解析 LLM 返回的 [{name,reason}] 成 name→reason 映射 */
    private Map<String, String> parseReasons(String json) {
        Map<String, String> map = new HashMap<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return map;
            for (JsonNode n : arr) {
                String name = n.path("name").asText("").trim();
                String reason = n.path("reason").asText("").trim();
                if (!name.isEmpty() && !reason.isEmpty()) map.put(name, reason);
            }
        } catch (Exception e) {
            log.warn("解析推荐理由 JSON 失败：{}", e.getMessage());
        }
        return map;
    }

    /** 兜底推荐理由：完全基于该景点的高德真实属性，不依赖 LLM */
    private String genReason(SpotVO s) {
        StringBuilder sb = new StringBuilder();
        double r = ratingOf(s);
        if (r > 0) sb.append("高德评分 ").append(s.getRating()).append("，");
        if (s.getOpenTime() != null && !s.getOpenTime().isBlank())
            sb.append("开放 ").append(s.getOpenTime()).append("，");
        if (s.getCost() != null && !s.getCost().isBlank())
            sb.append("参考花费 ").append(s.getCost()).append("，");
        sb.append("是本城口碑不错的选择，顺路安排很合适。");
        return sb.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").trim();
    }
}