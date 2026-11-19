package com.xingji.planner;

import com.xingji.dto.GenerateRequestDTO;
import com.xingji.dto.GeneratedItineraryDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 生成结果的"硬约束兜底"(代码层,不依赖模型自觉)。
 *
 * 为什么必须有:系统提示词里写了"末日晚于返程-150 分钟不许排""住宿节点数=晚数""每天午餐/晚餐各一次",
 * 但 GLM-4-Flash / R1-8B 这类小模型经常只看一半——实测出现过返程当天 14:00 还排住宿、一天两顿午餐、
 * 住宿 3 个但只住 2 晚。提示词是"建议",这里是"执行":违规节点直接丢弃,再落库。
 *
 * 规则与 prompt 一一对应,任何一条未来调整时两边要一起改。
 */
@Slf4j
@Component
public class ItineraryConstraintEnforcer {

    /** 到达缓冲:落地后 90 分钟内不排景点(接驳+入住) —— 与 prompt 规则 11 一致 */
    private static final int ARRIVAL_BUFFER_MIN = 90;
    /** 返程缓冲:末日活动必须早于"返程时刻-150 分钟"结束 —— 与 prompt 规则 11 一致 */
    private static final int DEPARTURE_BUFFER_MIN = 150;

    private static final LocalTime LUNCH_FROM = LocalTime.of(10, 30);
    private static final LocalTime LUNCH_TO = LocalTime.of(15, 0);
    private static final LocalTime DINNER_FROM = LocalTime.of(16, 30);
    private static final LocalTime DINNER_TO = LocalTime.of(22, 0);

