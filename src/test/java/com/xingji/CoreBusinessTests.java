package com.xingji;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.GenerateRequestDTO;
import com.xingji.dto.GeneratedItineraryDTO;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ItineraryNodeMapper;
import com.xingji.mapper.PlaceMapper;
import com.xingji.map.AmapClient;
import com.xingji.planner.ItineraryConstraintEnforcer;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ItineraryNode;
import com.xingji.pojo.Place;
import com.xingji.service.impl.ItineraryValidateServiceImpl;
import com.xingji.util.JsonRepair;
import com.xingji.vo.ValidationResultVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 核心业务逻辑单元测试：JSON 修复、行程硬约束、POI 名称匹配、时间冲突与预算校验。
 * 不启动 Spring 容器，外部依赖以 Mockito 桩替代。
 */
public class CoreBusinessTests {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 1. JSON 修复器 ====================

    @Test
    public void jsonRepair_quotesBareTimeValues() throws Exception {
        String broken = "{\"days\":[{\"nodes\":[{\"placeName\":\"宽窄巷子\",\"endTime\":17:00}]}]}";
        JsonNode node = mapper.readTree(JsonRepair.repair(broken));
        assertEquals("17:00", node.at("/days/0/nodes/0/endTime").asText());
    }

    @Test
    public void jsonRepair_stripsFenceAndChineseQuotesAndTrailingComma() throws Exception {
        String broken = "```json\n{“title”:“成都三日游”, “days”: [],}\n```";
        JsonNode node = mapper.readTree(JsonRepair.repair(broken));
        assertEquals("成都三日游", node.get("title").asText());
        assertTrue(node.get("days").isArray());
    }

    @Test
    public void jsonRepair_completesTruncatedOutput() throws Exception {
        String truncated = "{\"title\":\"成都三日游\",\"days\":[{\"dayIndex\":1,\"nodes\":[{\"placeName\":\"锦里\"";
        JsonNode node = mapper.readTree(JsonRepair.repair(truncated));
        assertEquals("成都三日游", node.get("title").asText());
        assertEquals("锦里", node.at("/days/0/nodes/0/placeName").asText());
    }

    @Test
    public void jsonRepair_extractsObjectFromProse() throws Exception {
        String raw = "好的，以下是行程：\n{\"title\":\"重庆两日游\",\"days\":[]}\n希望对你有帮助。";
        JsonNode node = mapper.readTree(JsonRepair.extractAndRepair(raw));
        assertEquals("重庆两日游", node.get("title").asText());
    }

    // ==================== 2. 行程硬约束 ====================

    private ItineraryConstraintEnforcer enforcer() {
        return new ItineraryConstraintEnforcer();
    }

    private GenerateRequestDTO req(int days, String pace, LocalDateTime arrival, LocalDateTime departure) {
        GenerateRequestDTO r = new GenerateRequestDTO();
        r.setDays(days);
        r.setPace(pace);
        if (arrival != null) {
            GenerateRequestDTO.TransitAnchor a = new GenerateRequestDTO.TransitAnchor();
            a.setDatetime(arrival);
            r.setArrival(a);
        }
        if (departure != null) {
            GenerateRequestDTO.TransitAnchor d = new GenerateRequestDTO.TransitAnchor();
            d.setDatetime(departure);
            r.setDeparture(d);
        }
        return r;
    }

    private GeneratedItineraryDTO.GeneratedNode node(String type, String name, String start) {
        GeneratedItineraryDTO.GeneratedNode n = new GeneratedItineraryDTO.GeneratedNode();
        n.setNodeType(type);
        n.setPlaceName(name);
        n.setStartTime(start);
        return n;
    }

    private GeneratedItineraryDTO.GeneratedDay day(int index, GeneratedItineraryDTO.GeneratedNode... nodes) {
        GeneratedItineraryDTO.GeneratedDay d = new GeneratedItineraryDTO.GeneratedDay();
        d.setDayIndex(index);
        d.setNodes(new ArrayList<>(Arrays.asList(nodes)));
        return d;
    }

    private GeneratedItineraryDTO gen(GeneratedItineraryDTO.GeneratedDay... days) {
        GeneratedItineraryDTO g = new GeneratedItineraryDTO();
        g.setDays(new ArrayList<>(Arrays.asList(days)));
        return g;
    }

