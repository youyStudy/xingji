package com.xingji.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.AnchorStateDTO;
import com.xingji.dto.ChatContextDTO;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ConstraintType;
import com.xingji.dto.GeneratedItineraryDTO;
import com.xingji.dto.TimePeriod;
import com.xingji.dto.TravelConstraintDTO;
import com.xingji.pojo.ChatSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 规则回归：日期证据、日期一致性、约束抽取/过滤、上下文迁移。 */
class P0RulesTests {

    private final ObjectMapper om = new ObjectMapper();

    // ==================== 日期证据 ====================

    @Test
    void fuzzyAfternoonDoesNotBecomeExactTimeOrDate() {
        DateEvidence ev = DateEvidence.of("我下午到泉州");
        assertFalse(ev.hasAnyDateSignal(), "只说下午到，不应产生日期");
        assertFalse(ev.hasExplicitTime(), "只说下午到，不应产生具体时刻");
        assertEquals(TimePeriod.AFTERNOON, ev.timePeriod());
        assertFalse(ev.hasDate(LocalDate.now().toString()), "不能把今天当到达日");
    }

    @Test
    void explicitDatesAreRecognized() {
        LocalDate today = LocalDate.now();
        assertTrue(DateEvidence.of("11月20号到成都").hasDate(today.getYear() + "-11-20"));
        assertTrue(DateEvidence.of("明天下午5点到").hasDate(today.plusDays(1).toString()));
        assertTrue(DateEvidence.of("明天下午5点到").hasTime("17:00"));
        assertTrue(DateEvidence.of("下个月15号出发").hasDate(today.plusMonths(1).withDayOfMonth(15).toString()));
    }

    @Test
    void preferenceStatementProducesNoDate() {
        DateEvidence ev = DateEvidence.of("我不想爬山");
        assertFalse(ev.hasAnyDateSignal());
        assertFalse(ev.hasExplicitTime());
    }

    // ==================== 日期一致性 ====================

