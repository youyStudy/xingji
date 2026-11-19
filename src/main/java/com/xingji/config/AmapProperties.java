package com.xingji.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "xingji.map.amap")
public class AmapProperties {
    /** 高德Web服务key */
    private String key;
    /** 接口基础地址 */
    private String baseUrl;
    /** POI文本搜索接口路径 /v3/place/text */
    private String poiSearchPath;
    /** 天气查询接口路径 /v3/weather/weatherInfo(编排器的第2个数据源用) */
    private String weatherPath;
    /** 路径规划接口前缀 /v3/direction/（实际请求拼 /v3/direction/driving 或 /walking） */
    private String directionPath;
}