    @Test
    public void enforcer_dropsNodesBeforeArrivalBuffer() {
        GeneratedItineraryDTO g = gen(day(1,
                node("景点", "人民公园", "09:00"),
                node("景点", "宽窄巷子", "16:00")));
        GenerateRequestDTO r = req(1, "适中",
                LocalDateTime.of(2026, 11, 1, 14, 0), null);

        int dropped = enforcer().enforce(g, r);

        assertEquals(1, dropped);
        assertEquals(1, g.getDays().get(0).getNodes().size());
        assertEquals("宽窄巷子", g.getDays().get(0).getNodes().get(0).getPlaceName());
    }

    @Test
    public void enforcer_dropsDepartureDayNodesAfterCutoff() {
        GeneratedItineraryDTO g = gen(day(2,
                node("景点", "锦里", "10:00"),
                node("餐饮", "火锅", "16:00")));
        GenerateRequestDTO r = req(2, "适中", null,
                LocalDateTime.of(2026, 11, 2, 18, 0));

        int dropped = enforcer().enforce(g, r);

        assertEquals(1, dropped);
        assertEquals("锦里", g.getDays().get(0).getNodes().get(0).getPlaceName());
    }

    @Test
    public void enforcer_dropsDuplicateAndExcessAttractions() {
        GeneratedItineraryDTO g = gen(
                day(1,
                        node("景点", "宽窄巷子", "09:00"),
                        node("景点", "锦里", "11:00"),
                        node("景点", "武侯祠", "13:00"),
                        node("景点", "杜甫草堂", "15:00")),
                day(2, node("景点", "宽窄巷子", "09:00")));
        GenerateRequestDTO r = req(2, "适中", null, null);

        int dropped = enforcer().enforce(g, r);

        assertEquals(2, dropped);                              // 第 4 个超上限 + 次日重复
        assertEquals(3, g.getDays().get(0).getNodes().size());
        assertEquals(0, g.getDays().get(1).getNodes().size());
    }

    @Test
    public void enforcer_capsLodgingByNightsAndSkipsLastDay() {
        GeneratedItineraryDTO g = gen(
                day(1, node("住宿", "春熙路", "20:00")),
                day(2, node("住宿", "太古里", "20:00"), node("住宿", "宽窄巷子", "21:00")),
                day(3, node("住宿", "锦里", "20:00")));
        GenerateRequestDTO r = req(3, "适中", null, null);

        int dropped = enforcer().enforce(g, r);

        assertEquals(2, dropped);                              // 同一天第二处住宿 + 末日住宿
        assertEquals(1, g.getDays().get(1).getNodes().size());
        assertEquals(0, g.getDays().get(2).getNodes().size());
    }

    @Test
    public void enforcer_limitsLunchAndDinnerPerDay() {
        GeneratedItineraryDTO g = gen(day(1,
                node("餐饮", "午餐一", "11:00"),
                node("餐饮", "午餐二", "12:30"),
                node("餐饮", "晚餐一", "18:00"),
                node("餐饮", "晚餐二", "19:30")));
        GenerateRequestDTO r = req(2, "适中", null, null);

        int dropped = enforcer().enforce(g, r);

        assertEquals(2, dropped);
        assertEquals(2, g.getDays().get(0).getNodes().size());
    }

    // ==================== 3. POI 名称匹配 ====================

    private ItineraryValidateServiceImpl validateService(ItineraryMapper im, ItineraryNodeMapper nm,
                                                         PlaceMapper pm, AmapClient amap) {
        return new ItineraryValidateServiceImpl(im, nm, pm, amap);
    }

    private Itinerary itinerary(String city, String budget) {
        Itinerary it = new Itinerary();
        it.setId(1);
        it.setUserId(1);
        it.setCity(city);
        it.setBudget(new BigDecimal(budget));
        return it;
    }

    private ItineraryNode travelNode(int dayId, int seq, String type, String name) {
        ItineraryNode n = new ItineraryNode();
        n.setId(seq);
        n.setDayId(dayId);
        n.setSeq(seq);
        n.setNodeType(type);
        n.setPlaceName(name);
        return n;
    }

    private Place place(int id, String name, String city) {
        Place p = new Place();
        p.setId(id);
        p.setName(name);
        p.setCity(city);
        p.setAmapPoiId("B0" + id);
        return p;
    }

