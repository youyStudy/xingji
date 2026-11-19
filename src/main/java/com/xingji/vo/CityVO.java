package com.xingji.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 地球上要标的一座城：名字 + 高德行政区划 adcode + 中心经纬度。
 * center 保留原始 "lng,lat" 字符串方便调试，lng/lat 拆好给前端画图。
 * 是否"旅游热门"属于 UI 强调，交给前端判断，这里不掺和。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CityVO {
    private String name;      // 城市名（高德原样，如 "重庆市"）
    private String adcode;    // 高德行政区划编码，作为唯一 key
    private String center;    // 高德中心点 "经度,纬度"
    private BigDecimal lng;
    private BigDecimal lat;
}