    @Test
    void arrivalAfterEndIsInconsistent() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setStartDate("2026-11-13");
        p.setEndDate("2026-11-16");
        AnchorStateDTO arrival = new AnchorStateDTO();
        arrival.setDate("2026-11-22");
        p.setArrival(arrival);
        assertFalse(DateRules.consistent(p));
    }

    @Test
    void consistencyAlignsStartAndEnd() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setStartDate("2026-11-13");
        p.setEndDate("2026-11-16");
        AnchorStateDTO arrival = new AnchorStateDTO();
        arrival.setDate("2026-11-22");
        p.setArrival(arrival);
        List<String> corrections = new ArrayList<>();
        DateRules.applyConsistency(p, corrections);
        assertEquals("2026-11-22", p.getStartDate());
        assertEquals("2026-11-22", p.getEndDate());
        assertFalse(corrections.isEmpty());
    }

    @Test
    void departureBeforeStartIsCleared() {
        ChatProfileDTO p = new ChatProfileDTO();
        p.setStartDate("2026-11-20");
        AnchorStateDTO dep = new AnchorStateDTO();
        dep.setDate("2026-11-15");
        p.setDeparture(dep);
        DateRules.applyConsistency(p, new ArrayList<>());
        assertNull(p.getDeparture().getDate());
    }

    // ==================== 约束 ====================

    @Test
    void safetyAndExclusionAreClassified() {
        List<TravelConstraintDTO> c1 = ConstraintExtractor.apply("我女朋友海鲜过敏", null);
        assertEquals(1, c1.size());
        assertEquals(ConstraintType.SAFETY, c1.get(0).getType());
        assertEquals("food", c1.get(0).getScope());

        List<TravelConstraintDTO> c2 = ConstraintExtractor.apply("她不想爬山", null);
        assertEquals(1, c2.size());
        assertEquals(ConstraintType.EXCLUSION, c2.get(0).getType());
        assertEquals("activity", c2.get(0).getScope());
    }

    @Test
    void modificationDeactivatesOldConstraint() {
        List<TravelConstraintDTO> existing = ConstraintExtractor.apply("以前不想爬山", null);
        assertTrue(existing.get(0).getActive());
        ConstraintExtractor.apply("之前说不爬山，这次可以接受轻松山路", existing);
        assertFalse(existing.get(0).getActive());
    }

    @Test
    void sameTopicConstraintsAreDeduplicated() {
        List<TravelConstraintDTO> c = ConstraintExtractor.apply("海鲜过敏，别安排海鲜", null);
        assertEquals(1, c.size(), "同一类海鲜约束不应并存三条");
        assertEquals(ConstraintType.SAFETY, c.get(0).getType());

        List<TravelConstraintDTO> h = ConstraintExtractor.apply("不想爬山", null);
        ConstraintExtractor.apply("她也不想爬山", h);
        assertEquals(1, h.size());
        assertEquals("不想爬山", h.get(0).getContent());
    }

    @Test
    void patchCanDeactivateConstraint() {
        List<TravelConstraintDTO> existing = ConstraintExtractor.apply("海鲜过敏", null);
        TravelConstraintDTO patch = new TravelConstraintDTO();
        patch.setContent("海鲜过敏");
        patch.setActive(false);
        List<TravelConstraintDTO> merged = ConstraintExtractor.mergePatch(existing, List.of(patch));
        assertFalse(merged.get(0).getActive());
    }

    @Test
    void validatorDropsHikingAndSeafoodButKeepsZhongshanRoad() {
        GeneratedItineraryDTO gen = new GeneratedItineraryDTO();
        GeneratedItineraryDTO.GeneratedDay day = new GeneratedItineraryDTO.GeneratedDay();
        day.setDayIndex(1);
        day.setNodes(new ArrayList<>(List.of(
                node("景点", "清源山"),
                node("景点", "中山路"),
                node("餐饮", "海鲜大排档"),
                node("景点", "西街"),
                node("餐饮", "侯阿婆肉粽")
        )));
        gen.setDays(new ArrayList<>(List.of(day)));

        List<TravelConstraintDTO> constraints = new ArrayList<>();
        constraints.addAll(ConstraintExtractor.apply("不想爬山", null));
        constraints.addAll(ConstraintExtractor.apply("海鲜过敏", null));

        int dropped = new ConstraintValidator().enforce(gen, constraints);
        assertEquals(2, dropped);
        List<String> names = new ArrayList<>();
        for (GeneratedItineraryDTO.GeneratedNode n : gen.getDays().get(0).getNodes()) {
            names.add(n.getPlaceName());
        }
        assertEquals(List.of("中山路", "西街", "侯阿婆肉粽"), names);
    }

    private GeneratedItineraryDTO.GeneratedNode node(String type, String name) {
        GeneratedItineraryDTO.GeneratedNode n = new GeneratedItineraryDTO.GeneratedNode();
        n.setNodeType(type);
        n.setPlaceName(name);
        return n;
    }

    // ==================== 上下文迁移 ====================

    @Test
    void legacyProfileMigratesWithoutInventingData() {
        ChatSession session = new ChatSession();
        session.setId(1);
        session.setContext("{\"city\":\"成都\",\"days\":3,\"avoid\":[\"不爬山\",\"海鲜过敏\"]}");
        ChatContextService service = new ChatContextService(om);

        ChatContextDTO ctx = service.read(session);
        assertEquals(ChatContextDTO.CURRENT_VERSION, ctx.getVersion());
        assertEquals("成都", ctx.getProfile().getCity());
        assertEquals(3, ctx.getProfile().getDays());
        assertNull(ctx.getProfile().getStartDate(), "迁移不能虚构日期");
        assertNotNull(ctx.getConversationState());
        assertEquals(2, ctx.getProfile().getConstraints().size());
        assertEquals(ConstraintType.EXCLUSION, ctx.getProfile().getConstraints().get(0).getType());
    }

    @Test
    void versionedContextRoundTripIsStable() {
        ChatContextService service = new ChatContextService(om);
        ChatProfileDTO p = new ChatProfileDTO();
        p.setCity("泉州");
        p.setConstraints(ConstraintExtractor.apply("海鲜过敏", null));
        String json = service.write(p, null);

        ChatSession session = new ChatSession();
        session.setId(2);
        session.setContext(json);
        ChatContextDTO ctx = service.read(session);
        assertEquals("泉州", ctx.getProfile().getCity());
        assertEquals(1, ctx.getProfile().getConstraints().size());

        String again = service.write(ctx);
        session.setContext(again);
        ChatContextDTO ctx2 = service.read(session);
        assertEquals("泉州", ctx2.getProfile().getCity());
        assertEquals(1, ctx2.getProfile().getConstraints().size());
    }
}