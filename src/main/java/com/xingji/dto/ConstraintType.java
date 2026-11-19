package com.xingji.dto;

/** 用户约束的业务类型：偏好尽量满足、排除必须过滤、安全优先保守处理。 */
public enum ConstraintType {
    PREFERENCE,
    EXCLUSION,
    SAFETY
}