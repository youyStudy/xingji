package com.xingji.chat;

import com.xingji.dto.AnchorStateDTO;
import com.xingji.dto.ChatProfileDTO;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 日期关系规则（纯函数，便于回归测试）：
 * startDate ≤ arrival.date ≤ endDate；返程日不能早于出发日。
 * 冲突时以到达/返程锚点为准，并写入修正说明。
 */
public final class DateRules {

    public static final int MAX_DAYS = 30;

    private DateRules() {
    }

    public static boolean consistent(ChatProfileDTO p) {
        if (p == null) return true;
        LocalDate start = parse(p.getStartDate());
        LocalDate end = parse(p.getEndDate());
        if (start != null && end != null && end.isBefore(start)) return false;
        if (p.getArrival() != null) {
            LocalDate arrival = parse(p.getArrival().getDate());
            if (arrival != null) {
                if (start != null && arrival.isBefore(start)) return false;
                if (end != null && arrival.isAfter(end)) return false;
            }
        }
        if (p.getDeparture() != null) {
            LocalDate departure = parse(p.getDeparture().getDate());
            if (departure != null && start != null && departure.isBefore(start)) return false;
        }
        return true;
    }

    public static void applyConsistency(ChatProfileDTO p, List<String> corrections) {
        if (p == null) return;
        LocalDate start = parse(p.getStartDate());
        LocalDate end = parse(p.getEndDate());
        AnchorStateDTO arr = p.getArrival();
        AnchorStateDTO dep = p.getDeparture();
        LocalDate arrival = arr == null ? null : parse(arr.getDate());
        LocalDate departure = dep == null ? null : parse(dep.getDate());

        if (arrival != null) {
            if (start != null && arrival.isBefore(start)) {
                corrections.add("到达日期早于出发日期，已按到达日期对齐");
                p.setStartDate(arrival.toString());
                start = arrival;
            }
            if (end != null && arrival.isAfter(end)) {
                corrections.add("到达日期晚于结束日期，已按到达日期重新对齐");
                // 到达锚点通常是最新的明确信息：以它为起点重排，而不是留一个到达后才开始的旧区间
                p.setStartDate(arrival.toString());
                start = arrival;
                end = (p.getDays() != null && p.getDays() >= 1)
                        ? arrival.plusDays(p.getDays() - 1L) : arrival;
                p.setEndDate(end.toString());
            }
            if (start == null) {
                p.setStartDate(arrival.toString());
                start = arrival;
            }
            if (end == null && start != null && p.getDays() != null && p.getDays() >= 1) {
                end = start.plusDays(p.getDays() - 1L);
                p.setEndDate(end.toString());
            }
        }

        if (departure != null) {
            if (start != null && departure.isBefore(start)) {
                corrections.add("返程日期早于出发日期，先没记返程日期");
                dep.setDate(null);
                dep.setTime(null);
                dep.setDatetime(null);
            } else if (end != null && departure.isAfter(end)) {
                corrections.add("结束日期已按返程日期调整");
                p.setEndDate(departure.toString());
                end = departure;
            }
        }

        if (start != null && end != null && !end.isBefore(start)) {
            long span = ChronoUnit.DAYS.between(start, end) + 1;
            if (span >= 1 && span <= MAX_DAYS) {
                p.setDays((int) span);
            }
        }
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}