package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 旅行画像:对话过程中逐步确认下来的结构化事实。
 *
 * 它不再只是"前端表单快照":每轮对话结束后由服务端合并、校验并写进
 * chat_session.context,刷新页面或切回历史会话都能恢复。模型只负责从用户原话里
 * 提 profile_patch(补丁),真正的合并与取舍在服务端做——这样"用户最新原话"
 * 永远能覆盖旧值,而不是被前端认为权威的旧画像挡住。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatProfileDTO {
    private String origin;            // 出发地城市,如"重庆"
    private String city;              // 目的地城市
    private String startDate;         // 出发日期 'YYYY-MM-DD'
    private String endDate;           // 结束日期 'YYYY-MM-DD'(与天数二选一,都给时以区间为准)
    private Integer days;             // 天数
    private BigDecimal budget;        // 预算(元)
    private List<String> preferences; // 偏好标签
    private Integer groupSize;        // 同行人数
    private String companionType;     // 独行/情侣/朋友/家庭
    private String pace;              // 特种兵/适中/悠闲
    private String transport;         // 市内交通偏好
    private String lodging;           // 住宿偏好(区域名,如"春熙路";或用户的自由描述)
    /**
     * 住宿方式:住一处 / 换着住。
     *
     * 为什么单独一个字段、不塞进 lodging 文本里:
     * 它决定的是"行程结构"而非"内容偏好"——住一处时全程同一个区域,
     * 换着住时按行程分段(比如成都住2晚、都江堰住1晚)。
     * 模型需要明确知道这一点才能排出合理的天数分配,藏在自由文本里它读不出来。
     * 空字符串=用户还没说,系统按"住一处"处理。
     */
    private String lodgingMode;
    private List<String> mustVisit;   // 必去地点
    private List<String> avoid;       // 回避项
    /**
     * 对话要点：用户明确说过、但装不进上面结构化字段的约束与偏好
     * （如"老人不能爬坡""第三天想轻松点""不想去人多的地方"）。
     * 模型每轮输出、服务端合并去重，生成行程时作为"本次对话已确认的要点"拼进提示词——
     * 否则这些只存在于对话里的信息，生成时就全丢了。
     */
    private List<String> notes;
    /**
     * 结构化约束：普通偏好 / 硬性排除 / 安全约束。
     * P0 与 avoid/notes 并存（旧数据仍可读），P1 再收敛。
     */
    private List<TravelConstraintDTO> constraints;
    private AnchorStateDTO arrival;   // 到达锚点(日期/时刻可只知其一)
    private AnchorStateDTO departure; // 返程锚点
}

