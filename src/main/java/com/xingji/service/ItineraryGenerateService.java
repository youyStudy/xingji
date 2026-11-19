package com.xingji.service;

import com.xingji.dto.DayRegenerateDTO;
import com.xingji.dto.GenerateRequestDTO;
import com.xingji.vo.ItineraryDetailVO;

/**
 * "AI 生成行程"业务服务:整条链路的总装。
 * 拼提示词 → 调 LlmClient(按任务路由到对应模型) → 解析返回的 JSON → 落库成三层树 → 复用 getDetail 读出树。
 * 只负责"生成并落库",不做地点高德匹配(那是 Phase 4 校验的职责)。
 */
public interface ItineraryGenerateService {

    ItineraryDetailVO generate(Integer userId, GenerateRequestDTO req);

    /**
     * 按天重排:只重新规划某一天(主题+节点),其余天原样保留。
     * 用于用户反馈"第二天太赶"这类局部调整,代替整份重写。
     */
    ItineraryDetailVO regenerateDay(Integer userId, Integer dayId, DayRegenerateDTO dto);
}