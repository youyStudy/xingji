package com.xingji.chat;

import com.xingji.dto.ChatProfileDTO;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes claims the chat model cannot support with server-side facts.
 *
 * The prompt asks the model not to invent dates or live prices, but model output is not a
 * reliable boundary. This class keeps two deterministic guards close to the response path:
 * no exact dates without date evidence, and no concrete travel prices.
 */
@Component
public class ChatReplySanitizer {

    private static final String AMOUNT = "(?:\\d+(?:\\.\\d+)?|[一二三四五六七八九十百千万两]+)";
    private static final String AMOUNT_RANGE =
            "(?:" + AMOUNT + "\\s*(?:-|~|～|—|–|至|到)\\s*" + AMOUNT + "|" + AMOUNT + ")";
    private static final String CURRENCY_UNIT = "\\s*(?:元|块钱?)(?:\\s*/\\s*(?:人|位|晚|间|天|张|程))?";
    private static final String PRICE_MARKERS =
            "(?:人均|每晚|每间|一晚|一间|单程|往返|票价|房价|房费|价格|价位|费用|"
                    + "门票|经济舱|商务舱|头等舱|海景房|标间|大床房|"
                    + "经济型|舒适型|高档型|豪华型)";

    /**
     * 价格清洗按分句做，而不是只替换金额本身。否则“经济舱大概 150 元”会留下
     * “经济舱大概具体价格…”，读起来比不替换还别扭。
     */
    private static final Pattern CLAUSE = Pattern.compile("[^，。；！？：\\n]+");
    private static final Pattern CURRENCY_PRICE = Pattern.compile(AMOUNT_RANGE + CURRENCY_UNIT);
    private static final Pattern MARKED_PRICE = Pattern.compile(
            PRICE_MARKERS + "[^，。；！？：\\n]{0,18}?" + AMOUNT_RANGE
    );
    private static final Pattern UNIT_RANGE_PRICE = Pattern.compile(
            AMOUNT + "\\s*(?:-|~|～|—|–|至|到)\\s*" + AMOUNT
                    + "\\s*(?:元|块钱?)?\\s*(?:/\\s*)?"
                    + "(?:\\d+|[一二三四五六七八九十两]+)?\\s*(?:人|位|晚|间|张|程)"
    );
    private static final String PRICE_DISCLAIMER =
            "(?:具体(?:房价|票价|价格)以[^，,。；;！？\\n]{0,24}为准"
                    + "|餐饮消费以[^，,。；;！？\\n]{0,24}为准"
                    + "|交通费用以[^，,。；;！？\\n]{0,24}为准)";
    private static final Pattern REPEATED_PRICE_DISCLAIMER = Pattern.compile(
            "(" + PRICE_DISCLAIMER + ")(?:(?:\\s*[，,；;。]\\s*)\\1)+"
    );
    private static final Pattern BUDGET_SIGNAL = Pattern.compile("预算|总预算|整体预算|总花销|总花费");

