package com.xingji.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.ChatAction;
import com.xingji.dto.ChatIntent;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.dto.ConversationStateDTO;
import com.xingji.LLM.ChatIntentRouter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1 回归：意图解析、策略选择、服务端动作、状态合并、动态上下文。 */
class P1StrategyTests {

    private final ChatStrategyService strategyService = new ChatStrategyService();
    private final ChatActionResolver actionResolver = new ChatActionResolver();
    private final ConversationStateService stateService = new ConversationStateService();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void intentParsingFallsBackToUnknown() {
        assertEquals(ChatIntent.PLAN, ChatIntent.parse("plan"));
        assertEquals(ChatIntent.UNKNOWN, ChatIntent.parse("nonsense"));
        assertEquals(ChatIntent.UNKNOWN, ChatIntent.parse(null));
    }

    @Test
    void userQuestionUsesAnswer() {
        ChatStrategy s = strategyService.decide(new ChatProfileDTO(), new ConversationStateDTO(),
                "成都有什么好吃的？", false, List.of("city"));
        assertEquals(ChatStrategy.ANSWER, s);
    }

    @Test
    void vagueUserGetsSuggestionInsteadOfQuestion() {
        ChatStrategy s = strategyService.decide(new ChatProfileDTO(), new ConversationStateDTO(),
                "你定吧，我还没想好", false, List.of("city", "days"));
        assertEquals(ChatStrategy.SUGGEST, s);
    }

    @Test
    void consecutiveAskingSwitchesToSuggestion() {
        ConversationStateDTO state = new ConversationStateDTO();
        state.setConsecutiveQuestions(1);
        ChatStrategy s = strategyService.decide(new ChatProfileDTO(), state,
                "玩三天吧", false, List.of("startDate"));
        assertEquals(ChatStrategy.SUGGEST, s);
    }

    @Test
    void previousAskAvoidsRepeatingTheSameQuestion() {
        ConversationStateDTO state = new ConversationStateDTO();
        state.setLastStrategy("ASK");
        ChatStrategy s = strategyService.decide(new ChatProfileDTO(), state,
                "玩三天吧", false, List.of("startDate"));
        assertEquals(ChatStrategy.SUGGEST, s);
    }

    @Test
    void missingCoreInfoAllowsOneAsk() {
        ChatStrategy s = strategyService.decide(new ChatProfileDTO(), new ConversationStateDTO(),
                "玩三天吧", false, List.of("startDate"));
        assertEquals(ChatStrategy.ASK, s);
    }

    @Test
    void readyProfileUsesComplete() {
        ChatStrategy s = strategyService.decide(readyProfile(), new ConversationStateDTO(),
                "好的", true, List.of());
        assertEquals(ChatStrategy.COMPLETE, s);
    }

    @Test
    void readyProfileStillAnswersAQuestion() {
        ChatStrategy s = strategyService.decide(readyProfile(), new ConversationStateDTO(),
                "成都有什么好吃的？", true, List.of());
        assertEquals(ChatStrategy.ANSWER, s);
    }

    @Test
    void actionReadyDoesNotAuthorizeGeneration() {
        assertEquals(ChatAction.READY, actionResolver.resolve(readyProfile(), "好的", ChatIntent.CONFIRM, null));
    }

    @Test
    void explicitRequestBecomesGenerate() {
        assertEquals(ChatAction.GENERATE,
                actionResolver.resolve(readyProfile(), "就按这个生成吧", ChatIntent.CONFIRM, null));
    }

    @Test
    void explicitDenyStaysChat() {
        assertEquals(ChatAction.CHAT,
                actionResolver.resolve(readyProfile(), "先别生成，我再看看", ChatIntent.CONFIRM, null));
    }

    @Test
    void inconsistentDatesNeverReady() {
        ChatProfileDTO p = readyProfile();
        p.setEndDate("2026-11-15");
        assertEquals(ChatAction.CHAT, actionResolver.resolve(p, "生成吧", ChatIntent.CONFIRM, null));
    }

    @Test
    void consecutiveQuestionsTrackAskStrategy() {
        ConversationStateDTO state = new ConversationStateDTO();
        state = stateService.merge(state, null, ChatIntent.PLAN, ChatStrategy.ASK, true, false,
                readyProfile(), List.of("startDate"));
        assertEquals(1, state.getConsecutiveQuestions());
        state = stateService.merge(state, null, ChatIntent.PLAN, ChatStrategy.ASK, true, false,
                readyProfile(), List.of("startDate"));
        assertEquals(2, state.getConsecutiveQuestions());
        state = stateService.merge(state, null, ChatIntent.PLAN, ChatStrategy.SUGGEST, true, false,
                readyProfile(), List.of("startDate"));
        assertEquals(0, state.getConsecutiveQuestions());
    }

