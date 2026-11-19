package com.xingji.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 大模型"多模型 + 任务路由"配置,对应 yml 里 xingji.ai.* 。
 * 设计:models 是"候选模型清单"(给每个模型起个别名,如 deepseek / reasoner),
 *      routing 是"任务→模型别名"的映射(哪个 AI 任务交给哪个模型干)。
 * 业务层只提 AiTask,由 LlmClient 拿 routing 查到别名、再用别名去 models 取配置——模型和业务彻底解耦。
 * (将来想升级成"管理员在线配模型",就把这两个 Map 的数据源从 yml 换成读 ai_service_config 表,代码结构不动。)
 */
@Data
@Component
@ConfigurationProperties(prefix = "xingji.ai")
public class AIProperties {

    private boolean mock;                       // true=用 MockLlmClient 离线测 false=用真模型。xingji.ai.mock
    private Map<String, ModelConfig> models = new HashMap<>();  // 别名→模型配置。键如 deepseek / reasoner
    private Map<String, String> routing = new HashMap<>();      // 任务名(AiTask.name())→模型别名。如 ITINERARY_GENERATE: reasoner

    /**
     * 单个模型的接入配置(一个模型一个条目)。将来一行对应 ai_service_config 表一行。
     */
    @Data
    public static class ModelConfig {
        private String baseUrl;       // 接口地址,如 https://api.deepseek.com/v1 (含 /v1)
        private String apiKey;        // 该模型密钥,别提交公开仓库
        private String model;         // 模型名,如 deepseek-chat / deepseek-reasoner
        private double temperature;   // 采样温度,结构化输出建议偏低(0.2)
        private int maxTokens;        // 输出上限(token)。0=不下发用服务商默认值;推理模型(R1)建议显式给大,防止思考吃满预算导致正文被截断

        /**
         * 厂商私有参数的透传口(yml: extra-body),原样并进请求体。
         * 例如智谱 4.5 系列默认开思考模式,对话场景可传 thinking.type=disabled 提速;
         * 换成别家模型时这段配置自然失效,不影响通用字段。
         */
        private Map<String, Object> extraBody;
    }
}