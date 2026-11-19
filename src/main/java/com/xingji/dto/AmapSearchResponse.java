package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmapSearchResponse {
    private String status;     // 字符串 "1"/"0" 1代表请求成功
    /**状态文字描述
     - 成功时：OK
     - 失败时：会写失败原因，比如:"INVALID_USER_KEY"（key 无效）"DAILY_QUERY_OVER_LIMIT"（每日额度耗尽）
     用来给人看，排错用。*/
    private String info;
    private String infocode; // 详细错误码
    private String count; // 本次搜索，满足条件的 POI 总数量
    private List<AmapPoi> pois; // 里面包含：景点名字、经纬度、地址、城市等
}