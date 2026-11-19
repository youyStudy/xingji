package com.xingji.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.common.BizException;
import com.xingji.dto.DayRegenerateDTO;
import com.xingji.dto.GenerateRequestDTO;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.GeneratedItineraryDTO;
import com.xingji.dto.PlannerContext;
import com.xingji.LLM.AiTask;
import com.xingji.LLM.LlmClient;
import com.xingji.LLM.PromptLoader;
import com.xingji.mapper.ChatMessageMapper;
import com.xingji.mapper.ChatSessionMapper;
import com.xingji.mapper.ItineraryDayMapper;
import com.xingji.mapper.ItineraryMapper;
import com.xingji.mapper.ItineraryNodeMapper;
import com.xingji.planner.ContextBuilder;
import com.xingji.pojo.ChatMessage;
import com.xingji.pojo.ChatSession;
import com.xingji.pojo.Itinerary;
import com.xingji.pojo.ItineraryDay;
import com.xingji.pojo.ItineraryNode;
import com.xingji.pojo.Place;
import com.xingji.service.ItineraryGenerateService;
import com.xingji.service.ItineraryService;
import com.xingji.vo.ItineraryDetailVO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "AI 生成行程"业务实现:整条链路的总装。
 * 编排顺序:拼 user 需求 → 从 PromptLoader 取 system 规则 → 调 LlmClient(按任务路由模型)
 *          → 抠出干净 JSON → 解析成 DTO → 三层落库 → 复用 getDetail 读出整棵树返回。
 * 只管"生成并落库",不做高德地点匹配(那是 Phase4 校验的职责,此处 placeId 留空、只存 placeName)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryGenerateServiceImpl implements ItineraryGenerateService {

    private final ContextBuilder contextBuilder;       // 确定性编排器:调模型前先把真实候选池等上下文备好
    private final com.xingji.planner.ItineraryConstraintEnforcer constraintEnforcer;  // 生成后的硬约束兜底(模型不听话时由代码删节点)
    private final LlmClient llmClient;               // 只依赖接口,底层是路由后的真模型/mock
    private final PromptLoader promptLoader;          // system 提示词统一由它提供
    private final ObjectMapper objectMapper;
    private final ChatSessionMapper chatSessionMapper;   // 生成时读会话：把对话里聊定的要点带进提示词
    private final ChatMessageMapper chatMessageMapper;
    private final ItineraryMapper itineraryMapper;
    private final ItineraryDayMapper dayMapper;
    private final ItineraryNodeMapper nodeMapper;
    private final com.xingji.chat.ChatContextService chatContextService;      // 读 v2 上下文/兼容旧画像
    private final com.xingji.chat.ConstraintValidator constraintValidator;    // 生成后的用户约束二次校验
    /**
     * 餐饮离当天最近景点的最大容忍距离(米)。
     *
     * 实测模型会把"真实存在但属于另一个区"的餐厅排进当天行程:
     * 把铁像寺的店排到武侯祠之后,两地相距 9.9 公里,中午来回两小时。
     * 名字是真的、高德校验也过,只能靠坐标距离兜底。
     *
     * 3 公里 ≈ 市区内打个车 10 分钟内可达,再远就属于绕路。
     */
    private static final double RESTAURANT_MAX_DETOUR_M = 3000;

    /** 住宿区域离当天最近景点的最大距离(米)。10 公里约等于市区内半小时车程。 */
    private static final double LODGING_MAX_DISTANCE_M = 10000;

    private final ItineraryService itineraryService;  // 复用"读树"能力做最终返回
    private final com.xingji.mapper.PlaceMapper placeMapper;   // 餐厅候选 → Place 落库(节点只存 placeId 外键)
    private final com.xingji.map.AmapClient amapClient;        // 住宿区域名 → 真实坐标

    @Override
    @Transactional   // 一次生成写行程+天+节点多张表,要么全成要么全回滚,避免半棵残树
    public ItineraryDetailVO generate(Integer userId, GenerateRequestDTO req) {
        // 0) 到达/返程硬锚点:先校验+对齐,再做后续一切(arrival 是硬约束、优先于 startDate)
        applyAnchorContract(req);

        // 0.1) 对齐后的出发日期必须还没过:否则模型会按过去日期排出一张无效行程
        if (req.getStartDate() != null && req.getStartDate().isBefore(java.time.LocalDate.now())) {
            throw new BizException(400, "出发日期已过(" + req.getStartDate() + "),请重新选择日期");
        }

        // 1) 编排器先取数:拉真实景点候选池(纯代码、不调模型),得到 PlannerContext
        PlannerContext ctx = contextBuilder.gather(req);

        // 2) 拼 user 需求文本(含候选景点池);system 规则从 PromptLoader 取(按任务)
        String system = promptLoader.getPrompt(AiTask.ITINERARY_GENERATE);
        // 对话是需求的一部分：把会话里的要点与用户原话带进提示词，避免生成时只看表单
        String dialogBrief = buildDialogBrief(userId, req.getSessionId());
        String user = buildUserPrompt(req, ctx, dialogBrief);

        // 3) 调模型:只报任务,用哪个模型由 LlmClient 内部路由决定
        String raw = llmClient.chat(AiTask.ITINERARY_GENERATE, system, user);

        // 4)+5) 抠 JSON 并解析。模型输出偶尔有语法错误(实测最高频是时间值漏引号),
        //    先尝试用 JsonRepair 原地修;修不好才重跑一次模型,而不是直接让用户白等后报错。
        GeneratedItineraryDTO gen = parseOrNull(raw);
        if (gen == null) {
            log.warn("行程 JSON 首次解析失败(已尝试修复),重跑一次模型");
            String retryRaw = llmClient.chat(AiTask.ITINERARY_GENERATE, system, user);
            gen = parseOrNull(retryRaw);
            if (gen == null) {
                log.error("行程 JSON 重试后仍解析失败, raw={}", retryRaw);
                throw new BizException("AI 这次生成没成功，再点一次「生成行程」通常就好了");
            }
        }

        // 4.5) 硬约束兜底:住宿晚数/末日晚于返程/一天两顿午餐/景点重复 —— 只靠 prompt 管不住小模型
        constraintEnforcer.enforce(gen, req);
        // 用户约束的二次校验：推荐阶段过滤过，这里仍要防模型在生成时自行加违规项
        constraintValidator.enforce(gen, req.getConstraints());

        // 5) 落库:先行程壳子(status=1 已生成)
        Itinerary itin = new Itinerary();
        itin.setUserId(userId);
        itin.setTitle(gen.getTitle() != null ? gen.getTitle() : req.getCity() + "行程");
        itin.setCity(req.getCity());       // 校验阶段按城市限定高德搜索范围
        itin.setStartDate(req.getStartDate());
        itin.setDays(req.getDays());
        itin.setBudget(req.getBudget());
        itin.setStatus(1);
        // 关联会话:让"我的行程"选中该行程时能拉出当初怎么聊出来的整段对话;无 sessionId 保持 null,不阻断生成
        itin.setSessionId(req.getSessionId());
        // 方案说明(预算拆分/预约提醒/备选):序列化成 JSON 文本随行程存,失败不阻断
        itin.setAdvice(serializeAdvice(gen.getAdvice()));
        itineraryMapper.insert(itin);

        // 6) 再逐天、逐节点落库
        List<GeneratedItineraryDTO.GeneratedDay> days = gen.getDays();
        if (days == null || days.isEmpty()) {
            throw new BizException("AI 未生成任何天");
        }

        // 6.1) 建景点候选池索引:poiId -> 真实 SpotVO。落库时据此回填真名/门票,并拦截模型编造的景点。
        Map<String, SpotVO> attractionById = new HashMap<>();
        if (ctx.getAttractions() != null) {
            for (SpotVO s : ctx.getAttractions()) {
                if (s.getAmapPoiId() != null && !s.getAmapPoiId().isBlank()) {
                    attractionById.put(s.getAmapPoiId(), s);
                }
            }
        }
        // 池为空 = 高德取数失败(编排器已降级)。此时不强制ID校验,保留模型给的名字,
        // 免得外部偶发抖动把整个"AI 生成行程"功能打死。
        boolean poolActive = !attractionById.isEmpty();

        Map<String, Place> restaurantById = indexRestaurants(ctx, req.getCity());
        // 整趟行程共用一份"已用过的餐厅",避免 3 天里 5 顿饭排同一家店
        java.util.Set<String> usedRestaurants = new java.util.HashSet<>();

        for (GeneratedItineraryDTO.GeneratedDay gd : days) {
            ItineraryDay day = new ItineraryDay();
            day.setItineraryId(itin.getId());
            day.setDayIndex(gd.getDayIndex());
            day.setTheme(gd.getTheme());
            // 由出发日期 + 第几天 推算当天真实日期
            if (req.getStartDate() != null && gd.getDayIndex() != null) {
                day.setDate(req.getStartDate().plusDays(gd.getDayIndex() - 1L));
            }
            dayMapper.insert(day);

            // 逐节点落库(候选池校验/真实名回填/拦截越界景点),generate 与 regenerateDay 共用
            insertGeneratedNodes(day, gd, attractionById, poolActive, restaurantById, usedRestaurants, req.getCity());
        }

        // 7) 复用"读树"把刚生成的完整行程吐出去(生成即所见)
        return itineraryService.getDetail(itin.getId(), userId);
    }

    /**
     * 按天重排:只重新生成某一天(主题+节点),行程其余天原样保留。
     * 与整份 generate 的区别——① 只输出单天 JSON;② 喂给模型"其他天已安排的地点",避免重复安排;
     * ③ 支持一句自然语言反馈("太赶了,少排点"),模型按反馈调密度;画像(节奏/偏好/回避)随请求再传一次。
     */
    @Override
    @Transactional   // 删旧节点 + 写新主题/节点要么全成要么全回滚,失败时保留原安排
    public ItineraryDetailVO regenerateDay(Integer userId, Integer dayId, DayRegenerateDTO dto) {
        // 1) 归属校验:day → itinerary → owner
        ItineraryDay day = dayMapper.findById(dayId);
        if (day == null) throw new BizException(404, "行程日不存在");
        Itinerary itin = itineraryMapper.findById(day.getItineraryId());
        if (itin == null) throw new BizException(404, "行程不存在");
        if (!itin.getUserId().equals(userId)) throw new BizException(403, "无权操作该行程");

        // 2) 取数:候选池 + 天气(借用 ContextBuilder,构造一个只含城市/当天日期的最小请求)
        GenerateRequestDTO minReq = new GenerateRequestDTO();
        minReq.setCity(dto.getCity());
        minReq.setStartDate(day.getDate());
        minReq.setDays(1);
        PlannerContext ctx = contextBuilder.gather(minReq);

        // 3) 模型输入:行程概况 + 要重排的天 + 其他天已占地点 + 画像 + 反馈 + 池
        String system = promptLoader.getDayRegeneratePrompt();
        String user = buildDayRegenUserPrompt(itin, day, dto, ctx);
        String raw = llmClient.chat(AiTask.ITINERARY_GENERATE, system, user);
        GeneratedItineraryDTO.GeneratedDay gd = parseDayOrNull(raw);
        if (gd == null) {
            // 与整趟生成一致:先修,修不好再重跑一次,不直接让用户白等后报错
            log.warn("按天重排 JSON 解析失败(已尝试修复),重跑一次模型");
            gd = parseDayOrNull(llmClient.chat(AiTask.ITINERARY_GENERATE, system, user));
            if (gd == null) {
                throw new BizException("AI 这次重排没成功，再点一次通常就好了");
            }
        }
        if (gd == null || gd.getNodes() == null || gd.getNodes().isEmpty()) {
            throw new BizException("AI 未生成任何安排,请换个说法再试");
        }

        // 单天重排同样过一遍硬约束(按整趟行程的天数判断"是不是最后一天")
        com.xingji.dto.GenerateRequestDTO enforceReq = new com.xingji.dto.GenerateRequestDTO();
        enforceReq.setCity(dto.getCity());
        enforceReq.setStartDate(itin.getStartDate());
        enforceReq.setDays(itin.getDays());
        enforceReq.setPace(dto.getPace());
        com.xingji.dto.GeneratedItineraryDTO wrapper = new com.xingji.dto.GeneratedItineraryDTO();
        java.util.List<com.xingji.dto.GeneratedItineraryDTO.GeneratedDay> one = new java.util.ArrayList<>();
        one.add(gd);
        wrapper.setDays(one);
        constraintEnforcer.enforce(wrapper, enforceReq);

        // 4) 替换:清掉旧节点 → 更新主题 → 按池校验落新节点(date/dayIndex 不动,仍属这一天)
        nodeMapper.deleteByDayId(dayId);
        if (gd.getTheme() != null && !gd.getTheme().isBlank()) {
            day.setTheme(gd.getTheme());
            dayMapper.update(day);
        }
        Map<String, SpotVO> attractionById = indexAttractions(ctx);
        // 单天重排:只对这一天的节点做去重(不跨天,因为其他天的餐厅不在本次上下文里)
        insertGeneratedNodes(day, gd, attractionById, !attractionById.isEmpty(),
                indexRestaurants(ctx, dto.getCity()), new java.util.HashSet<>(), dto.getCity());

        // 5) 读回整棵树返回
        return itineraryService.getDetail(itin.getId(), userId);
    }

    /**
     * 餐厅候选池建 poiId → Place 索引。没入库过的先落库(节点只存 placeId 外键,坐标在 Place 表里)。
     * 落库失败不影响主流程:该餐厅只是拿不到坐标,节点会退化成"未核验"。
     */
    private Map<String, Place> indexRestaurants(PlannerContext ctx, String city) {
        Map<String, Place> byId = new HashMap<>();
        if (ctx == null || ctx.getRestaurants() == null) return byId;
        for (com.xingji.vo.RestaurantCandidateVO rc : ctx.getRestaurants()) {
            if (rc.getAmapPoiId() == null || rc.getAmapPoiId().isBlank()) continue;
            try {
                Place cached = placeMapper.findByAmapPoiId(rc.getAmapPoiId());
                if (cached == null) {
                    Place p = new Place();
                    p.setName(rc.getName());
                    p.setAmapPoiId(rc.getAmapPoiId());
                    // place.category 是 VARCHAR(32),而高德的 type 是
                    // "餐饮服务;中餐厅;四川菜(川菜)"这类长串,直接存会 Data too long。
                    // 这里只保留最后一段(最具体的菜系/分类),既够用又不超长。
                    p.setCategory(truncateCategory(rc.getCategory()));
                    p.setCity(city);
                    // 候选池里的坐标已由 AmapRestaurantClient 转成 WGS-84(与 Place 表存法一致)
                    p.setLongitude(rc.getLng());
                    p.setLatitude(rc.getLat());
                    placeMapper.insert(p);     // 回填自增 id
                    cached = p;
                }
                byId.put(rc.getAmapPoiId(), cached);
            } catch (Exception e) {
                log.warn("餐厅落库失败,该餐厅将不可定位: name={}, err={}", rc.getName(), e.getMessage());
            }
        }
        return byId;
    }

    /**
     * 高德 type 串 → place.category(VARCHAR(32))。
     * 取最后一段(最具体),再按列宽截断,防止 Data too long 让整个生成回滚。
     */
    private String truncateCategory(String category) {
        if (category == null) return null;
        String[] parts = category.split(";");
        String last = parts.length > 0 ? parts[parts.length - 1].trim() : category.trim();
        return last.length() > 32 ? last.substring(0, 32) : last;
    }

    /** 候选池建 poiId 索引;池为空(高德降级)返回空 Map,上层据此关掉 ID 拦截 */
    private Map<String, SpotVO> indexAttractions(PlannerContext ctx) {
        Map<String, SpotVO> byId = new HashMap<>();
        if (ctx.getAttractions() != null) {
            for (SpotVO s : ctx.getAttractions()) {
                if (s.getAmapPoiId() != null && !s.getAmapPoiId().isBlank()) {
                    byId.put(s.getAmapPoiId(), s);
                }
            }
        }
        return byId;
    }

    /** 按天重排的 user 提示词:重排目标 + 其他天已占地点(防重复) + 画像 + 反馈 + 天气 + 候选池 */
    private String buildDayRegenUserPrompt(Itinerary itin, ItineraryDay day, DayRegenerateDTO dto, PlannerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("行程:").append(itin.getTitle() == null ? "未命名行程" : itin.getTitle())
                .append(";要重排:第").append(day.getDayIndex()).append("天");
        if (day.getDate() != null) sb.append(";日期:").append(day.getDate());
        if (day.getTheme() != null && !day.getTheme().isBlank()) sb.append(";原主题(可换):").append(day.getTheme());

        // 其他天已安排的地点:让模型避开,防止重排后又和别的天撞车
        List<ItineraryDay> allDays = dayMapper.findByItineraryId(itin.getId());
        List<String> used = new java.util.ArrayList<>();
        for (ItineraryDay d : allDays) {
            if (d.getId().equals(day.getId())) continue;
            for (ItineraryNode n : nodeMapper.findByDayId(d.getId())) {
                if (n.getPlaceName() != null && !n.getPlaceName().isBlank()) used.add(n.getPlaceName());
            }
        }
        if (!used.isEmpty()) sb.append("\n其他天已安排、不得重复出现的地点:").append(String.join("、", used));

        sb.append("\n画像:节奏:").append(dto.getPace() == null ? "适中" : dto.getPace());
        if (dto.getPreferences() != null && !dto.getPreferences().isEmpty())
            sb.append(";偏好:").append(String.join("、", dto.getPreferences()));
        if (dto.getTransport() != null && !dto.getTransport().isBlank())
            sb.append(";市内交通:").append(dto.getTransport());
        if (dto.getMustVisit() != null && !dto.getMustVisit().isEmpty())
            sb.append(";必须去:").append(String.join("、", dto.getMustVisit()));
        if (dto.getAvoid() != null && !dto.getAvoid().isEmpty())
            sb.append(";回避:").append(String.join("、", dto.getAvoid()));
        if (dto.getFeedback() != null && !dto.getFeedback().isBlank())
            sb.append("\n用户本次反馈(优先级最高,重排必须体现):").append(dto.getFeedback().trim());
        if (ctx != null && ctx.getWeatherBrief() != null && !ctx.getWeatherBrief().isBlank()) {
            sb.append("\n当地天气预报:").append(ctx.getWeatherBrief());
        }
        sb.append(renderCandidatePool(ctx));
        return sb.toString();
    }

    /**
     * 把模型给的一天节点落库。
     *
     * 景点:必须在候选池内(回填真实名/门票),越界景点拦截丢弃。
     * 餐饮:如果模型选了候选池里的餐厅,回填它的真实 Place(带 placeId → 可定位可导航);
     *      没选/池为空时沿用模型给的名字,该节点保持"未核验"状态,前端不会画假坐标。
     * 住宿/交通:沿用模型给的名称与估算。
     * seq 按数组顺序重排。
     */
    private void insertGeneratedNodes(ItineraryDay day, GeneratedItineraryDTO.GeneratedDay gd,
                                      Map<String, SpotVO> attractionById, boolean poolActive,
                                      Map<String, Place> restaurantById,
                                      java.util.Set<String> usedRestaurants, String city) {
        if (gd.getNodes() == null) return;

        // 先收集当天所有"已定位景点"的坐标,供餐厅做距离校验。
        // 必须整天的景点都拿到手才能判断——模型可能把餐厅排在两个远隔的景点之间。
        List<double[]> dayAttractionPoints = collectDayAttractionPoints(gd, attractionById);

        int seq = 1;
        for (GeneratedItineraryDTO.GeneratedNode gn : gd.getNodes()) {
            // 去重保护:同一家餐厅不重复用作多个餐饮节点。
            // 实测模型会连着 5 顿都排同一家店(它只在池子里看到那几个候选),
            // 这样的"行程"显然不能给用户。重复的直接降级为普通建议项,不再绑定该餐厅。
            ItineraryNode node = new ItineraryNode();
            node.setDayId(day.getId());
            node.setNodeType(gn.getNodeType());

            boolean isAttraction = "景点".equals(gn.getNodeType());
            boolean isRestaurant = "餐饮".equals(gn.getNodeType());

            if (isAttraction && poolActive) {
                // 景点必须能在候选池里按 poiId 找到真实高德 POI
                SpotVO real = gn.getPoiId() == null ? null : attractionById.get(gn.getPoiId());
                if (real == null) {
                    // 编造/越界/缺 ID 的景点:硬拦截,不落库。这是"候选池"杜绝幻觉景点的落点。
                    log.warn("拦截越界景点: poiId={}, placeName={} 不在候选池,已丢弃该节点",
                            gn.getPoiId(), gn.getPlaceName());
                    continue;
                }
                node.setPlaceName(real.getName());                          // 回填真实名称(不信任模型手打的名字)
                node.setCost(parseCost(real.getCost(), gn.getCost()));      // 门票以高德真实值优先,解析不出再退回模型估算
                // 立即绑定 placeId:景点本来就是从高德候选池里选的,poiId 就是真实 POI。
                // 以前这里留空、等"校验"阶段再按名字重查一遍,导致用户生成完看到的是
                // 满屏"尚未核验",必须再点一次校验才有坐标/导航——体验断裂且多一次浪费的请求。
                // 现在生成即完成定位;校验只负责补那些确实缺 placeId 的节点。
                node.setPlaceId(resolveAttractionPlaceId(real, city));
            } else if (isRestaurant && usedRestaurants.contains(gn.getPoiId())) {
                // 这家餐厅今天/本次已经用过了:不再绑定坐标,只当作普通推荐保留名字
                node.setPlaceName(gn.getPlaceName());
                node.setCost(gn.getCost());
                log.debug("餐饮重复,已解除地点绑定: {}", gn.getPlaceName());
            } else if (isRestaurant && restaurantById != null && gn.getPoiId() != null
                    && restaurantById.containsKey(gn.getPoiId())) {
                // 模型从餐厅候选池里选中的真实餐厅。
                Place real = restaurantById.get(gn.getPoiId());

                // 位置合理性兜底(实测最关键的一道):
                // 模型会挑中"真实存在但离当天景点十万八千里"的餐厅——把铁像寺的店
                // 排在武侯祠那天,两地相距 9.9 公里,中午来回两小时,行程直接不可用。
                // 名字是真的、高德校验也会通过,所以只能靠坐标距离来兜。
                //
                // 修法不是"解除绑定"(那样校验阶段会按名字又绑回去,等于没修),
                // 而是【换一家离当天景点近的池内餐厅】:既保证真实可导航,又保证不绕路。
                boolean tooFar = nearestDistance(real, dayAttractionPoints) > RESTAURANT_MAX_DETOUR_M;
                boolean located = true;
                if (tooFar) {
                    Place nearer = pickNearbyRestaurant(restaurantById, usedRestaurants, dayAttractionPoints);
                    if (nearer != null) {
                        log.info("餐饮离当天景点太远已就近替换: {} -> {}", real.getName(), nearer.getName());
                        real = nearer;
                    } else {
                        // 池子里没有更近的了。保留名字(用户仍能看到推荐),但不绑坐标:
                        // 否则导航会把人带到 9 公里外,比不给导航更糟。
                        log.warn("餐饮离当天景点太远且无可替换的候选: {} (保留名称、不绑定坐标)", real.getName());
                        located = false;
                    }
                }
                node.setPlaceName(real.getName());
                node.setCost(gn.getCost());   // 人均信息在候选池里,落库沿用模型估算即可
                // 关键:绑定 placeId。节点的坐标来自 place 表(见 ItineraryServiceImpl.toNodeVO),
                // 不绑这个外键的话,餐厅即使从真实候选池里选出来也拿不到坐标、不可导航。
                if (located) {
                    node.setPlaceId(real.getId());
                    usedRestaurants.add(real.getAmapPoiId());
                }
            } else if ("住宿".equals(gn.getNodeType())) {
                // 住宿:定位到"区域"而不是具体酒店。
                // 用户选了住宿区域就查这个区域;没选就用模型写的名字试一次。
                // 查到 -> 绑定坐标,节点能在地图上看到、能算离景点多远;
                // 查不到 -> 保持文字(前端显示"建议区域"),不画假点。
                String areaName = hasText(city) && hasText(gn.getPlaceName())
                        ? cleanLodgingName(gn.getPlaceName()) : null;
                Place area = areaName == null ? null : amapClient.resolveArea(areaName, city);

                // 住宿位置合理性兜底(实测踩过的坑):
                // 用户选了"换着住"时,模型会把三天全排在"邛崃市"——离市区景点 60 多公里,
                // 而当天景点全在成都市内。这种行程用户根本没法用。
                // 这里用"住宿离当天最近景点"的距离做判断,太远就换成离景点最近的候选区域。
                if (area != null && nearestPlaceDistance(area, dayAttractionPoints) > LODGING_MAX_DISTANCE_M) {
                    // 太远 → 不用这个区域。不要拿景点名当住宿名(会出现"住宿:武侯祠博物馆"这种荒唐结果),
                    // 退回通用文案,前端会显示"未核验"，用户据此自己决定。
                    log.warn("住宿离当天景点太远已弃用: {} (超过 {} 公里)",
                            area.getName(), LODGING_MAX_DISTANCE_M / 1000);
                    area = null;
                }

                if (area != null) {
                    node.setPlaceName(area.getName());
                    node.setPlaceId(resolveAttractionPlaceId(toSpotVO(area), city));
                } else if (hasText(gn.getPlaceName()) && !looksLikeAttractionName(gn.getPlaceName())) {
                    // 模型给的名字不像景点名，就保留(比如"春熙路")，但不绑定坐标
                    node.setPlaceName(gn.getPlaceName());
                } else {
                    node.setPlaceName("建议住宿区域");
                }
                node.setCost(gn.getCost());
            } else {
                // 交通,或候选池未启用(高德失败降级)时:沿用模型给的名字与估算
                node.setPlaceName(gn.getPlaceName());
                node.setCost(gn.getCost());
            }

            node.setSeq(seq++);                     // 顺序按数组先后;仅真正落库的节点才占号
            node.setStartTime(parseTime(gn.getStartTime()));
            node.setEndTime(parseTime(gn.getEndTime()));
            nodeMapper.insert(node);
        }
    }

    /**
     * 住宿名清洗:模型常写"建议住宿区域：武侯祠-锦里一带""春熙路-太古里一带",
     * 拿去高德搜会失败。去掉前缀噪音和"一带/附近"这类后缀,只留地名本身。
     */
    private String cleanLodgingName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 去掉"建议住宿区域：""推荐住宿"这类前缀
        int idx = Math.max(s.lastIndexOf('：'), s.lastIndexOf(':'));
        if (idx >= 0 && idx < s.length() - 1) s = s.substring(idx + 1).trim();
        if (s.startsWith("建议") || s.startsWith("推荐")) {
            int c = s.indexOf('：');
            if (c < 0) c = s.indexOf(':');
            if (c >= 0) s = s.substring(c + 1).trim();
        }
        // 去掉后缀
        for (String suffix : new String[]{"一带", "附近", "周边", "区域", "片区"}) {
            if (s.endsWith(suffix)) s = s.substring(0, s.length() - suffix.length()).trim();
        }
        // "武侯祠-锦里" 这种复合名取第一个,高德搜复合名常常查不到
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 名字看起来像不像"景点"。
     * 用于挡住模型把住宿写成"成都武侯祠博物馆"这种情况——景点名当住宿名是明显错误。
     */
    private boolean looksLikeAttractionName(String name) {
        if (name == null) return false;
        return name.contains("博物馆") || name.contains("公园") || name.contains("古街")
                || name.contains("景区") || name.contains("古镇") || name.contains("遗址")
                || name.contains("故居") || name.contains("寺") || name.contains("宫")
                || name.contains("塔") || name.contains("楼") || name.contains("观")
                || name.contains("艺术") || name.contains("基地") || name.contains("园区");
    }

    /** Place -> SpotVO(仅用于复用 resolveAttractionPlaceId 的落库逻辑) */
    private SpotVO toSpotVO(Place p) {
        SpotVO s = new SpotVO();
        s.setName(p.getName());
        s.setAmapPoiId(p.getAmapPoiId());
        s.setCategory(p.getCategory());
        s.setAddress(p.getAddress());
        s.setLng(p.getLongitude());
        s.setLat(p.getLatitude());
        return s;
    }

    /**
     * 把候选池里的景点(高德 POI)落成 Place 并返回其主键,供节点直接绑定。
     *
     * 为什么要落库:itinerary_node 只存 placeId 整型外键,坐标在 place 表里
     * (详见 ItineraryServiceImpl.toNodeVO)。不落库就没法绑定,节点也就没法定位。
     *
     * 去重:同一个高德 POI 反复出现时复用已有记录(place 表按 amap_poi_id 缓存)。
     * 失败返回 null —— 该节点退回"未核验",不影响整条生成。
     */
    private Integer resolveAttractionPlaceId(SpotVO spot, String city) {
        if (spot == null || spot.getAmapPoiId() == null || spot.getAmapPoiId().isBlank()) return null;
        try {
            Place cached = placeMapper.findByAmapPoiId(spot.getAmapPoiId());
            if (cached != null) return cached.getId();
            Place p = new Place();
            p.setName(spot.getName());
            p.setAmapPoiId(spot.getAmapPoiId());
            p.setCategory(truncateCategory(spot.getCategory()));
            p.setAddress(spot.getAddress());
            p.setCity(city);
            // SpotVO 的坐标已经是 WGS-84(browseSpots 里转过了),与 place 表存法一致
            p.setLongitude(spot.getLng());
            p.setLatitude(spot.getLat());
            placeMapper.insert(p);
            return p.getId();
        } catch (Exception e) {
            log.warn("景点落库失败,该节点将显示为未核验: name={}, err={}", spot.getName(), e.getMessage());
            return null;
        }
    }

    /** 任意 Place 到当天最近景点的距离;当天没有可用景点坐标返回 0 */
    private double nearestPlaceDistance(Place p, List<double[]> dayPoints) {
        if (p == null || p.getLatitude() == null || p.getLongitude() == null) return 0;
        if (dayPoints == null || dayPoints.isEmpty()) return 0;
        double lat = p.getLatitude().doubleValue();
        double lng = p.getLongitude().doubleValue();
        double min = Double.MAX_VALUE;
        for (double[] pt : dayPoints) {
            double d = com.xingji.util.GeoDistance.meters(lat, lng, pt[0], pt[1]);
            if (d >= 0 && d < min) min = d;
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    /**
     * 从当天景点里挑一个"离景点最近"的地点作为住宿兜底。
     * 直接复用当天第一个景点(行程已经选好的、离得最近的锚点),比再造一个候选池简单可靠。
     */
    private Place pickNearestCandidate(Map<String, Place> restaurantById,
                                       Map<String, SpotVO> attractionById,
                                       List<double[]> dayPoints) {
        if (attractionById == null || attractionById.isEmpty()) return null;
        Place best = null;
        double bestDist = Double.MAX_VALUE;
        for (SpotVO s : attractionById.values()) {
            if (s.getLat() == null || s.getLng() == null) continue;
            Place p = new Place();
            p.setName(s.getName());
            p.setAmapPoiId(s.getAmapPoiId());
            p.setCategory(s.getCategory());
            p.setLongitude(s.getLng());
            p.setLatitude(s.getLat());
            double d = nearestPlaceDistance(p, dayPoints);
            if (d >= 0 && d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    /**
     * 收集当天所有"在候选池里、有坐标"的景点的坐标对 [lat, lng]。
     * 用于判断餐饮节点离当天行程有多远。
     */
    private List<double[]> collectDayAttractionPoints(GeneratedItineraryDTO.GeneratedDay gd,
                                                      Map<String, SpotVO> attractionById) {
        List<double[]> pts = new ArrayList<>();
        if (gd.getNodes() == null || attractionById == null) return pts;
        for (GeneratedItineraryDTO.GeneratedNode gn : gd.getNodes()) {
            if (!"景点".equals(gn.getNodeType()) || gn.getPoiId() == null) continue;
            SpotVO s = attractionById.get(gn.getPoiId());
            if (s == null || s.getLat() == null || s.getLng() == null) continue;
            pts.add(new double[]{s.getLat().doubleValue(), s.getLng().doubleValue()});
        }
        return pts;
    }

    /**
     * 从候选池里挑一家"离当天景点最近、且本次还没用过"的餐厅,用于替换排得太远的那家。
     * 没有合格候选返回 null(调用方会保留原名但不定位)。
     */
    private Place pickNearbyRestaurant(Map<String, Place> restaurantById,
                                       java.util.Set<String> usedRestaurants,
                                       List<double[]> dayPoints) {
        if (restaurantById == null || restaurantById.isEmpty()) return null;
        Place best = null;
        double bestDist = RESTAURANT_MAX_DETOUR_M;
        for (Place cand : restaurantById.values()) {
            if (cand.getAmapPoiId() == null || usedRestaurants.contains(cand.getAmapPoiId())) continue;
            double d = nearestDistance(cand, dayPoints);
            if (d > 0 && d < bestDist) {   // d>0:跳过没有坐标的
                bestDist = d;
                best = cand;
            }
        }
        return best;
    }

    /**
     * 餐厅到当天最近景点的距离(米)。
     * 当天没有可用景点坐标时返回 0(视为不构成绕路,不做拦截)。
     */
    private double nearestDistance(Place restaurant, List<double[]> dayPoints) {
        if (restaurant == null || restaurant.getLatitude() == null || restaurant.getLongitude() == null) return 0;
        if (dayPoints == null || dayPoints.isEmpty()) return 0;
        double lat = restaurant.getLatitude().doubleValue();
        double lng = restaurant.getLongitude().doubleValue();
        double min = Double.MAX_VALUE;
        for (double[] p : dayPoints) {
            double d = com.xingji.util.GeoDistance.meters(lat, lng, p[0], p[1]);
            if (d >= 0 && d < min) min = d;
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    /** 方案说明序列化成 JSON 文本存 itinerary.advice;null 或失败都存 null,绝不影响行程主体 */
    private String serializeAdvice(GeneratedItineraryDTO.GeneratedAdvice advice) {
        if (advice == null) return null;
        try {
            return objectMapper.writeValueAsString(advice);
        } catch (Exception e) {
            log.warn("方案说明序列化失败,本次忽略", e);
            return null;
        }
    }

    /**
     * 到达/返程硬锚点契约:
     *  ① arrival 存在 → startDate 强制跟随 arrival.date(硬锚优先于用户传的 startDate);
     *  ② departure 存在 → 不早于 arrival、不早于行程末(startDate + days - 1),违反就抛 BizException。
     * 任一 null 都完全跳过,老 payload 零副作用。
     */
    private void applyAnchorContract(GenerateRequestDTO req) {
        GenerateRequestDTO.TransitAnchor a = req.getArrival();
        GenerateRequestDTO.TransitAnchor d = req.getDeparture();

        // ① arrival 驱动 startDate 对齐
        if (a != null && a.getDatetime() != null) {
            LocalDate arrivalDate = a.getDatetime().toLocalDate();
            if (req.getStartDate() != null && !req.getStartDate().equals(arrivalDate)) {
                log.warn("用户传的 startDate={} 与 arrival.date={} 不一致,以 arrival 为准(硬锚)",
                        req.getStartDate(), arrivalDate);
            }
            req.setStartDate(arrivalDate);
        }

        // ② departure 交叉校验
        if (d != null && d.getDatetime() != null) {
            LocalDate depDate = d.getDatetime().toLocalDate();

            if (a != null && a.getDatetime() != null && d.getDatetime().isBefore(a.getDatetime())) {
                throw new BizException(400, "返程时间早于到达时间,请检查到达/返程锚点");
            }

            if (req.getStartDate() != null && req.getDays() != null && req.getDays() > 0) {
                LocalDate tripEnd = req.getStartDate().plusDays(req.getDays() - 1L);
                if (depDate.isBefore(tripEnd)) {
                    throw new BizException(400, "返程日期(" + depDate + ")早于行程最后一天(" + tripEnd
                            + "),请调整天数或返程时间");
                }
            }
        }
    }

    /** 把用户诉求 + 旅行画像 + 候选景点池 + 天气拼成模型可读的 user 提示词 */
    /**
     * 从会话里提炼"对话要点"：画像里的 notes + 用户最近几条原话。
     * 只接受当前用户自己的会话；会话不存在或不属于本人时返回空串，生成照常进行。
     */
    private String buildDialogBrief(Integer userId, Integer sessionId) {
        if (sessionId == null) return "";
        ChatSession session = chatSessionMapper.findById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) return "";
        List<String> parts = new ArrayList<>();
        try {
            ChatProfileDTO p = chatContextService.readProfile(session);
            if (p != null && p.getNotes() != null && !p.getNotes().isEmpty()) {
                parts.add("要点：" + String.join("；", p.getNotes()));
            }
        } catch (Exception e) {
            log.debug("会话画像解析失败，仅使用原话，sessionId={}", sessionId, e);
        }
        List<ChatMessage> msgs = chatMessageMapper.findBySessionId(sessionId);
        List<String> users = new ArrayList<>();
        for (int i = msgs.size() - 1; i >= 0 && users.size() < 4; i--) {
            ChatMessage m = msgs.get(i);
            if (!"user".equals(m.getRole()) || m.getContent() == null || m.getContent().isBlank()) continue;
            String c = m.getContent().trim().replaceAll("\\s+", " ");
            if (c.length() > 120) c = c.substring(0, 120) + "…";
            users.add(0, c);
        }
        if (!users.isEmpty()) parts.add("用户原话：" + String.join(" / ", users));
        return String.join("\n", parts);
    }

    private String buildUserPrompt(GenerateRequestDTO req, PlannerContext ctx, String dialogBrief) {
        StringBuilder sb = new StringBuilder();
        sb.append("目的地:").append(req.getCity())
                .append(";天数:").append(req.getDays());
        if (req.getBudget() != null) sb.append(";预算:").append(req.getBudget()).append("元");
        if (req.getStartDate() != null) sb.append(";出发日期:").append(req.getStartDate());
        if (req.getPreferences() != null && !req.getPreferences().isEmpty())
            sb.append(";偏好:").append(String.join("、", req.getPreferences()));

        // ===== 旅行画像段:逐项可选,填了才拼,由 system 提示词里的规则消费 =====
        if (req.getGroupSize() != null || hasText(req.getCompanionType())) {
            sb.append(";同行:");
            if (req.getGroupSize() != null) sb.append(req.getGroupSize()).append("人");
            if (hasText(req.getCompanionType())) {
                if (req.getGroupSize() != null) sb.append("·");
                sb.append(req.getCompanionType());
            }
        }
        if (hasText(req.getPace())) sb.append(";节奏:").append(req.getPace());
        if (hasText(req.getTransport())) sb.append(";市内交通:").append(req.getTransport());
        if (hasText(req.getLodging())) {
            sb.append(";住宿区域:").append(req.getLodging());
        }
        // 住宿方式决定行程结构:住一处=全程同一区域;换着住=按天分段。
        // 以前这里只传了住宿文本、没传方式,模型就按自己理解排,经常出现"每天都写同一个区域"
        // 或"某天跑去很远的景点当天往返"这类不合理安排。
        if (hasText(req.getLodgingMode())) {
            sb.append(";住宿方式:").append(req.getLodgingMode());
        } else if (hasText(req.getLodging())) {
            sb.append(";住宿方式:住一处(用户未说明,默认)");
        }
        if (req.getMustVisit() != null && !req.getMustVisit().isEmpty())
            sb.append(";必须去:").append(String.join("、", req.getMustVisit()));
        if (req.getAvoid() != null && !req.getAvoid().isEmpty())
            sb.append(";回避:").append(String.join("、", req.getAvoid()));
        java.util.List<com.xingji.dto.TravelConstraintDTO> cons =
                com.xingji.chat.ConstraintExtractor.active(req.getConstraints());
        if (!cons.isEmpty()) {
            sb.append(";硬约束(必须遵守):");
            for (com.xingji.dto.TravelConstraintDTO c : cons) {
                sb.append(c.getContent());
                if (c.getType() == com.xingji.dto.ConstraintType.SAFETY) {
                    sb.append("(安全约束，无法确认安全性的项目不得安排)");
                }
                sb.append("、");
            }
            if (sb.charAt(sb.length() - 1) == '、') sb.setLength(sb.length() - 1);
        }

        // 到达/返程硬锚(仅在用户/上层提供了 datetime 时才拼,老 payload 完全跳过)
        appendAnchorLines(sb, req);

        // 对话要点:用户在聊天里说清、但表单装不下的约束（老人/节奏/回避细节等）
        if (dialogBrief != null && !dialogBrief.isBlank()) {
            sb.append("\n\n【本次对话已确认的要点（优先级高于上面的表单默认值；冲突时以对话里最近的说法为准）】\n")
                    .append(dialogBrief);
        }

        // 天气(第 2 个数据源):有就带上,指导模型做室内外/避正午的排布
        if (ctx != null && ctx.getWeatherBrief() != null && !ctx.getWeatherBrief().isBlank()) {
            sb.append(";当地天气预报:").append(ctx.getWeatherBrief());
        }

        // 候选景点池:让模型只能从这里选 poiId
        sb.append(renderCandidatePool(ctx));
        // 候选餐厅池:让餐饮节点也能落到真实地点(带坐标→可导航)
        sb.append(renderRestaurantPool(ctx));
        return sb.toString();
    }

    /**
     * 把真实餐厅候选池渲染成给模型的文本,每行:编号 | POI_ID | 餐厅名 | 区域 | 类型 | 人均。
     *
     * 空池时返回空串——高德取不到就退回模型自由给名字(那些节点会是"未核验"状态,
     * 前端会诚实显示,不会画假坐标)。
     */
    private String renderRestaurantPool(PlannerContext ctx) {
        if (ctx == null || ctx.getRestaurants() == null || ctx.getRestaurants().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n可选餐厅池(餐饮节点的 poiId 只能从下列 ID 中选,禁止编造池外餐厅):\n");
        int i = 1;
        for (com.xingji.vo.RestaurantCandidateVO r : ctx.getRestaurants()) {
            if (r.getAmapPoiId() == null || r.getAmapPoiId().isBlank()) continue;
            sb.append(i++).append(" | ").append(r.getAmapPoiId()).append(" | ").append(r.getName());
            if (r.getArea() != null) sb.append(" | ").append(r.getArea()).append("附近");
            if (r.getCategory() != null) sb.append(" | ").append(shortCategory(r.getCategory()));
            if (r.getCost() != null && !r.getCost().isBlank()) sb.append(" | 人均").append(r.getCost());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 高德的 type 是"餐饮服务;中餐厅;四川菜(川菜)"这种长串,只取最后一段作菜系 */
    private String shortCategory(String category) {
        if (category == null) return null;
        String[] parts = category.split(";");
        return parts.length > 0 ? parts[parts.length - 1].trim() : category;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 把到达/返程锚点 + 代码推导的住宿晚数 + 首末日边界指令,拼进 user prompt。
     * 三个都可选,任一缺失只跳过对应片段;两个都缺时整段不追加,老 prompt 一字不变。
     */
    private void appendAnchorLines(StringBuilder sb, GenerateRequestDTO req) {
        GenerateRequestDTO.TransitAnchor a = req.getArrival();
        GenerateRequestDTO.TransitAnchor d = req.getDeparture();
        boolean hasA = a != null && a.getDatetime() != null;
        boolean hasD = d != null && d.getDatetime() != null;

        if (hasA) sb.append(";到达锚点:").append(formatAnchor(a));
        if (hasD) sb.append(";返程锚点:").append(formatAnchor(d));

        // 住宿晚数由代码推导(不是让模型自报),放在锚点之后紧跟指令
        if (hasA && hasD) {
            long nights = ChronoUnit.DAYS.between(
                    a.getDatetime().toLocalDate(), d.getDatetime().toLocalDate());
            if (nights > 0) {
                sb.append(";住宿晚数:").append(nights).append("晚(代码按到达/返程日期推导,请勿自行更改)");
            }
        }

        // 首末日排程硬约束:任一锚点存在就拼上,告诉模型哪一侧要收边界
        if (hasA || hasD) {
            sb.append(";排程硬约束:");
            if (hasA) {
                sb.append("首日第一个景点的开始时间不得早于到达时刻+90分钟(接驳+入住缓冲);");
            }
            if (hasD) {
                sb.append("末日最后一个景点/餐饮的结束时间不得晚于返程时刻-150分钟(市内交通+值机/提前到站+缓冲)。");
            }
        }
    }

    /** 单个锚点渲染成"2026-11-01 14:00 @ 成都东站(train)"这种一行紧凑文本;location/type 缺失自动降级 */
    private String formatAnchor(GenerateRequestDTO.TransitAnchor t) {
        StringBuilder s = new StringBuilder();
        s.append(t.getDatetime().toLocalDate()).append(" ").append(t.getDatetime().toLocalTime());
        if (t.getLocation() != null && !t.getLocation().isBlank()) s.append(" @ ").append(t.getLocation());
        if (t.getType() != null && !t.getType().isBlank()) s.append("(").append(t.getType()).append(")");
        return s.toString();
    }

    /**
     * 把真实景点候选池渲染成给模型的文本,每行:编号 | POI_ID | 名称 | 分类 | 人均 | 开放时间。
     * 空池(高德取数失败降级)返回空串——此时 prompt 里没有池,模型只能自由发挥,
     * 服务层的 poolActive 也会是 false、不做ID拦截,二者语义一致。
     */
    private String renderCandidatePool(PlannerContext ctx) {
        if (ctx == null || ctx.getAttractions() == null || ctx.getAttractions().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n可选景点池(景点节点的 poiId 只能从下列 ID 中选,禁止编造池外景点):\n");
        int i = 1;
        for (SpotVO s : ctx.getAttractions()) {
            if (s.getAmapPoiId() == null || s.getAmapPoiId().isBlank()
                    || s.getName() == null || s.getName().isBlank()) {
                continue;   // 缺 id 或名字的脏数据不进池,免得模型选到却回填不出
            }
            sb.append(i++).append(" | ").append(s.getAmapPoiId()).append(" | ").append(s.getName());
            String cat = simplifyCategory(s.getCategory());
            if (cat != null) sb.append(" | ").append(cat);
            if (s.getCost() != null && !s.getCost().isBlank()) sb.append(" | 人均").append(s.getCost());
            if (s.getOpenTime() != null && !s.getOpenTime().isBlank()) sb.append(" | ").append(s.getOpenTime());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 高德分类是分号串(如"风景名胜;风景名胜;国家级景点"),取首段做简化;空则返回 null */
    private String simplifyCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int idx = raw.indexOf(';');
        String head = idx > 0 ? raw.substring(0, idx) : raw;
        head = head.trim();
        return head.isEmpty() ? null : head;
    }

    /** 高德真实门票/人均字符串转 BigDecimal;非数字或空则回退到模型估算值 */
    private BigDecimal parseCost(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 取最外层 { 到 } 之间的内容,顺带剥掉 ```json 围栏和模型爱加的客套话 */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) throw new BizException("AI 返回为空");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new BizException("AI 返回中找不到 JSON");
        return raw.substring(start, end + 1);
    }

    /**
     * 抠 JSON → 修复 → 解析。任一步失败返回 null,由调用方决定重试还是报错。
     *
     * 先走 JsonRepair:实测模型最常见的错误是时间值漏引号("endTime":17:00),
     * 这类问题用代码能稳定修好,不需要再花一次几分钟的模型调用。
     */
    /** 按天重排的解析入口:同样先修复再解析,失败返回 null 交给调用方重试 */
    private GeneratedItineraryDTO.GeneratedDay parseDayOrNull(String raw) {
        String json = com.xingji.util.JsonRepair.extractAndRepair(raw);
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, GeneratedItineraryDTO.GeneratedDay.class);
        } catch (Exception e) {
            log.warn("按天重排 JSON 解析失败, 修复后前200字={}", json.length() > 200 ? json.substring(0, 200) : json);
            return null;
        }
    }

    private GeneratedItineraryDTO parseOrNull(String raw) {
        String json = com.xingji.util.JsonRepair.extractAndRepair(raw);
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, GeneratedItineraryDTO.class);
        } catch (Exception e) {
            log.warn("行程 JSON 解析失败, 修复后内容前200字={}", json.length() > 200 ? json.substring(0, 200) : json);
            return null;
        }
    }

    /** "HH:mm" 转 LocalTime;空或格式错返回 null(时间非生成关键,不硬失败) */
    private LocalTime parseTime(String t) {
        if (t == null || t.isBlank()) return null;
        try {
            return LocalTime.parse(t.trim());
        } catch (Exception e) {
            return null;
        }
    }
}