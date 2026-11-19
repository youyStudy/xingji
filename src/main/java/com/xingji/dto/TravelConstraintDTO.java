package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 一条用户约束。
 * 与旧 avoid 的区别：保留类型和生效状态，能表达"海鲜过敏"和"喜欢美食"不是一回事，
 * 也能表达"之前说不爬山，这次可以接受轻松山路"这种修改而不是永久禁止。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TravelConstraintDTO {

    /** 约束内容，如"海鲜过敏""不想爬山" */
    private String content;

    /** 业务类型：偏好/排除/安全 */
    private ConstraintType type;

    /** 作用范围：food / activity / place / lodging / general */
    private String scope;

    /** 是否仍然有效 */
    private Boolean active = true;

    /** 用户原话，便于回溯和前端展示 */
    private String sourceText;

    public boolean isEffective() {
        return active == null || active;
    }
}