package com.xingji.service;

import com.xingji.vo.RouteSegmentVO;

import java.util.List;

/** 路线/导航（REQ-012 相邻节点真实路线）。 */
public interface RouteService {

    /** 读取已算好的路线段 */
    List<RouteSegmentVO> list(Integer itineraryId, Integer userId);

    /** 重新计算并落库（按天相邻节点调高德路径规划），返回最新结果。mode: car/walk */
    List<RouteSegmentVO> refresh(Integer itineraryId, Integer userId, String mode);
}
