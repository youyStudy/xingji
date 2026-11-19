package com.xingji.LLM;

/**
 * AI 任务类型枚举:业务层用它表达"这次要模型干哪件事",而不是"用哪个模型"。
 * 路由层负责把 task 翻译成具体 provider/模型。加新任务 = 加一个枚举值 + 在 yml routing 配一行。
 */
public enum AiTask {
    ITINERARY_GENERATE,  // 行程生成:推理排布每天/节点(用强推理模型)
    CHAT,                // 对话交互:边聊边改行程(用对话体验好的模型)
    INSPIRATION,         // 目的地种草文案:给地球首页"AI 二创"用(创意向)
    SPOT_RECOMMEND       // 景点智能推荐:从高德候选里按用户偏好挑选并给理由
}