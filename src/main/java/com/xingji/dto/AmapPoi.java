package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.List;

/**
 * 高德 place/text 的单条 POI。注意：rating/cost/open_time/photos 只有在请求
 * extensions=all 时才会返回；base 模式只有名字/坐标/地址/分类。
 * 高德用 snake_case，这里用 @JsonProperty 把 biz_ext / open_time 映射到驼峰字段。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmapPoi {
    private String id;
    private String name;
    private String type;       // 高德自带的景区分类
    private String location;   // "经度,纬度"（GCJ-02）
    @JsonDeserialize(using = AmapFlexStringDeserializer.class)
    private String address;
    @JsonDeserialize(using = AmapFlexStringDeserializer.class)
    private String cityname;
    @JsonDeserialize(using = AmapFlexStringDeserializer.class)
    private String tel;        // 电话（extensions=all）——高德可能返回数组，用柔性解析

    @JsonProperty("biz_ext")
    @JsonDeserialize(using = AmapFlexBizExtDeserializer.class)   // base 模式下这里是 []
    private BizExt bizExt;     // 商业扩展：评分/人均/开放时间（extensions=all）

    private List<Photo> photos; // 真实照片（extensions=all，可能为空）

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BizExt {
        @JsonDeserialize(using = AmapFlexStringDeserializer.class)
        private String rating;      // 评分，如 "4.8"
        @JsonDeserialize(using = AmapFlexStringDeserializer.class)
        private String cost;        // 人均/门票，如 "60"（可能为空）
        @JsonProperty("open_time")
        @JsonDeserialize(using = AmapFlexStringDeserializer.class)
        private String openTime;    // 开放时间，如 "08:00-17:30"
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Photo {
        @JsonDeserialize(using = AmapFlexStringDeserializer.class)
        private String title;   // 高德有时把照片标题返回成标签数组，用柔性解析
        @JsonDeserialize(using = AmapFlexStringDeserializer.class)
        private String url;
    }
}