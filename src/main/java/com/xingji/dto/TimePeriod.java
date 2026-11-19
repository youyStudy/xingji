package com.xingji.dto;

/** 模糊时段：用户只说"下午到"时记录它，不换算成 17:00。 */
public enum TimePeriod {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
    UNKNOWN
}