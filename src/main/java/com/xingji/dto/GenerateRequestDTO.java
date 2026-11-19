package com.xingji.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 前端发起"AI 生成行程"的入参(用户诉求)。
 * 故意不含 userId:由 controller 从登录态取,防越权。
 * 这些字段会被拼成给模型的 user 提示词。
 *
 * arrival/departure 是"最小锚点版 TaskBrief"的边界条件:它们不是内容项,
 * 而是决定首日起点、末日收尾、住宿晚数、餐次完整性的硬约束。
 * 都做成可选——老 payload 不传时,生成链路退化成"由 startDate/days 自由推断",完全向后兼容。
 */
@Data
public class GenerateRequestDTO {
    private String city;              // 目的地城市,如"重庆"
    private LocalDate startDate;      // 出发日期,用来推算每天具体日期;若 arrival 存在,以 arrival.date 为准
    @Min(value = 1, message = "行程天数至少为 1 天")
    @Max(value = 30, message = "行程天数不能超过 30 天")
    private Integer days;             // 天数,如 3
    @DecimalMin(value = "0", message = "预算不能为负数")
    private BigDecimal budget;        // 预算(元),如 2000
    private List<String> preferences; // 偏好标签,如["人文","夜景","美食"],可空

    // ===== 旅行画像(全部可选;画像越全,生成越贴合。buildUserPrompt 拼进 user 提示词,由 system 规则消费) =====
    private Integer groupSize;        // 同行人数,如 2;null=未说明
    private String companionType;     // 同行关系:独行/情侣/朋友/家庭(可注明有无老人小孩)
    private String pace;              // 节奏:特种兵/适中/悠闲;决定每天主景点数量上限
    private String transport;         // 市内交通偏好:公共交通优先/打车/自驾
    private String lodging;           // 住宿偏好,自由文本,如"地铁口附近,400元/晚以内"
    /** 住宿方式:住一处 / 换着住;null 或空 = 未说明,按"住一处"处理 */
    private String lodgingMode;
    private List<String> mustVisit;   // 必须去的地点清单
    private List<String> avoid;       // 回避清单:不想去的地方/忌口/不能早起等硬约束
    /** 结构化约束（可选）：偏好/排除/安全，生成后会被 ConstraintValidator 二次校验 */
    private List<TravelConstraintDTO> constraints;

    /** 关联会话 id:前端在生成前会先 ensureSession,把当前 sessionId 带过来;
     *  为 null 表示"独立生成"(老 payload 兼容),此时行程无对话历史可回放。 */
    private Integer sessionId;

    /** 到达锚点(第一天的时间下界);null 表示用户未提供,不启用首日边界 */
    @Valid
    private TransitAnchor arrival;

    /** 返程锚点(最后一天的时间上界);null 表示用户未提供,不启用末日边界 */
    @Valid
    private TransitAnchor departure;

    /**
     * 交通锚点(到达 / 返程共用结构)。
     * 之所以两者共用一个类:字段几乎一致、行为对称;拆两个类只会让 service 里到处写两份相同逻辑。
     */
    @Data
    public static class TransitAnchor {
        private LocalDateTime datetime;   // 到/离的具体时刻,如 2026-11-01T14:00
        private String location;          // 站点/机场名,如"成都东站""双流机场T2"
        private String type;              // 交通方式:train | flight | bus | car | other
        private String source;            // 数据来源:manual(手填) | text_order(贴订单抽取) | ocr(二期);默认 manual
        private BigDecimal confidence;    // 置信度 0~1;手填恒 1.0,抽取字段可带模型置信度,低置信度必须前端弹确认
    }
}