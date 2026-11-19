package com.xingji.service;

import com.xingji.dto.DayEditDTO;
import com.xingji.dto.ItineraryEditDTO;
import com.xingji.dto.NodeEditDTO;
import com.xingji.pojo.Itinerary;
import com.xingji.vo.ItineraryDetailVO;

import java.util.List;

public interface ItineraryService {

    ItineraryDetailVO getDetail(Integer id, Integer userId);

    List<Itinerary> listByUser(Integer userId);

    void delete(Integer id, Integer userId);

    /** 置顶/取消置顶行程(列表排序用) */
    void pin(Integer id, Integer userId, Integer pinned);

    // ===== 行程编辑(Phase 2b) =====

    /** 改行程主体:标题/日期/天数/预算。改前先校验这条行程属于当前用户 */
    void updateItinerary(Integer id, Integer userId, ItineraryEditDTO dto);

    /** 给某行程新增一天,返回新 dayId */
    Integer addDay(Integer itineraryId, Integer userId, DayEditDTO dto);

    /** 修改某一天(第几天/主题/日期) */
    void updateDay(Integer dayId, Integer userId, DayEditDTO dto);

    /** 删除某一天,连带删除它下面的所有节点(物理删) */
    void deleteDay(Integer dayId, Integer userId);

    /** 给某一天新增一个活动节点,返回新 nodeId */
    Integer addNode(Integer dayId, Integer userId, NodeEditDTO dto);

    /** 修改某个活动节点 */
    void updateNode(Integer nodeId, Integer userId, NodeEditDTO dto);

    /** 删除某个活动节点 */
    void deleteNode(Integer nodeId, Integer userId);

    /**
     * 分享用:按行程 id 取详情,但**不做 userId 越权校验**
     * (分享链接是公开的,访问者不是行程主人)。仅内部持有 token 校验后才调用。
     */
    ItineraryDetailVO getDetailForShare(Integer itineraryId);
}