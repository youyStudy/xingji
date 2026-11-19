package com.xingji.chat;

import com.xingji.dto.TimePeriod;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本轮用户原话里的日期/时刻证据。
 *
 * 只回答一个问题：模型写进画像的日期/时刻，能不能从这句话里推出来。
 * 推不出来就不许写——这是"不编日期"的服务端闸门，不依赖模型自觉。
 */
public final class DateEvidence {

    private static final Pattern DATE_FULL = Pattern.compile("(20\\d{2})\\D{1,3}(\\d{1,2})\\D{1,3}(\\d{1,2})");
    private static final Pattern DATE_MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern DATE_DAY = Pattern.compile(
            "(?<!\\d)(?<!第)(\\d{1,2})\\s*[日号](?!\\s*游)");
    private static final Pattern TIME_HM = Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{1,2})");
    private static final Pattern TIME_CN = Pattern.compile(
            "(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*(\\d{1,2})\\s*[点時时](?:\\s*(半|(\\d{1,2})\\s*分?))?");

    private final Set<String> dates = new LinkedHashSet<>();
    private final Set<String> times = new LinkedHashSet<>();
    private final TimePeriod period;
    private final boolean explicitHour;
    private final LocalDate today;
    private final boolean contentKeepsDates;

    private DateEvidence(String content) {
        this.today = LocalDate.now();
        String text = content == null ? "" : content;
        this.contentKeepsDates = Pattern.compile("日期不变|时间不变|还是原来|不改日期|不改时间|日期照旧")
                .matcher(text).find();
        offsetDays(text);
        fullDate(text);
        monthDay(text);
        dayOnly(text);
        time(text);
        this.period = detectPeriod(text);
        this.explicitHour = !times.isEmpty() || TIME_HM.matcher(text).find()
                || hasHourWithDian(text);
    }

    public static DateEvidence of(String content) {
        return new DateEvidence(content);
    }

    public boolean hasDate(String iso) {
        return iso != null && dates.contains(iso.trim());
    }

    public boolean hasTime(String hhmm) {
        return hhmm != null && times.contains(hhmm.trim());
    }

    public boolean hasExplicitTime() {
        return explicitHour;
    }

    public TimePeriod timePeriod() {
        return period;
    }

    /** 原话里有没有任何日期信号（用于"换城市是否保留旧日期"的判断） */
    public boolean hasAnyDateSignal() {
        return !dates.isEmpty();
    }

    /** 用户是否明确表示日期不变（换城市时保留旧日期） */
    public boolean explicitlyKeepsDates() {
        return contentKeepsDates;
    }

    private void addDate(LocalDate d) {
        if (d != null) dates.add(d.toString());
    }

    private void offsetDays(String text) {
        if (text.contains("大后天")) addDate(today.plusDays(3));
        else if (text.contains("后天")) addDate(today.plusDays(2));
        else if (text.contains("明天") || text.contains("明日")) addDate(today.plusDays(1));
        else if (text.contains("今天") || text.contains("今晚")) addDate(today);
    }

    private void fullDate(String text) {
        Matcher m = DATE_FULL.matcher(text);
        while (m.find()) {
            try {
                addDate(LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
            } catch (Exception ignored) {
                // 非法日期不产生证据
            }
        }
    }

    private void monthDay(String text) {
        Matcher m = DATE_MONTH_DAY.matcher(text);
        while (m.find()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            for (LocalDate base : baseMonths(text)) {
                try {
                    addDate(LocalDate.of(base.getYear(), month, day));
                } catch (Exception ignored) {
                    // 跳过非法
                }
            }
        }
    }

    private void dayOnly(String text) {
        Matcher m = DATE_DAY.matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            for (LocalDate base : baseMonths(text)) {
                try {
                    addDate(base.withDayOfMonth(day));
                } catch (Exception ignored) {
                    // 跳过非法
                }
            }
        }
    }

    /**
     * 日期基准月：默认本月；出现"下个月/下月"时再加一个月。
     * 两个都放进去是有意的——宁可多给候选，也不要把用户真实说的日期误判成编造。
     */
    private java.util.List<LocalDate> baseMonths(String text) {
        java.util.List<LocalDate> list = new java.util.ArrayList<>();
        if (text.contains("下个月") || text.contains("下月")) {
            list.add(today.plusMonths(1));
        } else if (text.contains("这个月") || text.contains("本月")) {
            list.add(today);
        } else {
            list.add(today);
            list.add(today.plusMonths(1));
            list.add(today.plusYears(1));
        }
        return list;
    }

    private void time(String text) {
        Matcher hm = TIME_HM.matcher(text);
        while (hm.find()) {
            try {
                int h = Integer.parseInt(hm.group(1));
                int m = Integer.parseInt(hm.group(2));
                if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                    times.add(String.format("%02d:%02d", h, m));
                }
            } catch (Exception ignored) {
                // 继续中文时刻
            }
        }
        Matcher cn = TIME_CN.matcher(text);
        while (cn.find()) {
            try {
                int h = Integer.parseInt(cn.group(2));
                String minuteText = cn.group(3);
                int m = "半".equals(minuteText) ? 30
                        : (cn.group(4) != null ? Integer.parseInt(cn.group(4)) : 0);
                String p = cn.group(1);
                int adjusted = h;
                if (p != null) {
                    if (p.contains("下午") || p.contains("傍晚") || p.contains("晚上")) {
                        if (adjusted < 12) adjusted += 12;
                    } else if (p.contains("中午")) {
                        if (adjusted < 11) adjusted += 12;
                    } else if (p.contains("凌晨") && adjusted == 12) {
                        adjusted = 0;
                    }
                }
                if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                    times.add(String.format("%02d:%02d", h, m));
                }
                if (adjusted >= 0 && adjusted <= 23 && m >= 0 && m <= 59) {
                    times.add(String.format("%02d:%02d", adjusted, m));
                }
            } catch (Exception ignored) {
                // 解析失败不产生证据
            }
        }
    }

    private boolean hasHourWithDian(String text) {
        return text.matches(".*\\d{1,2}\\s*[点時时].*");
    }

    private TimePeriod detectPeriod(String text) {
        if (text.contains("凌晨")) return TimePeriod.NIGHT;
        if (text.contains("早上") || text.contains("早晨") || text.contains("上午")) return TimePeriod.MORNING;
        if (text.contains("中午") || text.contains("下午")) return TimePeriod.AFTERNOON;
        if (text.contains("傍晚")) return TimePeriod.EVENING;
        if (text.contains("晚上") || text.contains("夜里") || text.contains("今晚")) return TimePeriod.NIGHT;
        return null;
    }

    /** 供测试与生成侧使用：把一个 "HH:mm" 串转成 LocalTime，不合法返回 null */
    public static LocalTime parseHm(String hm) {
        try {
            return LocalTime.parse(hm);
        } catch (Exception e) {
            return null;
        }
    }
}
