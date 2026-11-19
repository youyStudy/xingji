package com.xingji.dto;

import lombok.Data;

/**
 * 智能推荐入参：城市 + 可选的用户偏好（自然语言，如"带娃、爱历史、预算中等"）。
 * 偏好为空时后端退化为"按评分排序的高德真实景点"，不烧 LLM。
 */
@Data
public class SpotRecommendDTO {
    private String city;        // 城市（高德原名，带"市"更准）
    private String preference;  // 用户偏好，可空
}