    /**
     * 原地裁剪模型给的行程树,返回被丢弃的节点数(用于日志与排查)。
     * 只做"删",不改内容;补内容(回填高分景点)属于后续迭代。
     */
    public int enforce(GeneratedItineraryDTO gen, GenerateRequestDTO req) {
        if (gen == null || gen.getDays() == null || gen.getDays().isEmpty()) return 0;
        if (req == null) req = new GenerateRequestDTO();

        List<GeneratedItineraryDTO.GeneratedDay> days = gen.getDays();
        days.sort((a, b) -> Integer.compare(nz(a.getDayIndex()), nz(b.getDayIndex())));

        int lastDayIndex = req.getDays() != null && req.getDays() > 0
                ? req.getDays()
                : nz(days.get(days.size() - 1).getDayIndex());
        int maxAttractions = maxAttractionsPerDay(req.getPace());
        int nights = nights(req);

        LocalTime arriveTime = req.getArrival() != null && req.getArrival().getDatetime() != null
                ? req.getArrival().getDatetime().toLocalTime() : null;
        LocalTime leaveTime = req.getDeparture() != null && req.getDeparture().getDatetime() != null
                ? req.getDeparture().getDatetime().toLocalTime() : null;

        Set<String> usedAttractions = new HashSet<>();   // 跨天景点去重
        int lodgingUsed = 0;
        int dropped = 0;

        for (GeneratedItineraryDTO.GeneratedDay day : days) {
            if (day.getNodes() == null) continue;
            int dayIndex = nz(day.getDayIndex());
            boolean firstDay = dayIndex == 1;
            boolean lastDay = dayIndex == lastDayIndex;

            day.getNodes().sort((a, b) -> {
                LocalTime ta = parseTime(a.getStartTime());
                LocalTime tb = parseTime(b.getStartTime());
                if (ta == null && tb == null) return 0;
                if (ta == null) return 1;
                if (tb == null) return -1;
                return ta.compareTo(tb);
            });

            List<GeneratedItineraryDTO.GeneratedNode> kept = new ArrayList<>();
            int attractionsToday = 0;
            boolean lunchUsed = false;
            boolean dinnerUsed = false;
            boolean lodgingToday = false;

            for (GeneratedItineraryDTO.GeneratedNode n : day.getNodes()) {
                String type = n.getNodeType() == null ? "" : n.getNodeType().trim();
                LocalTime st = parseTime(n.getStartTime());

                // ① 首日:到达前不排任何节点(人还在路上)
                LocalTime firstLimit = arriveTime == null ? null
                        : ("景点".equals(type) ? arriveTime.plusMinutes(ARRIVAL_BUFFER_MIN) : arriveTime);
                if (firstDay && firstLimit != null && st != null && st.isBefore(firstLimit)) {
                    dropped++;
                    log.debug("约束丢弃:首日早于到达+缓冲 -> {}", n.getPlaceName());
                    continue;
                }

                // ② 末日:任何节点都不得晚于"返程-150 分钟"开始
                if (lastDay && leaveTime != null && st != null
                        && !st.isBefore(leaveTime.minusMinutes(DEPARTURE_BUFFER_MIN))) {
                    dropped++;
                    log.debug("约束丢弃:末日超过返程边界 -> {}", n.getPlaceName());
                    continue;
                }

                if ("景点".equals(type)) {
                    // ③ 每天主景点数量按节奏封顶
                    if (attractionsToday >= maxAttractions) {
                        dropped++;
                        log.debug("约束丢弃:当天景点超过 {} 个 -> {}", maxAttractions, n.getPlaceName());
                        continue;
                    }
                    // ④ 同一景点不重复安排
                    String key = norm(n.getPlaceName());
                    if (key.isEmpty() || !usedAttractions.add(key)) {
                        dropped++;
                        log.debug("约束丢弃:景点重复 -> {}", n.getPlaceName());
                        continue;
                    }
                    attractionsToday++;
                } else if ("餐饮".equals(type)) {
                    // ⑤ 每天最多一顿午餐、一顿晚餐(早餐/夜宵不限)
                    if (st != null && !st.isBefore(LUNCH_FROM) && st.isBefore(LUNCH_TO)) {
                        if (lunchUsed) { dropped++; continue; }
                        lunchUsed = true;
                    } else if (st != null && !st.isBefore(DINNER_FROM) && st.isBefore(DINNER_TO)) {
                        if (dinnerUsed) { dropped++; continue; }
                        dinnerUsed = true;
                    }
                } else if ("住宿".equals(type)) {
                    // ⑥ 最后一天不排住宿
                    if (lastDay) {
                        dropped++;
                        log.debug("约束丢弃:最后一天不排住宿 -> {}", n.getPlaceName());
                        continue;
                    }
                    // ⑦ 同一天最多一个住宿节点。
                    //    实测模型经常在同一天写两个(如"武侯祠-锦里一带"+"春熙路-太古里一带"),
                    //    用户看到会觉得行程是错的——一天只可能睡一个地方。
                    if (lodgingToday) {
                        dropped++;
                        log.debug("约束丢弃:当天已有住宿 -> {}", n.getPlaceName());
                        continue;
                    }
                    // ⑧ 住宿总数不超过晚数
                    if (lodgingUsed >= nights) {
                        dropped++;
                        log.debug("约束丢弃:住宿数超过 {} 晚 -> {}", nights, n.getPlaceName());
                        continue;
                    }
                    lodgingToday = true;
                    lodgingUsed++;
                }
                kept.add(n);
            }
            day.setNodes(kept);
        }
        if (dropped > 0) {
            log.info("生成结果约束兜底:丢弃 {} 个越界/重复节点", dropped);
        }
        return dropped;
    }

    /** 节奏 → 每天主景点上限(与 prompt 规则 4 一致) */
    private int maxAttractionsPerDay(String pace) {
        if (pace == null) return 3;
        String p = pace.trim();
        if (p.contains("悠闲") || p.contains("轻松")) return 2;
        if (p.contains("特种兵") || p.contains("紧凑")) return 4;
        return 3;
    }

    /** 住宿晚数:有到达+返程按日期差算;否则按"天数-1" */
    private int nights(GenerateRequestDTO req) {
        if (req.getArrival() != null && req.getDeparture() != null
                && req.getArrival().getDatetime() != null && req.getDeparture().getDatetime() != null) {
            long n = ChronoUnit.DAYS.between(req.getArrival().getDatetime().toLocalDate(),
                    req.getDeparture().getDatetime().toLocalDate());
            return (int) Math.max(0, n);
        }
        return req.getDays() == null ? 0 : Math.max(0, req.getDays() - 1);
    }

    private int nz(Integer v) { return v == null ? 0 : v; }

    private String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s·・（）()\\-—]", "").trim();
    }

    /** 容忍 "9:00" / "09:00" / "09:00:00";解析不出返回 null */
    private LocalTime parseTime(String t) {
        if (t == null || t.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t.trim());
        if (!m.find()) return null;
        try {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            return (h >= 0 && h < 24 && min >= 0 && min < 60) ? LocalTime.of(h, min) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
