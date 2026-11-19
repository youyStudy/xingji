package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.service.RouteService;
import com.xingji.vo.RouteSegmentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 路线/导航接口（REQ-012，需登录）。
 * GET  读取已算好的路线段；POST refresh 重新调高德路径规划并落库。
 */
@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    /** 某行程的路线段列表（已算好的） */
    @GetMapping("/{itineraryId}")
    public Result<List<RouteSegmentVO>> list(@PathVariable Integer itineraryId) {
        Integer userId = UserContext.getUserId();
        return Result.ok(routeService.list(itineraryId, userId));
    }

    /** 重新计算并保存（mode: car/walk，默认 car），返回最新结果 */
    @PostMapping("/{itineraryId}/refresh")
    public Result<List<RouteSegmentVO>> refresh(@PathVariable Integer itineraryId,
                                                @RequestParam(required = false) String mode) {
        Integer userId = UserContext.getUserId();
        return Result.ok(routeService.refresh(itineraryId, userId, mode));
    }
}
