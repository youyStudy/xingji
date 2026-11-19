package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Place {

    private Integer id;
    private String name;         // 地点名称
    private String amapPoiId;    // 高德POI唯一ID
    // 注意:存的是 WGS-84,不是 GCJ-02。
    // AmapClient.doSearch 拿到高德坐标后做了 gcj02ToWgs84 纠偏再入库(为了地球贴图落位准)。
    // 要拼高德导航链接/回到高德地图时,必须先用 GeoUtil.wgs84ToGcj02 转回去,否则偏几百米。
    private BigDecimal longitude;// 经度(WGS-84)
    private BigDecimal latitude; // 纬度(WGS-84)
    private String city;
    private String category;  // 用来保存景点的分类
    private String address;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
