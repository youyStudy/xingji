package com.xingji.service;

import com.xingji.vo.ValidationResultVO;

/**
 * 行程校验服务(Phase 4)。生成之后跑一次,把"AI 编出来的骨架"变成"经事实与约束核验过的行程"。
 * 两类检查:
 *  1) 真实性:拿节点 placeName 去高德查,查到→缓存地点并回填 placeId、is_abnormal=0;查不到→is_abnormal=1 并记一条提醒。
 *  2) 约束(纯确定性代码,不靠模型):预算是否超支、同一天活动时间是否重叠。
 * 跑完把 itinerary.status 置 2(已校验)。
 */
public interface ItineraryValidateService {

    ValidationResultVO validate(Integer itineraryId, Integer userId);
}
