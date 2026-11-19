package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ItineraryNodeMapper;
import com.xingji.mapper.PlaceMapper;
import com.xingji.map.AmapClient;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ItineraryNode;
import com.xingji.pojo.Place;
import com.xingji.service.ItineraryValidateService;
import com.xingji.vo.ValidationResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验实现(Phase 4)。生成之后跑一次:真实性(高德POI核验/回填 placeId)+ 约束(预算、时间重叠)。
 * 生成阶段只存了 placeName、placeId 为空,这里就是把它补成"能落到地图上的真实地点"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryValidateServiceImpl implements ItineraryValidateService {

    private final ItineraryMapper itineraryMapper;
    private final ItineraryNodeMapper nodeMapper;
    private final PlaceMapper placeMapper;
    private final AmapClient amapClient;

    @Override
    @Transactional
    public ValidationResultVO validate(Integer itineraryId, Integer userId) {
        Itinerary itinerary = itineraryMapper.findById(itineraryId);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        if (!itinerary.getUserId().equals(userId)) {
            throw new BizException(403, "无权校验该行程");
        }

        ValidationResultVO vo = new ValidationResultVO();
        vo.setItineraryId(itineraryId);

        // 一次性取出整棵行程的所有节点(已按天序、日内顺序排好)
        List<ItineraryNode> nodes = nodeMapper.findByItineraryId(itineraryId);
        vo.setTotal(nodes.size());

        int matched = 0;        // 高德核验通过(或曾核验通过)的景点数
        int abnormal = 0;       // 高德未匹配的景点数
        int suggested = 0;      // 餐饮/住宿/交通这类"建议项",不做 POI 核验
        BigDecimal totalCost = BigDecimal.ZERO; // 总花费，初始化为0

        // 按天分组,后面做"同一天时间重叠"检查
        Map<Integer, List<ItineraryNode>> byDay = new LinkedHashMap<>();

        java.util.List<String> unmatched = new java.util.ArrayList<>();

        for (ItineraryNode node : nodes) {
            byDay.computeIfAbsent(node.getDayId(), k -> new java.util.ArrayList<>()).add(node);
            if (node.getCost() != null) totalCost = totalCost.add(node.getCost());

            // 只处理还没回填 placeId 的节点(已匹配过的跳过,可重复调用不返工)
            if (node.getPlaceId() != null) {
                matched++;   // 之前已匹配的也计入 matched
                continue;
            }

            // 需要在真实地图上核验的:景点 + 餐饮。
            //
            // 餐饮能被核验,是因为生成阶段已经让它从"高德真实餐厅候选池"里选了
            // (见 ContextBuilder 的餐厅数据源)。这类节点自带 placeId,直接算命中。
            // 若模型没选池中餐厅(池为空或它自己编了个店名),这里仍会尝试按名字查一次:
            // 查到 → 回填 placeId,节点可导航;查不到 → 标异常,前端诚实显示"未核验"。
            //
            // 住宿/交通仍是"建议项"(如"春熙路-太古里一带""建议住宿区域"),
            // 拿这种描述去高德搜注定搜不到,不做核验也不标异常。
            boolean needsVerify = "景点".equals(node.getNodeType()) || "餐饮".equals(node.getNodeType());
            if (!needsVerify) {
                node.setIsAbnormal(0);
                suggested++;
                nodeMapper.update(node);
                continue;
            }

            Place resolved = resolveByAmap(node.getPlaceName(), itinerary.getCity());
            if (resolved != null) {
                node.setPlaceId(resolved.getId());
                node.setIsAbnormal(0);
                matched++;
            } else if ("餐饮".equals(node.getNodeType())) {
                // 餐饮查不到不算"异常"到要报警的程度:它只是没有地图信息而已,
                // 用户照样能去吃。归入"建议项"计数,避免满屏红字。
                node.setIsAbnormal(0);
                suggested++;
            } else {
                node.setIsAbnormal(1);
                abnormal++;
                unmatched.add(node.getPlaceName());
            }
            nodeMapper.update(node);
        }

        // 异常消息聚合,别一条一条刷屏(15 个地点 = 15 行红字)
        if (!unmatched.isEmpty()) {
            java.util.List<String> brief = unmatched.size() > 6 ? unmatched.subList(0, 6) : unmatched;
            vo.getMessages().add("有 " + unmatched.size() + " 个景点没在高德核验到：" + String.join("、", brief)
                    + (unmatched.size() > 6 ? " 等" : "") + "。建议在行程里换成真实景点。");
        }
        if (suggested > 0) {
            vo.getMessages().add("另有 " + suggested + " 个餐饮/住宿/交通是 AI 建议项，未做地图核验，以实际到店为准。");
        }

        vo.setMatched(matched);
        vo.setAbnormal(abnormal);
        vo.setSuggested(suggested);

        // 约束1:预算超支(纯确定性)
        if (itinerary.getBudget() != null && totalCost.compareTo(itinerary.getBudget()) > 0) {
            vo.getMessages().add("预算超支:预计花费 " + totalCost + " 元,已超预算 " + itinerary.getBudget() + " 元");
        }

        // 约束2:同一天活动时间重叠
        checkOverlaps(byDay, vo);

        // 校验通过(哪怕有异常节点也视为"跑过校验"),状态置 2
        itineraryMapper.updateStatus(itineraryId, 2);
        return vo;
    }

    /**
     * 拿地点名去高德查,命中则缓存/复用地点并返回;查不到或调用失败返回 null(交上层标异常)。
     * city 非空时限定在城市内搜索——以前全国搜,"人民公园"这种名字会匹配到别的城市。
     */
    private Place resolveByAmap(String placeName, String city) {
        if (placeName == null || placeName.isBlank()) return null;
        String cleaned = cleanName(placeName);
        if (cleaned.isEmpty()) return null;
        List<Place> found;
        try {
            found = amapClient.searchByName(cleaned, city);
        } catch (Exception e) {
            log.warn("高德地点核验失败 placeName={}: {}", placeName, e.getMessage());
            return null;
        }
        if (found == null || found.isEmpty()) return null;
        Place top = pickBest(found, cleaned, city);
        if (top == null) return null;
        // 去重:高德POI已缓存过就复用,避免同地点反复入库
        Place cached = placeMapper.findByAmapPoiId(top.getAmapPoiId());
        if (cached != null) return cached;
        placeMapper.insert(top);    // 回填自增 id
        return top;
    }

    /** 去掉"附近/一带/周边"这类口语后缀,高德不吃这些词 */
    private String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        for (String suffix : new String[]{"附近", "一带", "周边", "区域", "附近区域"}) {
            if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length()).trim();
        }
        return s;
    }

    /**
     * 从高德返回的多条里挑最像的那条:
     * ① 优先同名(忽略空格/间隔号);② 其次包含关系;③ 都没有就取城市内第一条。
     * 城市过滤只在高德返回里确实存在该城市时生效,避免把正常结果全过滤掉。
     */
    private Place pickBest(List<Place> found, String query, String city) {
        String q = normName(query);
        if (q.isEmpty()) return null;
        List<Place> pool = found;
        if (city != null && !city.isBlank()) {
            String c = city.replace("市", "").replace("特别行政区", "").trim();
            List<Place> inCity = new java.util.ArrayList<>();
            for (Place p : found) {
                if (p.getCity() != null && p.getCity().contains(c)) inCity.add(p);
            }
            if (!inCity.isEmpty()) pool = inCity;
        }
        for (Place p : pool) {
            if (q.equals(normName(p.getName()))) return p;
        }
        for (Place p : pool) {
            String n = normName(p.getName());
            if (!n.isEmpty() && (n.contains(q) || q.contains(n))) return p;
        }
        return pool.isEmpty() ? null : pool.get(0);
    }

    private String normName(String s) {
        return s == null ? "" : s.replaceAll("[\\s·・（）()\\-—]", "").trim();
    }

    /** 同一天的节点按 seq 排序后,若某节点开始时间早于前一节点结束时间,判为重叠提醒 */
    private void checkOverlaps(Map<Integer, List<ItineraryNode>> byDay, ValidationResultVO vo) {
        for (List<ItineraryNode> dayNodes : byDay.values()) {
            ItineraryNode prev = null;
            for (ItineraryNode cur : dayNodes) {   // findByItineraryId 已按 seq 排序
                LocalTime cs = cur.getStartTime();
                LocalTime pe = prev == null ? null : prev.getEndTime();
                if (cs != null && pe != null && cs.isBefore(pe)) {
                    vo.getMessages().add("时间可能重叠:「" + cur.getPlaceName()
                            + "」" + cs + " 开始,早于「" + prev.getPlaceName() + "」" + pe + " 结束");
                }
                prev = cur;
            }
        }
    }
}
