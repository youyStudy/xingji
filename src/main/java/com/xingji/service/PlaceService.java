package com.xingji.service;

import com.xingji.dto.SpotRecommendDTO;
import com.xingji.vo.CityVO;
import com.xingji.vo.SpotVO;

import java.util.List;

public interface PlaceService {

    /** 某城市的真实景点浏览（高德 POI + 评分/图片/门票），地球右侧卡片用 */
    public List<SpotVO> browseSpots(String city);

    /** 智能推荐：在高德候选里按用户偏好让 LLM 挑选并写推荐理由 */
    public List<SpotVO> smartRecommend(SpotRecommendDTO dto);

    /** 全国地级市清单（高德行政区划），给地球打真实城市点用 */
    public List<CityVO> listCities();

    /** AI 二创：根据城市 + 真实景点名，产出一段种草文案 */
    public String generateInspiration(String city, List<String> spots);

}
