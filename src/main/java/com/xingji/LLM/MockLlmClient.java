package com.xingji.LLM;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LlmClient 的线下模拟实现：当 xingji.ai.mock=true 时装配（与 OpenAiLlmClient 二选一）。
 * 用途是"没有真模型 key 也能把整条链路跑通"——按 AiTask 返回固定但合法的回复，
 * 让对话、种草文案、行程生成在前端都能演示，而不必花钱调 API。
 * 注意：ITINERARY_GENERATE 返回的必须是能被 GeneratedItineraryDTO 解析的合法 JSON。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "xingji.ai", name = "mock", havingValue = "true")
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(AiTask task, String system, String user) {
        return chat(task, system, List.of(), user);
    }

    @Override
    public String chat(AiTask task, String system, List<Map<String, String>> history, String user) {
        log.warn("[MockLlmClient] mock 模式回复，task={}（填真 key + mock=false 即走真实模型）", task);
        String input = user == null ? "" : user.trim();
        switch (task) {
            case CHAT:
                return "【模拟回复】我在离线 mock 模式，还没接真模型。你说："
                        + clip(input) + "。等 xingji.ai.api-key 填上、mock 关掉，这里就是真正的 AI 回复了。";
            case INSPIRATION:
                return "【AI 二创·种草（模拟）】" + clip(input)
                        + "。把节奏放慢：清晨逛古迹、午后找小馆、入夜追灯火，再留半天给街巷烟火。";
            case ITINERARY_GENERATE:
                return MOCK_ITINERARY_JSON;
            case SPOT_RECOMMEND:
                // mock 不知道候选真名，返回空数组；服务层会自动用高德数据兜底生成推荐理由
                return "[]";
            default:
                return "【模拟回复】未为该任务准备样例，请接真实模型。";
        }
    }

    private String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    /** 一份合法、可被 GeneratedItineraryDTO 解析的两日草稿 */
    private static final String MOCK_ITINERARY_JSON = """
            {
              "title": "示例城市三日游（模拟）",
              "days": [
                {
                  "dayIndex": 1,
                  "theme": "初到·地标与夜景",
                  "nodes": [
                    {"nodeType": "景点", "placeName": "洪崖洞", "startTime": "19:00", "endTime": "20:30", "cost": 0, "suggestion": "亮灯后最美，11楼平出到马路"},
                    {"nodeType": "餐饮", "placeName": "八一好吃街", "startTime": "20:30", "endTime": "21:30", "cost": 50, "suggestion": "顺便尝小吃"}
                  ]
                },
                {
                  "dayIndex": 2,
                  "theme": "古镇与索道",
                  "nodes": [
                    {"nodeType": "景点", "placeName": "磁器口古镇", "startTime": "09:30", "endTime": "12:00", "cost": 30, "suggestion": "买陈麻花、看川剧"},
                    {"nodeType": "交通", "placeName": "长江索道", "startTime": "14:00", "endTime": "14:40", "cost": 30, "suggestion": "老式空中公交横跨长江"}
                  ]
                }
              ]
            }
            """;
}
