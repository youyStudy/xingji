package com.xingji.LLM;

import java.util.List;
import java.util.Map;

public interface LlmClient {
    /**
     * system=该任务的固定规则(角色+输出格式),user=本次需求数据。
     * 分开传,由实现拼成 [system, user] 两条 messages。适合一次性生成/解析。
     */
    String chat(AiTask task, String system, String user);

    /**
     * 多轮对话:在大模型无状态的前提下,由调用方把"历史轮次"传进来一起发。
     * system=固定规则;history=之前若干轮 [{role,content}](按时间从旧到新,不含本轮);
     * user=本轮用户输入。实现拼成 [system] + history + [user] 再发。
     */
    String chat(AiTask task, String system, List<Map<String, String>> history, String user);
}