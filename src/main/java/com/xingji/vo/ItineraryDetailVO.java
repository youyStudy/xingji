package com.xingji.vo;

import com.xingji.pojo.Itinerary;
import lombok.Data;

import java.util.List;

/**
 * 行程详情"整棵树"的输出 VO(后端吐给前端的数据契约)。
 * 给前端编辑页/分享页一次拿到完整行程:主体 + 每天 + 每天下的节点。
 */
@Data
public class ItineraryDetailVO {
    private Itinerary itinerary;          // 行程主体(标题/日期/天数/预算/status 等)
    private List<ItineraryDayVO> days;    // 按 day_index 排好序的每一天的列表
}

/*{
  "itinerary": {
    "id": 1001,
    "title": "武汉3日游",
    "totalDay": 3,
    "totalBudget": 1500,
    "status": 1
  },
  "days": [
    {
      "dayIndex": 1,
      "dayDate": "2026-10-20",
      "nodes": [
        {
          "placeName": "黄鹤楼",
          "nodeType": "景点",
          "startTime": "08:30",
          "endTime": "11:00",
          "cost": 70
        },
        {
          "placeName": "户部巷小吃",
          "nodeType": "餐饮",
          "startTime": "12:00",
          "endTime": "13:30",
          "cost": 60
        }
      ]
    },
    {
      "dayIndex": 2,
      "dayDate": "2026-10-21",
      "nodes": [
        {
          "placeName": "东湖风景区",
          "nodeType": "景点",
          "startTime": "09:00",
          "endTime": "16:00",
          "cost": 0
        }
      ]
    }
  ]
}
*/