    private static final Pattern PENDING_WEEKDAY = Pattern.compile(
            "(日期待定|时间待定)[\\s\\u00A0\\u3000]*[（(][\\s\\u00A0\\u3000]*"
                    + "(?:周|星期)[一二三四五六日天][\\s\\u00A0\\u3000]*[）)]"
    );
    private static final Pattern UNVERIFIED_APPROX_DATE = Pattern.compile(
            "(?:(?:下个?月|这个月|本月|\\d{1,2}\\s*月)"
                    + "\\s*(?:初|上旬|中旬|下旬|底)?)"
                    + "(?:\\s*[（(]\\s*(?:日期待定|时间待定)"
                    + "(?:\\s*(?:-|~|～|—|–|至|到)\\s*(?:日期待定|时间待定))?"
                    + "\\s*[）)])?\\s*[、,，]?"
    );
    private static final Pattern WEEKDAY_RANGE = Pattern.compile(
            "(?:这|下)?(?:周|星期)[一二三四五六日天]\\s*"
                    + "(?:-|~|～|—|–|至|到)\\s*(?:周|星期)[一二三四五六日天]"
    );
    private static final Pattern PAREN_WEEKDAY_RANGE = Pattern.compile(
            "[（(]\\s*" + WEEKDAY_RANGE.pattern() + "\\s*[）)]"
    );
    private static final Pattern UNVERIFIED_WEEKDAY_EVENT = Pattern.compile(
            "(?:这|下)?(?:周|星期)[一二三四五六日天]\\s*"
                    + "(?:上午|中午|下午|傍晚|晚上|早上)?\\s*"
                    + "(?:出发|到|到达|返程|回|走)[，,]?"
    );
    private static final Pattern WEEKDAY_ONLY = Pattern.compile(
            "(?:这|下)?(?:周|星期)[一二三四五六日天]"
    );
    private static final Pattern DAY_WEEKDAY_RETURN = Pattern.compile(
            "\\d{1,2}\\s*[日号]\\s*[（(]?\\s*(?:周|星期)[一二三四五六日天]\\s*[）)]?\\s*(返程|回程)"
    );
    private static final Pattern DATE_ISO = Pattern.compile(
            "20\\d{2}-\\d{1,2}-\\d{1,2}\\s*(?:到|到达|出发)?"
    );
    private static final Pattern DATE_CN_FULL = Pattern.compile(
            "20\\d{2}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?\\s*(?:到|到达|出发)?"
    );
    private static final Pattern DATE_CN_MONTH_DAY = Pattern.compile(
            "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?\\s*(?:到|到达|出发)?"
    );
    private static final Pattern DATE_DAY = Pattern.compile(
            "(?<!\\d)(?<!第)\\d{1,2}\\s*[日号]"
                    + "(?!\\s*(?:游|线|院|楼|厅|店|房|口|门|通道|航站楼|出口|入口))"
                    + "(?:\\s*[（(]?\\s*(?:周|星期)[一二三四五六日天]\\s*[）)]?)?"
    );
    private static final Pattern DATE_RELATIVE = Pattern.compile("大后天|后天|明天|明日");
    private static final Pattern DURATION_CLAIM = Pattern.compile(
            "(?<!第)(?:\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(?:天|日游)"
    );
    private static final Pattern LATE_DAY_ORDINAL = Pattern.compile("第[三四五六七八九十]+天");
    private static final Pattern DAY_SKELETON = Pattern.compile(
            "(?:按|排|走|玩)\\s*(?:\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*(?:天|日)"
                    + "|第[一二三四五六七八九十]+天[^。！？\\n]*第[一二三四五六七八九十]+天"
    );
    private static final Pattern FINAL_QUESTION = Pattern.compile("([^，,。！？!?；;\\n]+[？?])\\s*$");
    private static final List<String> QUESTION_TOPICS = List.of(
            "细化", "推荐", "调整", "方向", "住宿", "交通", "美食", "路线", "吃的",
            "几天", "天数"
    );
    private static final List<String> DATE_QUESTION_TOPICS = List.of(
            "日期", "什么时候", "时间", "哪天", "几月"
    );

    public String sanitize(String reply, ChatProfileDTO profile,
                           DateEvidence evidence, List<String> corrections) {
        return sanitize(reply, profile, evidence, corrections, false, null);
    }

    public String sanitize(String reply, ChatProfileDTO profile,
                           DateEvidence evidence, List<String> corrections,
                           boolean bareAcknowledgement, String lastAssistantReply) {
        if (reply == null || reply.isBlank()) return reply;

        String guarded = guardBareAcknowledgement(reply, profile, bareAcknowledgement, lastAssistantReply);
        String cleaned = scrubUnverifiedDates(guarded, profile, evidence, corrections);
        cleaned = correctDurationAgainstProfile(cleaned, profile);
        cleaned = scrubRepeatedQuestion(cleaned, lastAssistantReply);
        return scrubUnsupportedPrices(cleaned);
    }

    private String correctDurationAgainstProfile(String reply, ChatProfileDTO profile) {
        if (profile == null || profile.getDays() == null) return reply;
        String duration = durationText(profile.getDays()) + "天";
        return reply
                .replace("一天半时间", duration + "时间")
                .replace("一天半行程", duration + "行程")
                .replace("一天半", duration);
    }

