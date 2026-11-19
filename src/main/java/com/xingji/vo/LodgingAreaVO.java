package com.xingji.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 住宿区域候选：给用户"住哪一带"的建议。
 *
 * 设计取舍：我们【不推荐具体酒店】——住宿高度个性化（预算、品牌、房型偏好各异），
 * 推荐到店容易踩雷，也很难解释理由。只推荐"区域"，让用户自己上高德订。
 * 区域本身是真实地标（用高德查过），所以能定位、能判断离景点远不远。
 */
@Data
public class LodgingAreaVO {

    /** 区域名，如"春熙路""宽窄巷子" —— 同时也是给用户看的文案主语 */
    private String name;

    /** 一句话特点，如"最热闹，吃的多，地铁方便" */
    private String highlight;

    /** 高德 POI ID（查到的话），用于落库绑定坐标 */
    private String amapPoiId;

    /** WGS-84 经度（与 place 表存法一致） */
    private BigDecimal lng;

    /** WGS-84 纬度 */
    private BigDecimal lat;
}
