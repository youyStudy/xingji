package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.dto.InspirationDTO;
import com.xingji.dto.SpotRecommendDTO;
import com.xingji.service.PlaceService;
import com.xingji.vo.CityVO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/place")
public class PlaceController {

    private final PlaceService placeService;

    /** 某城市的真实景点浏览（高德"风景名胜"POI，带评分/价格/开放时间/图片） */
    @GetMapping("/recommend")
    public Result<List<SpotVO>> recommend(@RequestParam String city){
        return Result.ok(placeService.browseSpots(city));
    }

    /** AI 智能推荐：按"城市 + 偏好"让模型从真实景点里选点并写推荐理由 */
    @PostMapping("/recommend/smart")
    public Result<List<SpotVO>> recommendSmart(@RequestBody SpotRecommendDTO dto){
        return Result.ok(placeService.smartRecommend(dto));
    }

    /** 全国地级市清单（高德行政区划），地球打点用 */
    @GetMapping("/cities")
    public Result<List<CityVO>> cities(){
        return Result.ok(placeService.listCities());
    }

    /** AI 二创：按城市 + 真实景点名生成一段种草文案 */
    @PostMapping("/inspiration")
    public Result<String> inspiration(@RequestBody InspirationDTO dto){
        return Result.ok(placeService.generateInspiration(dto.getCity(), dto.getSpots()));
    }

}
