package com.xingji.dto;

import lombok.Data;

import java.util.List;

/**
 * 地球首页"AI 二创"入参：城市名 + 用户当前看到的真实景点名列表。
 * 后端把这两个拼成给模型的自然语言输入，产出一段种草文案。
 */
@Data
public class InspirationDTO {
    private String city;         // 城市名（如"重庆"）
    private List<String> spots;  // 候选景点名（高德真实返回的，模型只从中取材）
}
