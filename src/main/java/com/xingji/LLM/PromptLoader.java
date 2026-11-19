package com.xingji.LLM;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 提示词加载器:启动时把各 system 提示词从 resources/prompts 读进内存,按 AiTask 缓存。
 * 业务代码要用提示词就注入本类、按 task 取,不再各自读盘——集中、去重、防手抖。
 * 用 AiTask 枚举当 key(非字符串):编译期防打错,且与模型路由共用同一套任务定义。
 * ⚠️ 只声明"文件已存在"的提示词;为不存在的文件加 @Value 会导致应用启动失败。
 */
@Component
public class PromptLoader {

    // 行程生成 system 提示词:已升级到"候选池 + poiId"契约的 v2 版
    // (旧 itinerary-generate-system.txt 仍留在磁盘,需回退时把这行路径改回去即可)
    @Value("classpath:prompts/itinerary-generate-system-v2.txt")
    private Resource itineraryGeneratePrompt;

    // 对话 system 提示词:文件已建好(resources/prompts/chat-system.txt),可加载
    @Value("classpath:prompts/chat-system.txt")
    private Resource chatPrompt;

    // 目的地种草文案 system 提示词:文件 resources/prompts/inspiration-system.txt
    @Value("classpath:prompts/inspiration-system.txt")
    private Resource inspirationPrompt;

    // 景点智能推荐 system 提示词:文件 resources/prompts/recommend-system.txt
    @Value("classpath:prompts/recommend-system.txt")
    private Resource recommendPrompt;

    // 按天重排 system 提示词:文件 resources/prompts/day-regenerate-system.txt。
    // 与 ITINERARY_GENERATE 共用同一模型路由(强推理),但不走 AiTask 缓存——
    // 枚举 key 一一对应"任务",按天重排属于 ITINERARY_GENERATE 的子模式,单独存。
    @Value("classpath:prompts/day-regenerate-system.txt")
    private Resource dayRegeneratePrompt;

    // 住宿区域推荐提示词:只给区域名不给酒店,见 LodgingAreaRecommender
    @Value("classpath:prompts/lodging-area-system.txt")
    private Resource lodgingAreaPrompt;

    private final Map<AiTask, String> cache = new EnumMap<>(AiTask.class);
    private String dayRegenerateCache;
    private String lodgingAreaCache;

    @PostConstruct
    public void init() {
        cache.put(AiTask.ITINERARY_GENERATE, read(itineraryGeneratePrompt));
        cache.put(AiTask.CHAT, read(chatPrompt));
        cache.put(AiTask.INSPIRATION, read(inspirationPrompt));
        cache.put(AiTask.SPOT_RECOMMEND, read(recommendPrompt));
        dayRegenerateCache = read(dayRegeneratePrompt);
        lodgingAreaCache = read(lodgingAreaPrompt);
    }

    /** 按任务取提示词;没加载到就明确报错,方便定位 */
    public String getPrompt(AiTask task) {
        String p = cache.get(task);
        if (p == null) throw new IllegalStateException("未加载该任务的提示词: " + task);
        return p;
    }

    /** 住宿区域推荐提示词(只给区域名,不给具体酒店) */
    public String getLodgingAreaPrompt() {
        if (lodgingAreaCache == null) throw new IllegalStateException("未加载住宿区域推荐提示词");
        return lodgingAreaCache;
    }

    /** 按天重排提示词(ITINERARY_GENERATE 的子模式,不走 AiTask 枚举) */
    public String getDayRegeneratePrompt() {
        if (dayRegenerateCache == null) throw new IllegalStateException("未加载按天重排提示词");
        return dayRegenerateCache;
    }

    /** 读 classpath 资源成字符串;失败 fail-fast */
    private String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("提示词文件读取失败: " + resource, e);
        }
    }
}