package com.xingji.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * "按天重排"的入参:只重排某一天,行程其余天不动。
 * 画像字段(节奏/偏好/交通/回避/必去)每次随请求重传——它们不落库,前端 explore store 里一直有;
 * feedback 是用户这一次的自然语言反馈(如"太赶了,少排点,晚上加个夜市"),优先级最高,可空。
 */
@Data
public class DayRegenerateDTO {
    @NotBlank(message = "目的地城市不能为空")
    private String city;              // 目的地城市:拉候选池 + 上下文
    private String feedback;          // 用户本次反馈,可空=按画像原样重排
    private String pace;              // 节奏:特种兵/适中/悠闲
    private String transport;         // 市内交通偏好
    private List<String> preferences; // 偏好标签
    private List<String> mustVisit;   // 必去地点
    private List<String> avoid;       // 回避项
}
