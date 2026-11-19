package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.mapper.ItineraryDayMapper;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ItineraryNodeMapper;
import com.xingji.mapper.PlaceMapper;
import com.xingji.mapper.RouteSegmentMapper;
import com.xingji.map.AmapClient;
import com.xingji.map.AmapNavigationBuilder;
import com.xingji.map.RouteResult;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ItineraryDay;
import com.xingji.pojo.ItineraryNode;
import com.xingji.pojo.Place;
import com.xingji.pojo.RouteSegment;
import com.xingji.service.RouteService;
import com.xingji.vo.RouteSegmentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RouteServiceImpl implements RouteService {

    private final ItineraryMapper itineraryMapper;
    private final ItineraryDayMapper dayMapper;
    private final ItineraryNodeMapper nodeMapper;
    private final PlaceMapper placeMapper;
    private final RouteSegmentMapper segmentMapper;
    private final AmapClient amapClient;
    private final AmapNavigationBuilder navBuilder;

    @Override
    public List<RouteSegmentVO> list(Integer itineraryId, Integer userId) {
        requireOwnedItinerary(itineraryId, userId);
        List<RouteSegment> segs = segmentMapper.findByItineraryId(itineraryId);
        List<RouteSegmentVO> out = new ArrayList<>();
        for (RouteSegment seg : segs) {
            out.add(toVO(seg));
        }
        return out;
    }

    @Override
    public List<RouteSegmentVO> refresh(Integer itineraryId, Integer userId, String mode) {
        requireOwnedItinerary(itineraryId, userId);
        String m = (mode == null || mode.isBlank()) ? AmapNavigationBuilder.MODE_CAR : mode;

        List<ItineraryDay> days = new ArrayList<>(dayMapper.findByItineraryId(itineraryId));
        days.sort(Comparator.comparing(ItineraryDay::getDayIndex, Comparator.nullsLast(Comparator.naturalOrder())));

        // 1) 先在事务/连接之外把每段都算完（含外部高德 HTTP）。
        //    若在 @Transactional 里逐段调 amap，网络耗时会长时间占用数据库连接，并发下打爆连接池。
        List<RouteSegment> computed = new ArrayList<>();
        for (ItineraryDay day : days) {
            List<ItineraryNode> nodes = new ArrayList<>(nodeMapper.findByDayId(day.getId()));
            nodes.sort(Comparator.comparing(ItineraryNode::getSeq, Comparator.nullsLast(Comparator.naturalOrder())));

            // 逐段：相邻两节点都有坐标才算
            for (int i = 0; i + 1 < nodes.size(); i++) {
                ItineraryNode a = nodes.get(i);
                ItineraryNode b = nodes.get(i + 1);
                Place pa = a.getPlaceId() == null ? null : placeMapper.findById(a.getPlaceId());
                Place pb = b.getPlaceId() == null ? null : placeMapper.findById(b.getPlaceId());
                String origin = pa == null ? null : navBuilder.positionOf(pa);
                String dest = pb == null ? null : navBuilder.positionOf(pb);
                if (origin == null || dest == null) continue;   // 缺坐标不瞎连

                RouteResult rr = amapClient.direction(origin, dest, m);
                RouteSegment seg = new RouteSegment();
                seg.setItineraryId(itineraryId);
                seg.setFromNodeId(a.getId());
                seg.setToNodeId(b.getId());
                if (rr != null) {
                    seg.setDistance(rr.getDistance());
                    seg.setDuration(rr.getDuration());
                    seg.setPolyline(rr.getPolyline());
                }
                computed.add(seg);
            }
        }

        // 2) 全部算完后，再一次性落库（纯本地写，快，不夹带网络）。
        segmentMapper.deleteByItineraryId(itineraryId);
        for (RouteSegment seg : computed) {
            segmentMapper.insert(seg);
        }
        return list(itineraryId, userId);
    }

    private void requireOwnedItinerary(Integer itineraryId, Integer userId) {
        Itinerary it = itineraryMapper.findById(itineraryId);
        if (it == null) {
            throw new BizException(404, "行程不存在");
        }
        if (!it.getUserId().equals(userId)) {
            throw new BizException(403, "无权查看该行程路线");
        }
    }

    /** 路线段 → VO：补起终点名称、第几天、可点的高德导航链接。 */
    private RouteSegmentVO toVO(RouteSegment seg) {
        RouteSegmentVO vo = new RouteSegmentVO();
        vo.setId(seg.getId());
        vo.setItineraryId(seg.getItineraryId());
        vo.setFromNodeId(seg.getFromNodeId());
        vo.setToNodeId(seg.getToNodeId());
        vo.setDistance(seg.getDistance());
        vo.setDuration(seg.getDuration());
        vo.setPolyline(seg.getPolyline());

        ItineraryNode from = nodeMapper.findById(seg.getFromNodeId());
        ItineraryNode to = nodeMapper.findById(seg.getToNodeId());
        if (from != null) vo.setFromName(from.getPlaceName());
        if (to != null) vo.setToName(to.getPlaceName());

        if (from != null) {
            ItineraryDay d = dayMapper.findById(from.getDayId());
            if (d != null) vo.setDayIndex(d.getDayIndex());
        }

        String origin = placePosition(from);
        String dest = placePosition(to);
        if (origin != null && dest != null) {
            vo.setNavUrl(navBuilder.routeUrl(origin, vo.getFromName(), dest, vo.getToName(),
                    AmapNavigationBuilder.MODE_CAR));
        }
        return vo;
    }

    /** 节点 → GCJ-02 坐标串（无地点/无坐标返回 null）。 */
    private String placePosition(ItineraryNode node) {
        if (node == null || node.getPlaceId() == null) return null;
        Place p = placeMapper.findById(node.getPlaceId());
        return p == null ? null : navBuilder.positionOf(p);
    }
}
