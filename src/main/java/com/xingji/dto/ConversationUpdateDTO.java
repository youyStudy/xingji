package com.xingji.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型对对话状态的建议更新。
 * 只是建议：服务端会校验 stage 合法性、截断长度、去重后再落库。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationUpdateDTO {

    /** COLLECTING / DISCUSSING / READY */
    private String stage;
    private String currentTopic;
    private List<String> undecidedItems = new ArrayList<>();
    private List<String> acceptedSuggestions = new ArrayList<>();
}