    private String scrubRepeatedQuestion(String reply, String previous) {
        if (blank(previous)) return reply;
        String currentQuestion = trailingQuestionBlock(reply);
        if (currentQuestion == null) return reply;
        String previousQuestion = finalQuestion(previous);
        boolean repeated = previousQuestion != null
                && sameQuestionFamily(currentQuestion, previousQuestion);
        if (!repeated && !previousCoversQuestionTopic(currentQuestion, previous)) return reply;

        int questionStart = trailingQuestionStart(reply);
        if (questionStart < 0) return reply;
        String before = reply.substring(0, questionStart).trim();
        return before.isEmpty() ? reply : before;
    }

    private String trailingQuestionBlock(String text) {
        if (text == null) return null;
        int start = trailingQuestionStart(text);
        return start < 0 ? null : text.substring(start).trim();
    }

    private int trailingQuestionStart(String text) {
        int end = text.length() - 1;
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) end--;
        if (end < 0 || (text.charAt(end) != '？' && text.charAt(end) != '?')) return -1;

        int sentenceStart = end;
        while (sentenceStart >= 0 && !isSentenceBoundary(text.charAt(sentenceStart))) {
            sentenceStart--;
        }
        sentenceStart++;

        int firstQuestion = -1;
        for (int i = sentenceStart; i <= end; i++) {
            if (text.charAt(i) == '？' || text.charAt(i) == '?') {
                firstQuestion = i;
                break;
            }
        }
        if (firstQuestion < 0) return -1;

