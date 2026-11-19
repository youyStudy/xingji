package com.xingji.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.vo.SpotVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReplySanitizerTests {

    private final ChatReplySanitizer sanitizer = new ChatReplySanitizer();

    @Test
    void removesInventedDayAndWeekdayWhenDateIsUndecided() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "那先按 5 日（周四）返程，日期待定（周一）也行。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("日期还没定"), List.of());

        assertFalse(cleaned.contains("5 日"));
        assertFalse(cleaned.contains("周四"));
        assertFalse(cleaned.contains("（周一）"));
        assertTrue(cleaned.contains("返程日期还没定"));
    }

    @Test
    void removesPendingWeekdayFromASpecificSentence() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String reply = "那先按周末出发搭一版吧，比如日期待定（周六）到，玩两天，周日晚上回。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("你看着办吧"), List.of());

        assertFalse(cleaned.contains("日期待定（周六）"));
        assertFalse(cleaned.contains("周六"));
        assertFalse(cleaned.contains("周日"));
        assertTrue(cleaned.contains("日期还没定"));
    }

    @Test
    void keepsDayCountWhenNoDateWasGiven() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setDays(5);
        String reply = "那就按 5 日游来安排，日期还没定也没关系。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("玩5天，日期还没定"), List.of());

        assertTrue(cleaned.contains("5 日游"));
        assertFalse(cleaned.contains("日期待定游"));
    }

    @Test
    void doesNotTurnTransitLineNumbersIntoDates() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "武林门坐地铁1号线到龙翔桥，酒店在2号楼。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("日期还没定"), List.of());

        assertTrue(cleaned.contains("地铁1号线"));
        assertTrue(cleaned.contains("2号楼"));
        assertFalse(cleaned.contains("日期待定线"));
    }

    @Test
    void removesUnverifiedApproximateMonthWithoutDroppingDayCount() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那先按「下个月中旬、3天」搭一版杭州行程？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of());

        assertEquals("那先按「3天」搭一版杭州行程？", cleaned);
    }

    @Test
    void removesPendingDatePairInsteadOfLeavingDuplicatePlaceholders() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那先按12月初（日期待定-日期待定）搭个杭州行程？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of());

        assertEquals("那先搭个杭州行程？", cleaned);
    }

    @Test
    void removesInventedWeekendButKeepsDuration() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那就暂定日期待定（周五）出发，玩到日期待定（周日）？"
                + "周五下午到，周日晚上走，三天时间刚好把西湖逛完。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of());

        assertEquals("先不锁具体日期，三天时间刚好把西湖逛完。", cleaned);
    }

    @Test
    void removesParenthesizedWeekendRangeWithoutDamagingWeekdayWords() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那就按12月初（周五-周日）3天杭州来定了。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of());

        assertEquals("那就按3天杭州来定了。", cleaned);
    }

    @Test
    void keepsKnownArrivalTimeWithoutInventingDate() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String reply = "那就按日期待定上午10点到杭州东站，玩2天来定了。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of());

        assertFalse(cleaned.contains("日期待定"));
        assertTrue(cleaned.contains("上午10点"));
        assertTrue(cleaned.contains("玩2天"));
    }

    @Test
    void doesNotLeavePendingDateGluedToArrivalTime() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String reply = "11月15号上午10点到东站，那正好可以上午先去西湖，下午逛河坊街。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("好"), List.of());

        assertFalse(cleaned.contains("日期还没定上午"));
        assertTrue(cleaned.startsWith("上午10点到东站"));
    }

    @Test
    void bareAcknowledgementDoesNotTurnAnOpenQuestionIntoThreeDays() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那先按3天搭个骨架吧：第一天西湖周边，第二天灵隐寺和龙井，第三天河坊街和宋城。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), true, "你大概什么时候想去？几天呢？");

        assertFalse(cleaned.contains("3天"));
        assertFalse(cleaned.contains("第三天"));
        assertTrue(cleaned.contains("先不用急着定几天"));
    }

    @Test
    void bareAcknowledgementStripsUnstatedDurationWithoutReplacingTheWholeReply() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那就按杭州两天的基础框架搭起来吧。";
        String previous = "那先按杭州的经典路线搭个框架：西湖周边、灵隐寺、龙井村。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("行"), List.of(), true, previous);

        assertFalse(cleaned.contains("两天"));
        assertTrue(cleaned.contains("这趟"));
        assertTrue(cleaned.contains("基础框架"));
    }

    @Test
    void bareAcknowledgementStripsUnstatedDurationRange() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String reply = "那杭州可以玩2-3天，慢悠悠逛西湖、灵隐寺。";
        String previous = "你大概什么时候去？几天比较合适？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), true, previous);

        assertFalse(cleaned.contains("2-3天"));
        assertFalse(cleaned.contains("2-这趟"));
        assertTrue(cleaned.contains("慢悠悠逛西湖"));
    }

    @Test
    void correctsOneAndHalfDaysWhenProfileSaysTwoDays() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setDays(2);
        String reply = "15号上午到，16号晚上返程，一天半时间也够玩西湖和灵隐寺。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("玩两天"), List.of());

        assertFalse(cleaned.contains("一天半"));
        assertTrue(cleaned.matches(".*(?:两|2)天时间.*"));
    }

    @Test
    void removesARepeatedQuestionTopicFromPreviousReply() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String previous = "路线先这样，需要我帮你细化具体路线或者推荐些吃的吗？";
        String reply = "好，那就这么定。第二天上午灵隐寺，下午龙井村，需要我细化每个点的玩法吗？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯，就这样定了"), List.of(), false, previous);

        assertFalse(cleaned.contains("需要我细化"));
        assertTrue(cleaned.contains("第二天上午灵隐寺"));
    }

    @Test
    void removesQuestionTopicAlreadyCoveredInPreviousReplyBody() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String previous = "第二天上午灵隐寺，下午龙井村。住宿建议选西湖边或地铁沿线，方便第二天出发。";
        String reply = "那就按这个节奏来，日期还没定，两天一夜。需要我帮你细化具体路线和住宿推荐吗？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), false, previous);

        assertFalse(cleaned.contains("需要我帮你细化"));
        assertTrue(cleaned.contains("两天一夜"));
    }

    @Test
    void removesDirectionQuestionAfterUserAlreadyApprovedThatDirection() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String previous = "好，那就按这个方向走。第一天西湖周边，断桥、苏堤、雷峰塔；"
                + "第二天灵隐寺和龙井村，感受茶园慢生活。";
        String reply = "11月15号上午10点到杭州东站，玩两天，这个时间很合适。"
                + "住宿建议西湖边或河坊街附近，交通方便也省时间。你觉得这个方向行吗？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("11月15号上午10点到杭州东站，玩2天"), List.of(), false, previous);

        assertFalse(cleaned.contains("这个方向行吗"));
        assertTrue(cleaned.contains("住宿建议西湖边或河坊街附近"));
    }

    @Test
    void removesDateQuestionWhenPreviousReplyAlreadySaidDateIsUndecided() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String previous = "那就按这个方向来，具体日期你定了再排。"
                + "这两天节奏可以松一点，西湖边慢慢逛。";
        String reply = "那这两天就按轻松节奏走，西湖边慢慢逛，灵隐寺和龙井村也不用赶。"
                + "你大概什么时候出发？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), false, previous);

        assertFalse(cleaned.contains("什么时候出发"));
        assertTrue(cleaned.contains("西湖边慢慢逛"));
    }

    @Test
    void collapsesRepeatedPendingDateAndRemovesItBeforeTimeOfDay() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String reply = "那就按这个节奏来，日期还没定日期还没定，两天一夜。日期还没定早上灵隐寺，下午龙井村。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), true, "你觉得这个安排怎么样？");

        assertEquals(cleaned.indexOf("日期还没定"), cleaned.lastIndexOf("日期还没定"));
        assertTrue(cleaned.contains("两天一夜"));
        assertTrue(cleaned.contains("早上灵隐寺"));
        assertFalse(cleaned.contains("日期还没定早上"));
    }

    @Test
    void bareAcknowledgementDoesNotRepeatThePreviousReply() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        profile.setDays(2);
        String previous = "11月15号上午10点到东站，那正好可以上午先去西湖，下午逛河坊街；第二天灵隐寺和龙井村。";

        String cleaned = sanitizer.sanitize(previous, profile,
                DateEvidence.of("好"), List.of(), true, previous);

        assertFalse(cleaned.equals(previous));
        assertTrue(cleaned.contains("先记着"));
    }

    @Test
    void bareAcknowledgementDoesNotRepeatNearlyIdenticalSkeleton() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String previous = "嗯，那咱们先按杭州的经典路线搭个骨架吧：西湖周边、灵隐寺、龙井茶园，"
                + "节奏可以按你喜欢的来调整。你大概想什么时候去？几天比较合适？";
        String reply = "好，那咱们先按杭州的经典路线搭个骨架：西湖周边、灵隐寺、龙井茶园，"
                + "节奏可以按你喜欢的来调整。你大概想什么时候去？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("好"), List.of(), true, previous);

        assertFalse(cleaned.contains("经典路线搭个骨架"));
        assertTrue(cleaned.contains("先不用急着定几天"));
    }

    @Test
    void removesMultipleRepeatedQuestionsInOneTrailingBlock() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("杭州");
        String previous = "杭州真是个好选择！西湖、灵隐寺、龙井茶园都值得去。你大概什么时候想去？几天呢？";
        String reply = "嗯，那咱们先按杭州的经典路线搭个骨架吧：西湖周边、灵隐寺、龙井茶园。"
                + "你大概想什么时候去？几天比较合适？";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("嗯"), List.of(), true, previous);

        assertFalse(cleaned.contains("什么时候去"));
        assertFalse(cleaned.contains("几天比较合适"));
        assertTrue(cleaned.contains("经典路线"));
    }

    @Test
    void removesConcreteTravelPrices() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "酒店每晚 300-800 元/晚，机票往返 150-300 元，门票 80 元。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("随便看看"), List.of());

        assertFalse(cleaned.contains("300-800"));
        assertFalse(cleaned.contains("150-300"));
        assertFalse(cleaned.contains("80 元"));
        assertTrue(cleaned.contains("具体房价以预订平台为准"));
        assertTrue(cleaned.contains("具体票价以订票平台为准"));
    }

    @Test
    void replacesWholePriceClauseWithoutDanglingModifiers() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "船票会根据舱位不同有所差异，经济舱大概 150 元，节假日可能会涨价。"
                + "民宿大概 300 元/晚，位置好点的可能 500 元一晚。"
                + "海鲜大排档人均50-80就能吃得不错。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("随便看看"), List.of());

        assertFalse(cleaned.contains("经济舱大概"));
        assertFalse(cleaned.contains("大概具体"));
        assertFalse(cleaned.contains("300 元/晚"));
        assertFalse(cleaned.contains("500 元一晚"));
        assertFalse(cleaned.contains("人均50-80"));
        assertTrue(cleaned.contains("具体票价以订票平台为准"));
        assertTrue(cleaned.contains("具体房价以预订平台为准"));
        assertTrue(cleaned.contains("餐饮消费以商家实际价格为准"));
    }

    @Test
    void removesPriceRangesWithoutCurrencyWord() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "价格大概200-300一晚，具体看位置。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("随便看看"), List.of());

        assertFalse(cleaned.contains("200-300"));
        assertFalse(cleaned.contains("一晚"));
        assertTrue(cleaned.contains("具体房价以预订平台为准"));
    }

    @Test
    void removesPlainPriceRangeAfterPriceMarker() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "可以帮你查几家评价不错的，价格在200-300之间，交通也方便。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("住宿帮我看看"), List.of());

        assertFalse(cleaned.contains("200-300"));
        assertTrue(cleaned.contains("具体价格以实际查询为准"));
    }

    @Test
    void removesPriceRangeAfterLodgingGrade() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "西湖边地铁附近的酒店，具体房价以预订平台为准，舒适型500-800，看你对住宿的要求。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("住宿预算1000"), List.of());

        assertFalse(cleaned.contains("500-800"));
        assertTrue(cleaned.contains("具体房价以预订平台为准"));
    }

    @Test
    void collapsesRepeatedPriceDisclaimers() {
        ChatProfileDTO profile = new ChatProfileDTO();
        String reply = "像银滩假日酒店和北海银滩海景酒店，"
                + "具体房价以预订平台为准，具体房价以预订平台为准。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("随便看看"), List.of());

        String phrase = "具体房价以预订平台为准";
        assertEquals(cleaned.indexOf(phrase), cleaned.lastIndexOf(phrase));
        assertTrue(cleaned.contains("银滩假日酒店"));
        assertTrue(cleaned.contains("银滩海景酒店"));
    }

    @Test
    void keepsUsersConfirmedBudget() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setBudget(BigDecimal.valueOf(3000));
        String reply = "你的预算 3000 元先按这个控，酒店每晚 300-800 元/晚。";

        String cleaned = sanitizer.sanitize(reply, profile,
                DateEvidence.of("预算3000元，日期还没定"), List.of());

        assertTrue(cleaned.contains("预算 3000 元"));
        assertFalse(cleaned.contains("300-800"));
    }

    @Test
    void spotPromptDoesNotLeakCardFactsToTheModel() {
        ChatProfileDTO profile = new ChatProfileDTO();
        profile.setCity("成都");
        SpotVO spot = new SpotVO();
        spot.setName("武侯祠");
        spot.setCategory("风景名胜");
        spot.setRating("4.8");
        spot.setCost("50");
        spot.setOpenTime("08:00-18:00");

        String prompt = new ChatContextBuilder(new ObjectMapper()).build(
                "推荐景点", profile, ChatStrategy.ANSWER, List.of(),
                null, "", List.of(spot), false);

        assertTrue(prompt.contains("武侯祠"));
        assertTrue(prompt.contains("风景名胜"));
        assertFalse(prompt.contains("4.8"));
        assertFalse(prompt.contains("50"));
        assertFalse(prompt.contains("08:00-18:00"));
        assertTrue(prompt.contains("评分、门票、开放时间、价格由前端卡片展示"));
    }
}
