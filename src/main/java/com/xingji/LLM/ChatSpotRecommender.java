package com.xingji.LLM;

import com.xingji.map.AmapClient;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 对话里的"景点推荐"识别与取数。
 *
 * 解决什么问题:以前用户在对话里说"推荐些景点"，模型凭训练数据吐三个名字就完了
 * ——没有图片、没有评分、没有开放时间，甚至名字可能是编的。
 * 现在改成:识别出这类意图后，去高德拉该城市的**真实景点**（带照片/评分/门票/开放时间），
 * 由服务端精选前几个交给前端渲染成图文卡片，模型只写一句引导语。
 *
 * 识别策略与 ChatIntentRouter 一致:宁可放过。
 * 命中"推荐/好玩/景点/必去"等词，且句子里有城市，才认为是在要推荐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSpotRecommender {

    /**
     * 一次最多给几张卡片。
     * 用户问"推荐景点"时是想多看几个做取舍,5 个偏少(实测少了最出名的涠洲岛);
     * 8 个刚好两列四行,再多就把对话刷屏了。
     */
    private static final int MAX_CARDS = 8;

    private static final Pattern WANTS_SPOTS = Pattern.compile(
            "推荐|有什么好玩|有什么景点|必去|值得去|哪里好玩|去哪玩|逛逛|景点");

    private final AmapClient amapClient;
    private final com.xingji.service.PlaceService placeService;   // 拿真实城市清单做校验

    /** "去X玩/想去X" 这类句式里抽城市名;X 允许 2~6 个汉字 */
    private static final Pattern GO_TO_CITY = Pattern.compile(
            "去([\\u4e00-\\u9fff]{2,6}?)(?:玩|旅游|旅行|度假|逛逛|出差|看看|吃|待|呆|住)");
    private static final Pattern WANT_CITY = Pattern.compile("想去([\\u4e00-\\u9fff]{2,5})");

    /**
     * 从这句话里认出目的地城市,并用高德的真实城市清单校验,避免把"去海边玩"里的"海边"当城市。
     *
     * 为什么不用写死的城市表:系统里城市来自高德行政区划(约 340 个地级市),
     * 写死一份词表永远会漏(实测"北海"就不在常见城市枚举里)。
     *
     * @param content 用户这句话
     * @return 校验通过的城市名;认不出返回 null
     */
    public String detectCity(String content) {
        if (content == null || content.isBlank()) return null;
        String candidate = null;
        java.util.regex.Matcher m1 = GO_TO_CITY.matcher(content);
        if (m1.find()) {
            candidate = m1.group(1).trim();
        } else {
            java.util.regex.Matcher m2 = WANT_CITY.matcher(content);
            if (m2.find()) candidate = m2.group(1).trim();
        }
        if (candidate == null || candidate.isEmpty()) return null;
        return matchRealCity(candidate);
    }

    /**
     * 把候选词对齐到真实城市名。
     *
     * 关键:高德行政区划返回的名字带"市"后缀("北海市"),而用户口语句子里是"北海"。
     * 两边都要去掉行政区后缀再比,否则永远匹配不上。
     * 返回的是去掉后缀的干净名字(高德 POI 检索用"北海"比"北海市"命中率更高)。
     */
    private String matchRealCity(String candidate) {
        try {
            List<com.xingji.vo.CityVO> cities = placeService.listCities();
            if (cities == null || cities.isEmpty()) return null;
            String c = stripAdminSuffix(candidate);
            // ① 精确匹配(都去掉后缀后)
            for (com.xingji.vo.CityVO city : cities) {
                if (city.getName() == null) continue;
                if (stripAdminSuffix(city.getName()).equals(c)) return c;
            }
            // ② 包含匹配,处理"广西北海""北海涠洲岛"这类带前缀的说法
            for (com.xingji.vo.CityVO city : cities) {
                if (city.getName() == null) continue;
                String name = stripAdminSuffix(city.getName());
                if (!name.isEmpty() && (c.contains(name) || name.contains(c))) return name;
            }
        } catch (Exception e) {
            log.warn("城市名校验失败: {}", e.getMessage());
        }
        return null;
    }

    /** 去掉行政区后缀:"北海市"→"北海"、"广西壮族自治区"→"广西" */
    private String stripAdminSuffix(String s) {
        if (s == null) return "";
        return s.replaceAll("(省|市|自治区|特别行政区|地区|自治州|盟)$", "").trim();
    }

    /** 这句话是不是在要景点推荐 */
    public boolean wantsSpots(String content) {
        if (content == null || content.isBlank()) return false;
        return WANTS_SPOTS.matcher(content).find();
    }

    /**
     * 拉该城市的真实景点，按高德热度排序后取前 MAX_CARDS 个。
     * 失败返回空列表——景点卡片是锦上添花，绝不能因为高德抖动把整轮对话打死。
     */
    public List<SpotVO> fetch(String city) {
        return fetch(city, java.util.List.of());
    }

    /**
     * 带用户约束的版本：明确的硬约束在取数阶段就过滤，
     * 不把"海鲜""登山"这类明知违反约束的景点推给用户。
     */
    public List<SpotVO> fetch(String city, java.util.List<com.xingji.dto.TravelConstraintDTO> constraints) {
        if (city == null || city.isBlank()) return java.util.List.of();
        java.util.List<com.xingji.dto.TravelConstraintDTO> active =
                com.xingji.chat.ConstraintExtractor.active(constraints);
        boolean noSeafood = false;
        boolean noHiking = false;
        boolean noShopping = false;
        for (com.xingji.dto.TravelConstraintDTO c : active) {
            String text = c.getContent() == null ? "" : c.getContent();
            if (com.xingji.chat.ConstraintExtractor.mentionsSeafood(text)) noSeafood = true;
            if (com.xingji.chat.ConstraintExtractor.mentionsHiking(text)) noHiking = true;
            if (com.xingji.chat.ConstraintExtractor.mentionsShopping(text)) noShopping = true;
        }
        try {
            List<SpotVO> all = amapClient.browseSpots(city);
            if (all == null || all.isEmpty()) return List.of();

            // 按评分从高到低排(高德回来的顺序是热度,但同质条目会挤在一起)
            List<SpotVO> sorted = new ArrayList<>(all);
            sorted.sort((a, b) -> Double.compare(parseRating(b.getRating()), parseRating(a.getRating())));

            List<SpotVO> out = new ArrayList<>();
            List<String> picked = new ArrayList<>();
            for (SpotVO s : sorted) {
                if (out.size() >= MAX_CARDS) break;
                if (s.getName() == null || s.getName().isBlank()) continue;
                if (violates(s.getName(), noSeafood, noHiking, noShopping)) continue;
                // 必须有图才做成卡片:没图的卡片在视觉上是空的，不如不显示
                if (s.getPhoto() == null || s.getPhoto().isBlank()) continue;
                // 同质去重:实测搜"北海"前 5 条里有 3 条是银滩的不同分区
                // (银滩国家旅游度假区 / 银滩欢乐港湾区 / 银滩银沙逐浪区),
                // 直接取前 5 张卡片会全是同一片海滩。这里用名字的核心词判断是否已覆盖。
                if (isCovered(s.getName(), picked)) continue;
                picked.add(s.getName());
                out.add(s);
            }
            // 全是无图数据时退而求其次:至少把名字给出去（仍然遵守硬约束）
            if (out.isEmpty()) {
                for (SpotVO s : all) {
                    if (out.size() >= MAX_CARDS) break;
                    if (s.getName() == null || s.getName().isBlank()) continue;
                    if (violates(s.getName(), noSeafood, noHiking, noShopping)) continue;
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("对话景点检索失败 city={}: {}", city, e.getMessage());
            return List.of();
        }
    }

    private boolean violates(String name, boolean noSeafood, boolean noHiking, boolean noShopping) {
        if (noSeafood && com.xingji.chat.ConstraintExtractor.isSeafoodName(name)) return true;
        if (noHiking && com.xingji.chat.ConstraintExtractor.isHikingName(name)) return true;
        return noShopping && com.xingji.chat.ConstraintExtractor.isShoppingName(name);
    }

    /**
     * 判断这个名字是否已被已选景点"覆盖"。
     *
     * 规则:取候选名的前 3 个字作为核心词(中文景点名的辨识部分通常在前),
     * 若已选集合里存在一个名字**包含**这个核心词,就认为同质(如多个"北海银滩…")。
     * 宁可少给几张卡片,也不让 5 张卡片都长一个样。
     */
    private boolean isCovered(String name, List<String> picked) {
        String n = name.replaceAll("[\\s·・（）()]", "");
        if (n.length() < 3) {
            for (String p : picked) {
                if (p.contains(n)) return true;
            }
            return false;
        }
        String core = n.substring(0, 3);
        for (String p : picked) {
            if (p.contains(core)) return true;
        }
        return false;
    }

    /** "4.8" -> 4.8;解析不出返回 0(排到最后) */
    private double parseRating(String r) {
        if (r == null || r.isBlank()) return 0;
        try {
            return Double.parseDouble(r.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}