package com.xingji.vo;

import com.xingji.dto.ChatProfileDTO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一轮对话的完整结果。
 *
 * 旧接口只返回一条 ChatMessage,画像散落在前端,刷新/切会话就丢。
 * 现在服务端是画像的唯一事实来源:每轮把合并后的 profile 一并返回,
 * 前端直接覆盖本地表单即可;missing 也是服务端算的,模型说了不算。
 */
@Data
public class ChatTurnVO {

    /** 助手消息 id(已落库) */
    private Integer id;

    /** 固定 assistant */
    private String role = "assistant";

    /** 给用户看的自然语言回复(不含 JSON) */
    private String content;

    /** 消息落库时间(近似值,前端只用于展示) */
    private LocalDateTime createTime;

    /** 服务端合并后的最新画像(完整快照,不是补丁) */
    private ChatProfileDTO profile;

    /** 服务端计算的缺失项,如 city / arrival.date / arrival.time */
    private List<String> missing;

    /** chat=继续聊;ready=画像已够生成,前端可亮画像卡;generate=用户已授权生成 */
    private String action;

    /** 模型判断的意图（EXPLORE/PLAN/DISCUSS/MODIFY/CONFIRM/SMALL_TALK/OUT_OF_SCOPE/UNKNOWN） */
    private String intent;

    /** 本轮采用的回复策略（ANSWER/SUGGEST/ASK/DISCUSS/CONFIRM/COMPLETE） */
    private String strategy;

    /** 模型建议的下一步（仅参考，不决定后端动作） */
    private String nextStep;

    /** 本轮被服务端忽略/修正的字段说明(日期越界等),前端可提示 */
    private List<String> warnings;

    /** 结构化约束：前端画像卡片按 已确认/待确定/已排除 展示 */
    private List<com.xingji.dto.TravelConstraintDTO> constraints;

    /** 对话状态（P0 只回传，前端暂不依赖） */
    private com.xingji.dto.ConversationStateDTO conversationState;

    /**
     * 本轮附带的景点卡片(用户在问"推荐景点"时才有,其余情况为空列表)。
     *
     * 为什么单独一个字段、不把信息全塞进 content:
     *   content 是给模型写的自然语言，前端只能当纯文本渲染；
     *   而景点名/照片/评分/门票这些是**结构化事实**，必须能渲染成图文卡片才好看。
     *   这些数据全部来自高德真实 POI，模型只负责"挑哪几个"，不负责"说什么"。
     */
    private List<com.xingji.vo.SpotVO> spots = new java.util.ArrayList<>();
}