        int clauseStart = firstQuestion;
        while (clauseStart > sentenceStart && !isClauseBoundary(text.charAt(clauseStart - 1))) {
            clauseStart--;
        }
        return clauseStart;
    }

    private boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '!' || c == '\n';
    }

    private boolean isClauseBoundary(char c) {
        return isSentenceBoundary(c) || c == '，' || c == ',' || c == '；' || c == ';';
    }

    private String finalQuestion(String text) {
        Matcher matcher = FINAL_QUESTION.matcher(text == null ? "" : text.trim());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean sameQuestionFamily(String current, String previous) {
        String a = canonicalForRepeat(current);
        String b = canonicalForRepeat(previous);
        if (a.equals(b)) return true;
        for (String topic : QUESTION_TOPICS) {
            if (a.contains(topic) && b.contains(topic)) return true;
        }
        return sharesTopicFamily(a, b, DATE_QUESTION_TOPICS);
    }

    private boolean previousCoversQuestionTopic(String current, String previous) {
        String currentCanonical = canonicalForRepeat(current);
        String previousCanonical = canonicalForRepeat(previous);
        for (String topic : QUESTION_TOPICS) {
            if (currentCanonical.contains(topic) && previousCanonical.contains(topic)) {
                return true;
            }
        }
        return sharesTopicFamily(currentCanonical, previousCanonical, DATE_QUESTION_TOPICS);
    }

    private boolean sharesTopicFamily(String current, String previous, List<String> topics) {
        boolean currentMatches = topics.stream().anyMatch(current::contains);
        boolean previousMatches = topics.stream().anyMatch(previous::contains);
        return currentMatches && previousMatches;
    }

    private String durationText(int days) {
        String[] names = {"零", "一", "两", "三", "四", "五", "六", "七", "八", "九", "十"};
        return days >= 1 && days <= 10 ? names[days] : Integer.toString(days);
    }

    /**
     * 纯确认轮次最容易被模型擅自扩写成“3天行程”或原样复读上一轮。
     * 这里只拦这两种会明显破坏体感的情况，其它回复仍交给模型自然表达。
     */
    private String guardBareAcknowledgement(String reply, ChatProfileDTO profile,
                                            boolean bareAcknowledgement, String lastAssistantReply) {
        if (!bareAcknowledgement) return reply;
        if (substantiallyRepeats(reply, lastAssistantReply)) {
            return acknowledgementFallback(profile, reply, lastAssistantReply);
        }
        if (profile == null || profile.getDays() == null) {
            if (DAY_SKELETON.matcher(reply).find() || LATE_DAY_ORDINAL.matcher(reply).find()) {
                return acknowledgementFallback(profile, reply, lastAssistantReply);
            }
            if (DURATION_CLAIM.matcher(reply).find()) {
                return scrubUnstatedDuration(reply);
            }
        }
        return reply;
    }

    private boolean substantiallyRepeats(String reply, String previous) {
        String current = canonicalForRepeat(stripLeadingAcknowledgement(reply));
        String before = canonicalForRepeat(previous);
        if (current.length() < 12 || before.length() < 12) return false;
        if (current.equals(before)) return true;
        return bigramSimilarity(current, before) >= 0.70;
    }

    private String stripLeadingAcknowledgement(String text) {
        if (text == null) return "";
        return text.replaceFirst("^(?:嗯+|好+|行+|ok|OK)[，,。.!！\\s]*", "");
    }

    private double bigramSimilarity(String left, String right) {
        Set<String> leftGrams = bigrams(left);
        Set<String> rightGrams = bigrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        Set<String> union = new HashSet<>(leftGrams);
        union.addAll(rightGrams);
        return (double) intersection.size() / union.size();
    }

    private Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }

    private String canonicalForRepeat(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "")
                .replaceAll("[，。；！？、,.!?;:：]", "");
    }

    private String acknowledgementFallback(ChatProfileDTO profile, String reply, String previous) {
        String city = profile == null ? "" : profile.getCity();
        if (city != null && !city.isBlank() && profile.getDays() == null) {
            String previousText = canonicalForRepeat(previous);
            if (previousText.contains("框架") || previousText.contains("路线")) {
                return city + "可以先按市区慢慢逛的方向想，先不用急着定几天。等日期有眉目了，我再把每天怎么走收紧。";
            }
            return city + "先不用急着定几天，先把想去的几个地方串起来就行，等你定下大概日期，我再帮你收节奏。";
        }
        if (profile != null && profile.getArrival() != null
                && !blank(profile.getArrival().getLocation())) {
            String arrival = profile.getArrival().getLocation();
            if (!blank(profile.getArrival().getTime())) {
                arrival += "和" + profile.getArrival().getTime();
            }
            return "行，" + arrival + "先记着。到站后就按轻松的节奏走，日期定了我再帮你把每天时间收顺。";
        }
        return "行，这版先记着，不用再重复确认。接下来按轻松的节奏往下收就行。";
    }

    private String scrubUnstatedDuration(String reply) {
        String s = reply
                .replaceAll("(?:按|排|走|玩)\\s*(?:\\d{1,2}|[一二两三四五六七八九十]{1,3})"
                                + "(?:\\s*(?:-|~|～|—|–|至|到)\\s*"
                                + "(?:\\d{1,2}|[一二两三四五六七八九十]{1,3}))?\\s*(?:天|日)",
                        "先")
                .replaceAll("(?<!第)(?:\\d{1,2}|[一二两三四五六七八九十]{1,3})"
                                + "(?:\\s*(?:-|~|～|—|–|至|到)\\s*"
                                + "(?:\\d{1,2}|[一二两三四五六七八九十]{1,3}))?\\s*(?:天|日游)",
                        "这趟");
        return s;
    }

    private String scrubUnverifiedDates(String reply, ChatProfileDTO profile,
                                        DateEvidence evidence, List<String> corrections) {
        boolean rejectedByServer = corrections != null && corrections.stream()
                .filter(c -> c != null)
                .anyMatch(c -> c.contains("日期没找到依据") || c.contains("日期需要重新确认"));
        boolean noDatesInProfile = profile == null
                || (blank(profile.getStartDate()) && blank(profile.getEndDate())
                && (profile.getArrival() == null || blank(profile.getArrival().getDate())));
        boolean hasDateEvidence = evidence != null && evidence.hasAnyDateSignal();
        if (!rejectedByServer && !(noDatesInProfile && !hasDateEvidence)) {
            return reply;
        }

        String s = PENDING_WEEKDAY.matcher(reply).replaceAll("$1");
        s = DAY_WEEKDAY_RETURN.matcher(s).replaceAll("$1日期待定");
        s = DATE_ISO.matcher(s).replaceAll("日期待定");
        s = DATE_CN_FULL.matcher(s).replaceAll("日期待定");
        s = DATE_CN_MONTH_DAY.matcher(s).replaceAll("日期待定");
        s = DATE_DAY.matcher(s).replaceAll("日期待定");
        s = DATE_RELATIVE.matcher(s).replaceAll("日期待定");
        s = UNVERIFIED_APPROX_DATE.matcher(s).replaceAll("");
        s = PAREN_WEEKDAY_RANGE.matcher(s).replaceAll("");
        s = WEEKDAY_RANGE.matcher(s).replaceAll("日期待定");
        s = UNVERIFIED_WEEKDAY_EVENT.matcher(s).replaceAll("");
        s = WEEKDAY_ONLY.matcher(s).replaceAll("日期待定");
        return normalizePendingDates(s);
    }

    private String normalizePendingDates(String reply) {
        String s = reply
                .replaceAll("(?:日期待定|时间待定)\\s*(?:-|~|～|—|–|至|到)\\s*(?:日期待定|时间待定)",
                        "日期待定")
                .replaceAll("(?:日期待定|时间待定)\\s*[（(]\\s*(?:日期待定|时间待定)\\s*[）)]",
                        "日期待定")
                .replaceAll("(日期还没定|时间还没定)(?:\\s*[，,；;、]?\\s*\\1)+", "$1")
                .replaceAll("按\\s*日期待定(?=\\s*(?:上午|中午|下午|傍晚|晚上|凌晨))",
                        "等你定日期后，")
                .replaceAll("(?:那就)?\\s*按?\\s*日期待定\\s*(?=(?:上午|中午|下午|傍晚|晚上|凌晨))", "")
                .replaceAll("(?:日期还没定|时间还没定)\\s*(?=(?:早上|上午|中午|下午|傍晚|晚上|凌晨))", "")
                .replaceAll("按\\s*日期待定", "先不锁日期，")
                .replaceAll("(?:那就)?\\s*暂定?日期待定\\s*出发[，,]?", "先不锁具体日期，")
                .replaceAll("玩到日期待定[？?，,]?", "")
                .replaceAll("按(?=搭|排|规划|安排)", "")
                .replaceAll("[「『“]\\s*[」』”]", "")
                .replaceAll("日期待定", "日期还没定");
        return s.replaceAll("^[，,；;\\s]+", "").replaceAll("[，,；;\\s]+$", "");
    }

    private String scrubUnsupportedPrices(String reply) {
        String cleaned = replaceMatches(reply, CLAUSE, matcher -> {
            String clause = matcher.group();
            if (!hasUnsupportedPrice(clause)) return clause;
            return preserveEdgeSpace(clause, priceReplacement(clause));
        });
        return REPEATED_PRICE_DISCLAIMER.matcher(cleaned).replaceAll("$1");
    }

    private String replaceMatches(String text, Pattern pattern,
                                  java.util.function.Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private boolean hasUnsupportedPrice(String clause) {
        if (clause == null || clause.isBlank()) return false;
        if (BUDGET_SIGNAL.matcher(clause).find()
                && CURRENCY_PRICE.matcher(clause).find()) {
            return false;
        }
        return CURRENCY_PRICE.matcher(clause).find()
                || MARKED_PRICE.matcher(clause).find()
                || UNIT_RANGE_PRICE.matcher(clause).find();
    }

    private String priceReplacement(String clause) {
        if (containsAny(clause, "打车", "出租车", "网约车", "包车", "车费", "油费", "过路费")) {
            return "交通费用以实际查询为准";
        }
        if (containsAny(clause, "门票", "景区", "景点", "参观")) {
            return "具体票价以景区或购票平台为准";
        }
        if (containsAny(clause, "餐饮", "吃饭", "餐费", "人均", "海鲜", "小吃", "大排档", "餐厅", "美食")) {
            return "餐饮消费以商家实际价格为准";
        }
        if (containsAny(clause, "机票", "飞机", "航班", "高铁", "动车", "火车", "车票",
                "船票", "票价", "经济舱", "商务舱", "头等舱", "往返", "单程")) {
            return "具体票价以订票平台为准";
        }
        if (containsAny(clause, "酒店", "住宿", "民宿", "客栈", "青旅", "房间",
                "房价", "房费", "海景房", "标间", "大床房", "一晚", "每晚", "每间", "住")) {
            return "具体房价以预订平台为准";
        }
        return "具体价格以实际查询为准";
    }

    private String preserveEdgeSpace(String original, String replacement) {
        int start = 0;
        while (start < original.length() && Character.isWhitespace(original.charAt(start))) start++;
        int end = original.length();
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) end--;
        return original.substring(0, start) + replacement + original.substring(end);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
