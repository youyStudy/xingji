package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.dto.DayEditDTO;
import com.xingji.dto.ItineraryEditDTO;
import com.xingji.dto.NodeEditDTO;
import com.xingji.mapper.ItineraryDayMapper;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ItineraryNodeMapper;
import com.xingji.mapper.PlaceMapper;
import com.xingji.map.AmapNavigationBuilder;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ItineraryDay;
import com.xingji.pojo.ItineraryNode;
import com.xingji.pojo.Place;
import com.xingji.service.ItineraryService;
import com.xingji.vo.ItineraryDetailVO;
import com.xingji.vo.ItineraryDayVO;
import com.xingji.vo.ItineraryNodeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItineraryServiceImpl implements ItineraryService {

    private final ItineraryMapper itineraryMapper;
    private final com.xingji.service.ChatService chatService;   // 删行程时连带清理"孤儿会话"
    private final ItineraryDayMapper dayMapper;
    private final ItineraryNodeMapper nodeMapper;
    private final PlaceMapper placeMapper;                  // 节点 placeId → 真实坐标
    private final AmapNavigationBuilder navBuilder;         // 坐标 → 高德导航链接

    @Override
    public ItineraryDetailVO getDetail(Integer id, Integer userId) {
        Itinerary itinerary = itineraryMapper.findById(id);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        // 数据隔离:这条行程不是你的,不许看
        if (!itinerary.getUserId().equals(userId)) {
            throw new BizException(403, "无权访问该行程");
        }
        return assemble(itinerary);   // 复用同一套组装逻辑
    }

    @Override
    public ItineraryDetailVO getDetailForShare(Integer itineraryId) {
        Itinerary itinerary = itineraryMapper.findById(itineraryId);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        // 分享场景:访问者不是行程主人,这里**不校验 userId**,能否访问由上层 token 决定
        return assemble(itinerary);
    }

    /** 把一棵行程树(行程→天→节点)组装成 DetailVO。getDetail 与 getDetailForShare 共用 */
    private ItineraryDetailVO assemble(Itinerary itinerary) {
        List<ItineraryDay> dayList = dayMapper.findByItineraryId(itinerary.getId());
        List<ItineraryDayVO> dayVOList = new ArrayList<>();
        for (ItineraryDay day : dayList) {
            ItineraryDayVO dayVO = new ItineraryDayVO();
            dayVO.setDay(day);

            List<ItineraryNode> rawNodes = nodeMapper.findByDayId(day.getId());
            List<ItineraryNodeVO> nodeVOs = new ArrayList<>();
            List<Place> locatedPlaces = new ArrayList<>();   // 按 seq 收集有坐标的点,用于拼当天路线
            for (ItineraryNode node : rawNodes) {
                ItineraryNodeVO nv = toNodeVO(node);
                nodeVOs.add(nv);
                if (nv.isLocated()) {
                    Place p = placeMapper.findById(node.getPlaceId());
                    if (p != null) locatedPlaces.add(p);
                }
            }
            dayVO.setNodes(nodeVOs);
            // 当天相邻两点的分段导航:用户点"今日路线"时逐段跟随
            dayVO.setRouteLinks(navBuilder.dayRouteLinks(locatedPlaces, AmapNavigationBuilder.MODE_CAR));
            dayVOList.add(dayVO);
        }
        ItineraryDetailVO vo = new ItineraryDetailVO();
        vo.setItinerary(itinerary);
        vo.setDays(dayVOList);
        return vo;
    }

    /**
     * 节点 → 带地图能力的 VO。
     *
     * 只有经过高德核验、回填了 placeId 的节点才有坐标;餐饮/住宿/交通 这类 AI 建议项
     * (以及核验失败的景点)没有 placeId,这里如实返回 located=false 并说明原因,
     * 前端应显示"未核验"而不是在图上画一个猜出来的点。
     */
    private ItineraryNodeVO toNodeVO(ItineraryNode node) {
        ItineraryNodeVO nv = new ItineraryNodeVO();
        nv.setNode(node);

        Place place = node.getPlaceId() == null ? null : placeMapper.findById(node.getPlaceId());
        String position = navBuilder.positionOf(place);
        if (position == null) {
            nv.setLocated(false);
            // 分清两种"没有坐标",让用户知道该怎么处理
            if (Integer.valueOf(1).equals(node.getIsAbnormal())) {
                nv.setLocationNote("这个地点没在高德核验到，可能不是真实地名");
            } else if ("景点".equals(node.getNodeType())) {
                nv.setLocationNote("尚未核验");
            } else {
                nv.setLocationNote("AI 建议项，未做地图核验，以实际到店为准");
            }
            return nv;
        }

        // 坐标是 GCJ-02(已转回),可直接给高德地图/导航用
        double[] gcj = com.xingji.util.GeoUtil.wgs84ToGcj02(
                place.getLongitude().doubleValue(), place.getLatitude().doubleValue());
        nv.setLng(java.math.BigDecimal.valueOf(gcj[0]));
        nv.setLat(java.math.BigDecimal.valueOf(gcj[1]));
        nv.setNavUrl(navBuilder.markerUrl(position, place.getName()));
        nv.setLocated(true);
        return nv;
    }

    @Override
    public List<Itinerary> listByUser(Integer userId) {
        return itineraryMapper.findByUserId(userId);
    }

    @Override
    public void delete(Integer id, Integer userId) {
        com.xingji.pojo.Itinerary itin = ownedItinerary(id, userId);   // 拿 session_id 用于连带清理
        itineraryMapper.softDelete(id, userId);
        // 会话是"聊出这条行程"的过程记录:行程删了、且该会话下再无其他存活行程,
        // 过程记录一并删除(消息级联),免得历史会话里留着打不开行程的空壳对话;
        // 同一会话还生成过别的行程则保留会话
        if (itin.getSessionId() != null
                && itineraryMapper.countAliveBySessionId(itin.getSessionId()) == 0) {
            chatService.deleteSession(itin.getSessionId(), userId);
        }
    }

    @Override
    public void pin(Integer id, Integer userId, Integer pinned) {
        ownedItinerary(id, userId);   // 归属校验:只能置顶自己的行程
        itineraryMapper.updatePinned(id, userId, pinned != null && pinned == 1 ? 1 : 0);
    }

    // ===== 行程编辑(Phase 2b) =====

    @Override
    public void updateItinerary(Integer id, Integer userId, ItineraryEditDTO dto) {
        Itinerary itinerary = ownedItinerary(id, userId);   // 归属校验
        itinerary.setTitle(dto.getTitle());
        itinerary.setStartDate(dto.getStartDate());
        itinerary.setDays(dto.getDays());
        itinerary.setBudget(dto.getBudget());
        itineraryMapper.update(itinerary);
    }

    @Override
    public Integer addDay(Integer itineraryId, Integer userId, DayEditDTO dto) {
        ownedItinerary(itineraryId, userId);   // 先确认这行程是我的
        ItineraryDay day = new ItineraryDay();
        day.setItineraryId(itineraryId);
        day.setDayIndex(dto.getDayIndex());
        day.setTheme(dto.getTheme());
        day.setDate(dto.getDate());
        dayMapper.insert(day);                 // 回填 id
        return day.getId();
    }

    @Override
    public void updateDay(Integer dayId, Integer userId, DayEditDTO dto) {
        ItineraryDay day = ownedDay(dayId, userId);   // day→itinerary→owner
        day.setDayIndex(dto.getDayIndex());
        day.setTheme(dto.getTheme());
        day.setDate(dto.getDate());
        dayMapper.update(day);
    }

    @Override
    @Transactional   // 删天 + 删它下面所有节点,两步要么都成要么都不成,不留"孤儿节点"
    public void deleteDay(Integer dayId, Integer userId) {
        ownedDay(dayId, userId);
        nodeMapper.deleteByDayId(dayId);   // 先清子节点
        dayMapper.deleteById(dayId);       // 再删这天
    }

    @Override
    public Integer addNode(Integer dayId, Integer userId, NodeEditDTO dto) {
        ownedDay(dayId, userId);           // day→itinerary→owner
        ItineraryNode node = new ItineraryNode();
        node.setDayId(dayId);
        applyNodeDto(node, dto);
        node.setIsAbnormal(0);             // 用户手填默认正常,是否异常交给校验阶段判定
        nodeMapper.insert(node);           // 回填 id
        return node.getId();
    }

    @Override
    public void updateNode(Integer nodeId, Integer userId, NodeEditDTO dto) {
        ItineraryNode node = ownedNode(nodeId, userId);   // node→day→itinerary→owner
        applyNodeDto(node, dto);
        nodeMapper.update(node);
    }

    @Override
    public void deleteNode(Integer nodeId, Integer userId) {
        ownedNode(nodeId, userId);
        nodeMapper.deleteById(nodeId);
    }

    /** 把 NodeEditDTO 的字段拷进实体,addNode/updateNode 共用 */
    private void applyNodeDto(ItineraryNode node, NodeEditDTO dto) {
        node.setPlaceId(dto.getPlaceId());
        node.setPlaceName(dto.getPlaceName());
        node.setNodeType(dto.getNodeType());
        node.setSeq(dto.getSeq());
        node.setStartTime(dto.getStartTime());
        node.setEndTime(dto.getEndTime());
        node.setCost(dto.getCost());
    }

    // ===== 归属校验辅助:层层向上找到行程,比对 userId,防越权 =====

    /** 校验该行程属于当前用户,返回行程;不存在→404,不归你→403 */
    private Itinerary ownedItinerary(Integer itineraryId, Integer userId) {
        Itinerary itinerary = itineraryMapper.findById(itineraryId);
        if (itinerary == null) {
            throw new BizException(404, "行程不存在");
        }
        if (!itinerary.getUserId().equals(userId)) {
            throw new BizException(403, "无权操作该行程");
        }
        return itinerary;
    }

    /** 由 dayId 上溯校验归属,返回天 */
    private ItineraryDay ownedDay(Integer dayId, Integer userId) {
        ItineraryDay day = dayMapper.findById(dayId);
        if (day == null) {
            throw new BizException(404, "行程日不存在");
        }
        ownedItinerary(day.getItineraryId(), userId);   // 借行程归属校验
        return day;
    }

    /** 由 nodeId 上溯校验归属,返回节点 */
    private ItineraryNode ownedNode(Integer nodeId, Integer userId) {
        ItineraryNode node = nodeMapper.findById(nodeId);
        if (node == null) {
            throw new BizException(404, "活动节点不存在");
        }
        ownedDay(node.getDayId(), userId);   // 借行程日归属校验(内部再上溯到行程)
        return node;
    }
}
