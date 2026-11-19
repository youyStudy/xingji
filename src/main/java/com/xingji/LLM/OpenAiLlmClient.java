package com.xingji.LLM;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.common.BizException;
import com.xingji.config.AIProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * LlmClient 的真实现:内置"任务→模型"路由,走 OpenAI 兼容协议(/chat/completions)。
 * 路由两步:task.name() 经 routing 找到模型别名 → 别名经 models 取到 ModelConfig → 用它发请求。
 * 业务层永远只调 chat(task, prompt),不知道也不关心底下换了哪个模型。
 * 仅当 xingji.ai.mock=false 时装配(与 MockLlmClient 二选一)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "xingji.ai", name = "mock", havingValue = "false")
public class OpenAiLlmClient implements LlmClient {

    private final RestTemplate restTemplate;
    private final AIProperties aiProps;
    private final ObjectMapper objectMapper;

    @Override
    public String chat(AiTask task, String system, String user) {
        // 一次性任务:没有历史,委托多轮实现
        return chat(task, system, List.of(), user);
    }

    @Override
    public String chat(AiTask task, String system, List<Map<String, String>> history, String user) {
        // messages = [system] + history(旧→新) + [本轮 user]
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));   // ← 先定规则
        if (history != null) {
            for (Map<String, String> h : history) {
                messages.add(Map.of("role", h.get("role"), "content", h.get("content")));
            }
        }
        messages.add(Map.of("role", "user", "content", user));       // ← 再传本轮输入
        return doSend(task, messages);
    }

    /** 真正发请求、解析回复的公共逻辑,两种入口共用 */
    private String doSend(AiTask task, List<Map<String, Object>> messages) {
        AIProperties.ModelConfig cfg = resolve(task);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", cfg.getModel());
        body.put("messages", messages);
        body.put("temperature", cfg.getTemperature());
        if (cfg.getMaxTokens() > 0) {
            body.put("max_tokens", cfg.getMaxTokens());   // 推理模型显式给上限，防正文被服务商默认值截断
        }
        // yml 里的厂商私有参数(如智谱 thinking.type=disabled)原样并进请求体
        if (cfg.getExtraBody() != null && !cfg.getExtraBody().isEmpty()) {
            body.putAll(cfg.getExtraBody());
        }

        String url = cfg.getBaseUrl() + "/chat/completions";
        // 创建请求头对象
        HttpHeaders headers = new HttpHeaders();
        // 设置请求体格式为 JSON
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 设置 Bearer令牌鉴权
        headers.setBearerAuth(cfg.getApiKey());

        try {
            // 将请求体对象body序列化为JSON字符串，作为大模型接口的请求报文
            String reqJson = objectMapper.writeValueAsString(body);
            if (log.isDebugEnabled()) {
                log.debug("[LLM请求体] task={} url={} body={}", task, url, reqJson);
            }
            // 使用POST发起HTTP请求：携带json报文和请求头，调用LLM接口，接收返回原始JSON字符串
            String respJson = restTemplate.postForObject(url, new HttpEntity<>(reqJson, headers), String.class);
            // 将返回的JSON字符串解析成JsonNode树形对象，方便后续按节点读取内容（不用映射完整实体类）
            JsonNode root = objectMapper.readTree(respJson);
            if (root.has("error")) {
                throw new BizException("大模型错误: " + root.path("error").path("message").asText("未知"));
            }
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new BizException("大模型返回内容为空");
            }
            return content.asText();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用大模型异常, task={}", task, e);
            throw new BizException("调用大模型异常: " + e.getMessage());
        }
    }

    /** 按任务把模型配置查出来:task→别名→ModelConfig。任一环缺失就明确报错,方便定位 yml 没配好 */
    private AIProperties.ModelConfig resolve(AiTask task) {
        String alias = aiProps.getRouting().get(task.name());
        if (alias == null) {
            throw new BizException("任务未配置模型路由: " + task);
        }
        AIProperties.ModelConfig cfg = aiProps.getModels().get(alias);
        if (cfg == null) {
            throw new BizException("路由指向的模型别名不存在于 models: " + alias);
        }
        return cfg;
    }
}