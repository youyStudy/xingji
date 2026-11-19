package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话状态。
 * 第一版只做持久化，不驱动回复策略——目的是把"连续追问、已建议、待决定"
 * 这些跨轮事实记下来，供 P1 的策略层使用。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationStateDTO {

    /** COLLECTING / DISCUSSING / READY */
    private String stage = "COLLECTING";

    /** 当前话题，如"泉州旅行安排" */
    private String currentTopic;

    /** 最近一次意图（模型给的 intent 或服务端分流结果） */
    private String lastIntent;

    /** 最近一次回复策略（ASK/SUGGEST/ANSWER…），用于避免连续追问 */
    private String lastStrategy;

    /** 连续追问次数 */
    private Integer consecutiveQuestions = 0;

    /** 用户已接受的建议 */
    private List<String> acceptedSuggestions = new ArrayList<>();

    /** 用户还没决定的事 */
    private List<String> undecidedItems = new ArrayList<>();
}