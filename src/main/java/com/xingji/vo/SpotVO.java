package com.xingji.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 地球首页"景点推荐"的展示 VO（后端吐给前端的富字段契约）。
 * 之所以不复用 Place 实体：Place 是数据库镜像，只有名字/地址/坐标，装不下
 * 高德 extensions=all 才返回的评分/门票/开放时间/图片，也装不下 AI 生成的推荐理由。
 * 这个 VO 专门给"推荐"这条读链路用，字段多一个都对应前端卡片上的一块信息。
 */
@Data
public class SpotVO {

    private String name;         // 景点名
    private String amapPoiId;    // 高德POI id，去重用
    private String category;     // 高德分类（风景名胜/博物馆…）
    private String address;      // 地址
    private BigDecimal lng;      // 经度（已纠偏 WGS-84，地球打点用）
    private BigDecimal lat;      // 纬度
    private String city;         // 所属城市

    private String tel;          // 电话（可空）
    private String rating;       // 评分，如 "4.8"（可空）
    private String cost;         // 人均/门票价，如 "60"（可空）
    private String openTime;     // 开放时间（可空）
    private String photo;        // 首图 url（可空，前端做缩略图）

    private String reason;       // AI 推荐理由（普通浏览为空；智能推荐时填）
}