    @Test
    void readyStageIsSticky() {
        ConversationStateDTO state = stateService.merge(new ConversationStateDTO(), null,
                ChatIntent.CONFIRM, ChatStrategy.COMPLETE, false, true, readyProfile(), List.of());
        assertEquals("READY", state.getStage());
        assertEquals("CONFIRM", state.getLastIntent());
        assertEquals("COMPLETE", state.getLastStrategy());
    }

    @Test
    void contextBuilderCarriesStrategyAndConstraints() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setCity("泉州");
        p.setConstraints(ConstraintExtractor.apply("海鲜过敏", null));
        String prompt = new ChatContextBuilder(om).build("推荐点吃的", p, ChatStrategy.ANSWER,
                List.of("days"), "你大概哪天到？", "", List.of(), false);
        assertTrue(prompt.contains("[交流节奏]"));
        assertTrue(prompt.contains("[用户硬约束"));
        assertTrue(prompt.contains("海鲜过敏"));
        assertTrue(prompt.contains("\"intent\""));
        assertFalse(prompt.contains("[还缺的信息]"));
        assertFalse(prompt.contains("[可自然推进的一项]"));
        assertFalse(prompt.contains("禁止追问"), "P1 不再使用硬编码追问禁令");
    }

    @Test
    void contextBuilderShowsOnlyOneNaturalGapWhenAsking() {
        ChatProfileDTO p = new ChatProfileDTO();
        String prompt = new ChatContextBuilder(om).build("随便", p, ChatStrategy.ASK,
                List.of("city", "days", "startDate"), null, "", List.of(), false);
        assertTrue(prompt.contains("[可自然推进的一项] 目的地城市"));
        assertFalse(prompt.contains("大概玩几天"));
        assertFalse(prompt.contains("[还缺的信息]"));

        String datePrompt = new ChatContextBuilder(om).build("还没定", new ChatProfileDTO(),
                ChatStrategy.ASK, List.of("startDate"), null, "", List.of(), false);
        assertTrue(datePrompt.contains("不要替用户编日期、周末或星期"));
    }

    @Test
    void contextBuilderKeepsBareAcknowledgementMovingForward() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setCity("杭州");
        String prompt = new ChatContextBuilder(om).build(
                "嗯", p, ChatStrategy.SUGGEST, List.of("days", "startDate"),
                null, "", List.of(), false,
                "好，那就先按杭州来规划。后面你想到具体日期、天数再补一句。", true);

        assertTrue(prompt.contains("[上一条助手回复]"));
        assertTrue(prompt.contains("[本轮是纯确认]"));
        assertTrue(prompt.contains("不要原样复用"));
        assertTrue(prompt.contains("不要默认天数"));
        assertTrue(prompt.contains("profile_patch 留空"));
    }

    @Test
    void smalltalkDoesNotKeepPushingArrivalDate() {
        ChatIntentRouter router = new ChatIntentRouter();
        String reply = router.smalltalkReply("好的", "成都", List.of("startDate"));
        assertFalse(reply.contains("哪天"));
        assertFalse(reply.contains("哪一天"));
        assertTrue(reply.contains("日期没定也没关系"));
    }

    @Test
    void bareAcknowledgementIsDistinguishedFromGreeting() {
        ChatIntentRouter router = new ChatIntentRouter();
        assertTrue(router.isBareAcknowledgement("嗯"));
        assertTrue(router.isBareAcknowledgement("好的。"));
        assertTrue(router.isBareAcknowledgement("OK"));
        assertFalse(router.isBareAcknowledgement("你好"));
        assertFalse(router.isBareAcknowledgement("好，那就去成都"));
    }

    @Test
    void offTopicReplyStaysFirmButDoesNotRepeatDateQuestion() {
        ChatIntentRouter router = new ChatIntentRouter();
        String reply = router.offTopicReply("成都");
        assertTrue(reply.contains("帮不上忙"));
        assertFalse(reply.contains("哪天"));
    }

    private ChatProfileDTO readyProfile() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setCity("成都");
        p.setDays(3);
        p.setStartDate("2026-11-20");
        p.setEndDate("2026-11-22");
        return p;
    }
}
