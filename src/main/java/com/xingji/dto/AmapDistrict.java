package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 高德「行政区划查询」接口 /v3/config/district 的返回体（只挑我们用得到的字段，
 * Jackson 会自动忽略 JSON 里多出来的字段，所以这里不用全写）。
 *
 * 结构是一棵树：中国(country) → 省(province) → 地级市(city) → 区县(district)。
 * District 里 districts 又是指向自己的列表，递归结构，所以用静态内部类自套。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmapDistrict {

    private String status;      // "1" 成功
    private String info;
    private String infocode;
    private List<District> districts;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class District {
        private String name;
        private String adcode;
        private String center;      // "经度,纬度"
        private String level;       // country / province / city / district / street
        // 注意：高德返回里 citycode 在国/省级是空数组 []、市级才是字符串，
        // 类型不稳定且本系统用不到，故不声明；靠 @JsonIgnoreProperties 直接忽略。
        private String parent_adcode;
        private List<District> districts;  // 下级，可能为 null
    }
}
