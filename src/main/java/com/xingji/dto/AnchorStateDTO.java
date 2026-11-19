package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 到达 / 返程锚点的结构化状态。
 *
 * 为什么把 date 与 time 分开存:用户经常只说得清一半——"下午5点到,坐高铁"里
 * 只有时间和交通方式,没有日期。旧实现把锚点当成一个 datetime 字符串,拿不到日期
 * 就整条丢弃,连用户已经说出的时间和高铁也一起丢了。分开存之后,半成品也能被保留,
 * 只追问真正缺的那一格。
 *
 * datetime 是派生字段:date + time 都有时由服务端拼成 "YYYY-MM-DDTHH:mm",
 * 方便旧的生成接口(GenerateRequestDTO.TransitAnchor)直接消费;只有一半时为空。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnchorStateDTO {

    /** 日期 'YYYY-MM-DD';空=还没说清 */
    private String date;

    /** 时刻 'HH:mm'(24 小时制);空=还没说清 */
    private String time;

    /** 派生值 'YYYY-MM-DDTHH:mm';date/time 不齐时为空 */
    private String datetime;

    /** 模糊时段：用户只说"下午到"时记录它，不换算成具体时刻 */
    private com.xingji.dto.TimePeriod timePeriod;

    /** 站点 / 机场,如"成都东站" */
    private String location;

    /** 交通方式:train | flight | bus | car | other */
    private String type;

    /** 来源:chat(对话抽取) / form(手填) / default(系统默认) */
    private String source;

    /** 置信度 0~1;对话抽取时模型给的把握程度,手填可视为 1 */
    private Double confidence;
}
