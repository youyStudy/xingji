package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 高德「天气查询」接口 /v3/weather/weatherInfo?extensions=all(预报) 的返回体。
 * 只挑我们用得到的字段,其余靠 @JsonIgnoreProperties 忽略。
 *
 * 结构:forecasts[0](某城市) → casts[](逐天预报,从今天起最多约 4 天)。
 * 因此当用户出发日期在很远以后时,casts 里根本不含那几天 —— 上层据此优雅降级、不报错。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmapWeatherResponse {

    private String status;      // "1" 成功
    private String info;
    private String infocode;
    private List<Forecast> forecasts;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Forecast {
        private String city;        // 城市名,如"杭州市"
        private String adcode;      // 城市 adcode
        private String province;
        private String reporttime;  // 发布时刻
        private List<Cast> casts;   // 逐天预报数组
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cast {
        private String date;          // 日期 yyyy-MM-dd,用来筛出行程覆盖的那几天
        private String week;          // 1~7 代表周一~周日
        private String dayweather;    // 白天天气现象,如"多云""小雨"
        private String nightweather;  // 夜间天气现象
        private String daytemp;       // 白天温度(℃,字符串)
        private String nighttemp;     // 夜间温度(℃,字符串)
        private String daywind;       // 白天风向
        private String daypower;      // 白天风力
    }
}