    @Test
    public void validate_prefersExactPoiNameWithinCity() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode node = travelNode(1, 1, "景点", "春熙路");
        when(im.findById(1)).thenReturn(itinerary("成都", "5000"));
        when(nm.findByItineraryId(1)).thenReturn(Collections.singletonList(node));
        when(amap.searchByName("春熙路", "成都")).thenReturn(Arrays.asList(
                place(11, "春熙路步行街", "成都市"),
                place(12, "春熙路", "成都市")));
        when(pm.findByAmapPoiId(any())).thenReturn(null);

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertEquals(1, vo.getMatched());
        assertEquals(0, vo.getAbnormal());
        assertEquals(12, node.getPlaceId());
    }

    @Test
    public void validate_filtersCandidatesByCity() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode node = travelNode(1, 1, "景点", "人民公园");
        when(im.findById(1)).thenReturn(itinerary("成都", "5000"));
        when(nm.findByItineraryId(1)).thenReturn(Collections.singletonList(node));
        when(amap.searchByName("人民公园", "成都")).thenReturn(Arrays.asList(
                place(21, "人民公园", "北京市"),
                place(22, "人民公园", "成都市")));
        when(pm.findByAmapPoiId(any())).thenReturn(null);

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertEquals(1, vo.getMatched());
        assertEquals(22, node.getPlaceId());
    }

    @Test
    public void validate_marksUnmatchedAttractionAbnormal() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode node = travelNode(1, 1, "景点", "天空之城观景台");
        when(im.findById(1)).thenReturn(itinerary("成都", "5000"));
        when(nm.findByItineraryId(1)).thenReturn(Collections.singletonList(node));
        when(amap.searchByName(anyString(), anyString())).thenReturn(new ArrayList<>());

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertEquals(1, vo.getAbnormal());
        assertEquals(1, node.getIsAbnormal());
        assertTrue(vo.getMessages().get(0).contains("没在高德核验到"));
    }

    @Test
    public void validate_countsUnmatchedRestaurantAsSuggested() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode node = travelNode(1, 1, "餐饮", "巷子口小馆");
        when(im.findById(1)).thenReturn(itinerary("成都", "5000"));
        when(nm.findByItineraryId(1)).thenReturn(Collections.singletonList(node));
        when(amap.searchByName(anyString(), anyString())).thenReturn(new ArrayList<>());

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertEquals(0, vo.getAbnormal());
        assertEquals(1, vo.getSuggested());
        assertEquals(0, node.getIsAbnormal());
    }

    // ==================== 4. 时间冲突与预算 ====================

    @Test
    public void validate_reportsTimeOverlapWithinDay() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode first = travelNode(1, 1, "景点", "宽窄巷子");
        first.setPlaceId(31);
        first.setStartTime(LocalTime.of(9, 0));
        first.setEndTime(LocalTime.of(11, 0));
        ItineraryNode second = travelNode(1, 2, "景点", "锦里");
        second.setPlaceId(32);
        second.setStartTime(LocalTime.of(10, 30));
        second.setEndTime(LocalTime.of(12, 0));

        when(im.findById(1)).thenReturn(itinerary("成都", "5000"));
        when(nm.findByItineraryId(1)).thenReturn(Arrays.asList(first, second));

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertTrue(vo.getMessages().stream().anyMatch(m -> m.contains("时间可能重叠")));
    }

    @Test
    public void validate_reportsBudgetOverrun() {
        ItineraryMapper im = mock(ItineraryMapper.class);
        ItineraryNodeMapper nm = mock(ItineraryNodeMapper.class);
        PlaceMapper pm = mock(PlaceMapper.class);
        AmapClient amap = mock(AmapClient.class);

        ItineraryNode node = travelNode(1, 1, "景点", "都江堰");
        node.setPlaceId(41);
        node.setCost(new BigDecimal("1500"));
        node.setStartTime(LocalTime.of(9, 0));
        node.setEndTime(LocalTime.of(12, 0));

        when(im.findById(1)).thenReturn(itinerary("成都", "1000"));
        when(nm.findByItineraryId(1)).thenReturn(Collections.singletonList(node));

        ValidationResultVO vo = validateService(im, nm, pm, amap).validate(1, 1);

        assertTrue(vo.getMessages().stream().anyMatch(m -> m.contains("预算超支")));
    }
}
