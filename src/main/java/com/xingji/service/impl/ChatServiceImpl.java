package com.xingji.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.LLM.AiTask;
import com.xingji.LLM.ChatIntentRouter;
import com.xingji.LLM.LlmClient;
import com.xingji.LLM.PromptLoader;
import com.xingji.chat.ChatActionResolver;
import com.xingji.chat.ChatContextBuilder;
import com.xingji.chat.ChatContextService;
import com.xingji.chat.ChatProfilePatchGuard;
import com.xingji.chat.ChatReplySanitizer;
import com.xingji.chat.ChatStrategyService;
import com.xingji.chat.ConstraintExtractor;
import com.xingji.chat.ConversationStateService;
import com.xingji.chat.DateEvidence;
import com.xingji.common.BizException;
import com.xingji.dto.AnchorStateDTO;
import com.xingji.dto.ChatAction;
import com.xingji.dto.ChatContextDTO;
import com.xingji.dto.ChatIntent;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatSendDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.dto.ConversationStateDTO;
import com.xingji.dto.ConversationUpdateDTO;
import com.xingji.dto.TimePeriod;
import com.xingji.dto.TravelConstraintDTO;
import com.xingji.mapper.ChatMessageMapper;
import com.xingji.mapper.ChatSessionMapper;
import com.xingji.pojo.ChatMessage;
import com.xingji.pojo.ChatSession;
import com.xingji.service.ChatService;
import com.xingji.vo.ChatSessionVO;
import com.xingji.vo.ChatTurnVO;
import com.xingji.vo.SpotVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话服务:单一对话层 + 服务端画像状态。
 *
 * 核心分工:
 *   模型只做两件事——理解用户这句话、写一句自然回复,并把"这句话里新增/修改的画像字段"
 *   输出成 profile_patch;
 *   服务端负责事实——画像合并、日期/时间/枚举校验、缺失项计算、持久化到 chat_session.context。
 *
 * 这样"用户最新原话"永远能覆盖旧画像,而不是被前端当成权威的旧表单挡住;
 * 模型偶尔算错日期/时间也不会污染画像,更不会因为拿不到日期就把已经说清的时间、高铁一起丢掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /** 历史消息最多带多少条进模型上下文(约 10 轮),防止长会话把 token 撑爆 */
    private static final int HISTORY_LIMIT = 20;

    /** 一次行程最多 30 天,和前端输入上限保持一致 */
    private static final int MAX_DAYS = 30;

    /**
     * 单条用户输入上限(字)。
     * 正常排行程用不到这么多——实测正常对话每句 10~20 字。
     * 这里不是"限制用户表达",是防止粘贴整篇小说/代码把上下文撑爆;超了给明确提示,不静默截断。
     */
    private static final int MAX_INPUT_CHARS = 500;

    private static final Pattern DATE_FULL = Pattern.compile("(20\\d{2})\\D{1,3}(\\d{1,2})\\D{1,3}(\\d{1,2})");
    private static final Pattern DATE_MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern DATE_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[日号]");
    private static final Pattern TIME_HM = Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{1,2})");
    private static final Pattern TIME_CN = Pattern.compile("(凌晨|早上|上午|中午|下午|傍晚|晚上)?\\s*(\\d{1,2})\\s*[点時时](?:\\s*(半|(\\d{1,2})\\s*分?))?");
    private static final Pattern REPLY_JSON = Pattern.compile("\"reply\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final com.xingji.mapper.ItineraryMapper itineraryMapper;   // 删会话前解除行程对会话的外键引用
    private final LlmClient llmClient;          // 底层按 AiTask.CHAT 路由到对话模型
    private final ObjectMapper objectMapper;    // 解析模型 JSON、序列化画像 context
    private final PromptLoader promptLoader;    // 取对话 system 规则
    private final ChatIntentRouter intentRouter; // 调模型前的确定性分流:寒暄/越界/规划
    private final com.xingji.LLM.ChatSpotRecommender spotRecommender; // 对话里的景点推荐取数
    private final ChatContextService contextService; // context 读写与旧画像迁移
    private final ChatContextBuilder contextBuilder;     // 动态提示词组装
    private final ChatProfilePatchGuard profilePatchGuard; // 纯确认词不凭空补画像
    private final ChatReplySanitizer replySanitizer;      // 回复中的无依据日期/价格兜底
    private final ChatStrategyService strategyService;   // 本轮该答/该建议/该问
    private final ConversationStateService stateService; // 对话状态合并
    private final ChatActionResolver actionResolver;     // CHAT / READY / GENERATE

    @Override
    public Integer createSession(Integer userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        session.setContext(contextService.write(new ChatProfileDTO(), new ConversationStateDTO()));
        sessionMapper.insert(session);          // 回填 id
        return session.getId();
    }

    @Override
    public List<ChatSession> listSessions(Integer userId) {
        return sessionMapper.findByUserId(userId);
    }

    @Override
    public ChatSessionVO getSession(Integer sessionId, Integer userId) {
        ChatSession session = ownedSession(sessionId, userId);   // 归属校验
        ChatContextDTO ctx = contextService.read(session);
        ChatProfileDTO profile = ctx.getProfile();
        ChatSessionVO vo = new ChatSessionVO();
        vo.setSession(session);
        vo.setMessages(messageMapper.findBySessionId(sessionId));
        vo.setProfile(profile);
        vo.setMissing(computeMissing(profile));
        vo.setConstraints(profile.getConstraints());
        vo.setConversationState(ctx.getConversationState());
        return vo;
    }

    @Override
    public ChatTurnVO send(Integer userId, ChatSendDTO dto) {
        ChatSession session = ownedSession(dto.getSessionId(), userId);
        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        if (content.isEmpty()) throw new BizException(400, "消息内容不能为空");

        List<String> corrections = new ArrayList<>();

        // 0) 输入超长保护:拦截粘贴整篇小说/代码的情况,而不是静默截断把用户的话切掉一半。
        if (content.length() > MAX_INPUT_CHARS) {
            throw new BizException(400, "这句话有点太长了（超过 " + MAX_INPUT_CHARS + " 字），"
                    + "排行程用不着这么多。挑重点说说想去哪、玩几天就行。");
        }

        // 1) 读服务端画像;前端表单是同一条事实的"视图",用户手动改过就以表单为准。
        //    本轮模型补丁最后再叠加,因此"用户刚说出口的话"优先级最高。
        ChatContextDTO ctx = contextService.read(session);
        ChatProfileDTO profile = ctx.getProfile();
        ConversationStateDTO state = ctx.getConversationState();
        profile = overlayClientProfile(profile, dto.getProfile(), corrections);
        DateEvidence evidence = DateEvidence.of(content);

        // 2) 调模型前先做确定性分流。寒暄和越界请求根本不该打扰大模型:
        //    寒暄走模型纯属浪费(实测 1~11 秒换一句模板话);
        //    越界请求更要拦——它一旦被模型回答,这次往返就会进历史,后续每轮模型都能看见
        //    "我以前讲过笑话",规则被一点点稀释,实测连续 8 次越界一次都没拒。
        //    服务端拦截 = 不产生历史,也就不会被后面的轮次当成证据。
        ChatIntentRouter.Intent intent = intentRouter.route(content);
        if (intent == ChatIntentRouter.Intent.SMALLTALK) {
            boolean continueActiveTrip = intentRouter.isBareAcknowledgement(content)
                    && hasActiveTravelContext(profile);
            if (!continueActiveTrip) {
                return replyWithoutModel(session, profile, state, content, ChatIntent.SMALL_TALK,
                        intentRouter.smalltalkReply(normalizeForSmalltalk(content), nz(profile.getCity()),
                                computeMissing(profile)), corrections);
            }
        }
        if (intent == ChatIntentRouter.Intent.OFF_TOPIC) {
            log.info("对话越界请求已服务端拦截, sessionId={}, content={}", session.getId(), content);
            return replyWithoutModel(session, profile, state, content, ChatIntent.OUT_OF_SCOPE,
                    intentRouter.offTopicReply(nz(profile.getCity())), corrections);
        }

        // 2.5) 景点推荐意图:用户问"推荐些景点/有什么好玩的"时,
        //      去高德拉真实景点(带照片/评分/门票),同时告诉模型"这些景点是什么",
        //      让模型基于真实数据写引导语,而不是凭记忆编三个名字。
        List<SpotVO> spots = new ArrayList<>();
        if (spotRecommender.wantsSpots(content)) {
            // 城市取"画像里已有的"或"这句话里刚提到的"。
            // 只取画像会漏掉最常见的情况:用户第一句就是"我想去北海玩,推荐景点",
            // 此时本轮抽出的城市还没合并进画像,拿到的会是 null。
            String spotCity = !isBlank(profile.getCity()) ? profile.getCity() : spotRecommender.detectCity(content);
            if (!isBlank(spotCity)) {
                spots = spotRecommender.fetch(spotCity,
                        ConstraintExtractor.active(profile.getConstraints()));
                log.info("对话景点推荐: city={} 取到 {} 个真实景点", spotCity, spots.size());
            }
        }

        // 3) 先读历史(此时还没写本轮 user),再调模型。历史截断到最后 N 条。
        List<ChatMessage> prior = messageMapper.findBySessionId(session.getId());
        List<Map<String, String>> history = toHistory(prior);

        // 4) 模型输入:今天 + 已确认画像 + 服务端算出的缺失项 + 上一轮问过什么 + 用户原话。
        //    模型不负责决定"缺什么",只负责理解这句话和写回复。
        String system = promptLoader.getPrompt(AiTask.CHAT);
        List<String> missing = computeMissing(profile);
        boolean readyForGen = actionResolver.ready(profile);
        ChatStrategy strategy = strategyService.decide(profile, state, content, readyForGen, missing);
        boolean bareAcknowledgement = intentRouter.isBareAcknowledgement(content);
        String lastAssistantReply = extractLastAssistantReply(prior);
        String userTurn = contextBuilder.build(content, profile, strategy, missing,
                extractLastAsked(prior), extractFacts(content), spots, readyForGen,
                lastAssistantReply, bareAcknowledgement);
        String raw;
        try {
            raw = llmClient.chat(AiTask.CHAT, system, history, userTurn);
            // 模型偶发返回空正文(如思考吃满 token 预算)。这种情况一句模板会把用户刚说的话吞掉,
            // 先原样重试一次;仍为空才走下面的兜底话术。
            if (raw == null || raw.isBlank()) {
                log.warn("对话模型返回空内容,重试一次, sessionId={}", session.getId());
                raw = llmClient.chat(AiTask.CHAT, system, history, userTurn);
            }
        } catch (Exception e) {
            // 免费模型(智谱 Flash)有频率限制,实测并发稍高就返回 429。
            // 这种是"过一会儿就好"的暂时故障,自动退避重试,别让用户自己点重发。
            if (isRateLimited(e)) {
                raw = retryRateLimitedChat(session, system, history, userTurn);
            } else {
                log.error("对话模型调用失败, sessionId={}, content={}", session.getId(), content, e);
                throw new BizException("AI 暂时没回上来，请稍后再试一次");
            }
        }
        log.debug("[CHAT][raw] sessionId={} raw={}", session.getId(), raw);

        // 4) 解析 {reply, profile_patch, action};解析失败也要给用户一句人话,绝不吐 JSON。
        TurnParse parsed = parseTurn(raw);
        if (!parsed.isStructured()) {
            // 模型偶发无视格式、整句纯文本回过来(profile_patch 直接丢失)。
            // 这时用一句更硬的格式要求重试一次,而不是默默把结构化信息扔掉。
            log.warn("对话模型未按 JSON 输出,重试一次, sessionId={}, raw={}", session.getId(), raw);
            try {
                String strictTurn = userTurn
                        + "\n[格式要求] 只输出一个 JSON 对象:{\"reply\":\"...\",\"intent\":\"UNKNOWN\","
                        + "\"profile_patch\":{},\"conversation_update\":{},\"next_step\":\"ANSWER\"},不要输出任何其它文字。";
                String retryRaw = llmClient.chat(AiTask.CHAT, system, history, strictTurn);
                TurnParse retry = parseTurn(retryRaw);
                if (retry.isStructured()) {
                    parsed = retry;
                    raw = retryRaw;
                }
            } catch (Exception e) {
                log.warn("对话格式重试失败,沿用首次回复, sessionId={}", session.getId(), e);
            }
        }
        ChatProfileDTO guardedPatch = profilePatchGuard.guard(
                parsed.getPatch(), lastAssistantReply, bareAcknowledgement);
        if (guardedPatch != null) {
            profile = mergeProfile(profile, guardedPatch, corrections, evidence);
        }
        if (!bareAcknowledgement && parsed.getNotes() != null && !parsed.getNotes().isEmpty()) {
            profile = mergeNotes(profile, parsed.getNotes());
        }
        // 模型漏掉的硬约束由服务端兜底：当轮就落库，不等下一轮补
        profile.setConstraints(ConstraintExtractor.apply(content, profile.getConstraints()));
        profile = finalizeProfile(profile, corrections);

        ChatIntent modelIntent = parsed.getIntent() == null ? ChatIntent.UNKNOWN : parsed.getIntent();
        ChatStrategy nextStep = parsed.getStrategy() == null ? strategy : parsed.getStrategy();
        ChatAction action = actionResolver.resolve(profile, content, modelIntent, nextStep);
        boolean readyNow = actionResolver.ready(profile);
        state = stateService.merge(state, parsed.getConversationUpdate(), modelIntent, nextStep,
                asked(parsed.getReply()), readyNow, profile, computeMissing(profile));

        // 5) 落库:用户原话、画像 context、助手回复。
        //    画像写入失败不能让用户白等一轮,但必须记日志,下一轮还能从原话重新抽取。
        insertMessage(session.getId(), "user", content);
        if ("新对话".equals(session.getTitle())) {
            sessionMapper.updateTitle(session.getId(), briefTitle(content));
        }
        try {
            sessionMapper.updateContext(session.getId(), contextService.write(profile, state));
        } catch (Exception e) {
            log.error("画像 context 写入失败, sessionId={}", session.getId(), e);
            corrections.add("画像暂时没保存成功，下一轮我再核对一次");
        }

        String reply = normalizeReply(parsed.getReply());
        reply = replySanitizer.sanitize(reply, profile, evidence, corrections,
                bareAcknowledgement, lastAssistantReply);
        if (reply.isEmpty()) {
            // 模型这次没给出可用正文:不要只说"没接住"就完事,要把用户这句话还给他,
            // 并给出可执行的下一步,避免用户不知道该重说哪部分。
            reply = corrections.isEmpty()
                    ? "这次没理解上来，你刚才说的我先记着了。能再说具体点吗——比如想去哪个城市、玩几天？"
                    : "这轮我先按能确认的部分往下看。你把最关键的一项再补一句就行，比如日期、天数或想先排哪一块。";
        }
        ChatMessage assistant = insertMessage(session.getId(), "assistant", reply);
        ChatTurnVO vo = buildTurnVO(assistant, reply, profile, corrections);
        vo.setSpots(spots);   // 景点卡片：前端据此渲染图文
        vo.setConstraints(profile.getConstraints());
        vo.setConversationState(state);
        vo.setAction(action.jsonValue());
        vo.setIntent(modelIntent.name());
        vo.setStrategy(nextStep.name());
        vo.setNextStep(nextStep.name());
        return vo;
    }

    private String retryRateLimitedChat(ChatSession session, String system,
                                        List<Map<String, String>> history, String userTurn) {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long waitMs = attempt == 1 ? 1200L : 2800L;
            log.warn("对话模型限流(429), {}ms 后第 {} 次重试, sessionId={}",
                    waitMs, attempt, session.getId());
            sleepQuietly(waitMs);
            try {
                return llmClient.chat(AiTask.CHAT, system, history, userTurn);
            } catch (Exception e) {
                last = e;
                if (!isRateLimited(e)) break;
            }
        }
        log.error("限流重试仍失败, sessionId={}", session.getId(), last);
        throw new BizException("现在用的人有点多，稍等十几秒再发一次就好了");
    }

    /**
     * 不经过大模型的一轮回复(寒暄 / 越界拒绝)。
     *
     * 关键点:这里【不调用 LLM、不产出 profile_patch】,但用户消息和助手回复仍然落库——
     * 用户应该在界面上看到自己说过的话和我们的回答,这是正常的对话记录。
     * 区别在于:这两类内容不会被当成"模型认可的对话"去影响后续判断,
     * 因为越界请求在下一轮根本不会被模型看到(下面 toHistory 会过滤掉)。
     */
    private ChatTurnVO replyWithoutModel(ChatSession session, ChatProfileDTO profile,
                                         ConversationStateDTO state, String content,
                                         ChatIntent intent, String reply, List<String> corrections) {
        profile.setConstraints(ConstraintExtractor.apply(content, profile.getConstraints()));
        profile = finalizeProfile(profile, corrections);
        state = stateService.merge(state, null, intent, ChatStrategy.ANSWER, false,
                actionResolver.ready(profile), profile, computeMissing(profile));
        insertMessage(session.getId(), "user", content);
        if ("新对话".equals(session.getTitle())) {
            sessionMapper.updateTitle(session.getId(), briefTitle(content));
        }
        // 画像可能因为前端表单覆盖而变了(用户手改画像同时说了句"好的"),这里照样落库
        try {
            sessionMapper.updateContext(session.getId(), contextService.write(profile, state));
        } catch (Exception e) {
            log.error("画像 context 写入失败, sessionId={}", session.getId(), e);
        }
        String finalReply = normalizeReply(reply);
        ChatMessage assistant = insertMessage(session.getId(), "assistant", finalReply);
        ChatTurnVO vo = buildTurnVO(assistant, finalReply, profile, corrections);
        vo.setConstraints(profile.getConstraints());
        vo.setConversationState(state);
        vo.setAction(ChatAction.CHAT.jsonValue());
        vo.setIntent(intent.name());
        vo.setStrategy(ChatStrategy.ANSWER.name());
        vo.setNextStep(ChatStrategy.ANSWER.name());
        return vo;
    }

    /** 异常链里是否包含 429 / Too Many Requests(免费模型限流的典型表现) */
    private boolean isRateLimited(Throwable e) {
        int depth = 0;
        while (e != null && depth++ < 8) {
            String msg = e.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("429") || m.contains("too many requests") || m.contains("访问量过大")
                        || m.contains("rate limit")) {
                    return true;
                }
            }
            e = e.getCause();
        }
        return false;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 去掉标点空白,供寒暄话术判断"用户说的是哪一类"(与 ChatIntentRouter 的归一化保持一致) */
    private String normalizeForSmalltalk(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}。，、！？；：”“‘’（）【】《》…—～·\\u3000]", "").toLowerCase();
    }

    /** 组装返回给前端的一轮结果(模型路径和服务端直答路径共用) */
    private ChatTurnVO buildTurnVO(ChatMessage assistant, String reply, ChatProfileDTO profile,
                                   List<String> corrections) {
        ChatTurnVO vo = new ChatTurnVO();
        vo.setId(assistant.getId());
        vo.setRole("assistant");
        vo.setContent(reply);
        vo.setCreateTime(assistant.getCreateTime());
        vo.setProfile(profile);
        vo.setMissing(computeMissing(profile));
        vo.setWarnings(new ArrayList<>(new LinkedHashSet<>(corrections)));
        return vo;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional   // 清引用+清消息+删会话三步原子
    public void deleteSession(Integer sessionId, Integer userId) {
        ownedSession(sessionId, userId);
        // 会话下挂过的行程(itinerary.session_id 外键引用本会话)必须先解除引用,
        // 否则 MySQL 拒删父行(fk_itin_session)报 500。行程是用户资产,保留不删
        itineraryMapper.clearSessionId(sessionId);
        messageMapper.deleteBySessionId(sessionId);   // 先清消息
        sessionMapper.deleteSession(sessionId);       // 再删会话
    }

    // ==================================================================================
    // 模型输入 / 输出
    // ==================================================================================

    /**
     * 从上一轮助手回复里提取"它问了什么"。只取带问号的句子,最多两句,
     * 没有问句返回 null(说明上一轮没追问,自然也不需要提醒模型别重复)。
     */
    private String extractLastAsked(List<ChatMessage> prior) {
        if (prior == null || prior.isEmpty()) return null;
        for (int i = prior.size() - 1; i >= 0; i--) {
            ChatMessage m = prior.get(i);
            if (m == null || !"assistant".equals(m.getRole()) || isBlank(m.getContent())) continue;
            List<String> questions = new ArrayList<>();
            for (String seg : m.getContent().split("[。！!\n]")) {
                if (seg.contains("？") || seg.contains("?")) {
                    String q = seg.trim();
                    if (!q.isEmpty() && q.length() <= 60) questions.add(q);
                }
                if (questions.size() >= 2) break;
            }
            return questions.isEmpty() ? null : String.join(" / ", questions);
        }
        return null;
    }

    private String extractLastAssistantReply(List<ChatMessage> prior) {
        if (prior == null || prior.isEmpty()) return null;
        for (int i = prior.size() - 1; i >= 0; i--) {
            ChatMessage m = prior.get(i);
            if (m != null && "assistant".equals(m.getRole()) && !isBlank(m.getContent())) {
                return m.getContent().trim();
            }
        }
        return null;
    }

    /**
     * 从用户原话里抠出"人数 / 天数 / 预算"三类最常被重复追问的事实。
     * 只是给模型的确定性提示,不写画像、不落库;识别不出返回空串。
     * 注意"第二天/第三天"是序数不是天数,用 (?<!第) 排除。
     */
    private String extractFacts(String content) {
        if (content == null || content.isBlank()) return "";
        List<String> facts = new ArrayList<>();
        String num = "(\\d{1,2}|[一二两三四五六七八九十]{1,3})";

        Matcher people = Pattern.compile(num + "\\s*(?:个)?\\s*(?:人|位)").matcher(content);
        if (people.find()) {
            Integer n = toNumber(people.group(1));
            if (n != null && n > 0 && n < 50) facts.add("同行 " + n + " 人");
        }

        Matcher days = Pattern.compile("(?<!第)" + num + "\\s*(?:天|日游)").matcher(content);
        if (days.find()) {
            Integer n = toNumber(days.group(1));
            if (n != null && n > 0 && n <= MAX_DAYS) facts.add("行程 " + n + " 天");
        }

        Matcher budget = Pattern.compile("(?:预算|大概|差不多|控制在)?\\s*(\\d{2,6})\\s*(?:元|块|rmb|RMB)").matcher(content);
        if (budget.find()) facts.add("预算约 " + budget.group(1) + " 元");

        Matcher lodging = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,12}?)(?:附近|一带)").matcher(content);
        if (lodging.find()) facts.add("住宿区域 " + lodging.group(1) + lodging.group().substring(lodging.group(1).length()));

        return String.join(";", facts);
    }

    /** 阿拉伯数字或中文数字(一~九十九)转 int;识别失败返回 null */
    private Integer toNumber(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException ignore) {
            // 继续按中文数字解析
        }
        String digits = "零一二三四五六七八九";
        String t = s.trim();
        if (t.equals("两")) return 2;
        if (t.equals("十")) return 10;
        if (t.length() == 1) {
            int i = digits.indexOf(t);
            return i > 0 ? i : null;
        }
        if (t.startsWith("十") && t.length() == 2) {
            int i = digits.indexOf(t.charAt(1));
            return i > 0 ? 10 + i : null;
        }
        if (t.endsWith("十") && t.length() == 2) {
            int i = digits.indexOf(t.charAt(0));
            return i > 0 ? i * 10 : null;
        }
        if (t.length() == 3 && t.charAt(1) == '十') {
            int a = digits.indexOf(t.charAt(0));
            int b = digits.indexOf(t.charAt(2));
            return (a > 0 && b > 0) ? a * 10 + b : null;
        }
        return null;
    }

    /**
     * 解析模型输出。正常是 {"reply":..., "profile_patch":..., "action":...};
     * 兼容模型偶尔加 ```json 围栏、前后客套话、把 JSON 拆成两段、或干脆只回一段纯文本。
     * 不能简单取"第一个 { 到最后一个 }"——中间夹一句解释就会让整段 JSON 失效,
     * 结果 reply 能抢救、profile_patch 却悄悄丢掉。
     */
    private TurnParse parseTurn(String raw) {
        TurnParse turn = new TurnParse();
        if (raw == null || raw.isBlank()) return turn;

        ChatLLMOut picked = null;
        for (String json : extractJsonObjects(raw)) {
            try {
                ChatLLMOut candidate = objectMapper.readValue(json, ChatLLMOut.class);
                // P1 协议：只取第一个有效对象，不再把多个 JSON 的字段拼起来
                if (!isBlank(candidate.getReply()) || candidate.getProfilePatch() != null) {
                    picked = candidate;
                    break;
                }
                if (picked == null) picked = candidate;
            } catch (Exception e) {
                log.debug("候选 JSON 解析失败, raw={}", json, e);
            }
        }
        if (picked != null) {
            turn.setReply(picked.getReply());
            turn.setPatch(picked.getProfilePatch());
            turn.setNotes(picked.getNotes());
            turn.setAction(picked.getAction());
            turn.setIntent(ChatIntent.parse(picked.getIntent()));
            turn.setStrategy(ChatStrategy.parse(picked.getNextStep()));
            turn.setConversationUpdate(picked.getConversationUpdate());
            turn.setNextStep(picked.getNextStep());
            turn.setStructured(true);
            return turn;
        }

        String salvaged = salvageReply(raw);
        turn.setReply(salvaged != null ? salvaged : sanitizePlainReply(raw));
        return turn;
    }

    /**
     * 从整段输出里逐个扫描"括号配平"的 JSON 对象(正确处理字符串里的花括号和转义),
     * 最多取 5 个,避免模型复述示例时无限扫。
     */
    private List<String> extractJsonObjects(String raw) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int start = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(raw.substring(start, i + 1));
                    start = -1;
                    if (out.size() >= 5) break;
                }
            }
        }
        return out;
    }

    /** 纯文本回复清洗:去 ``` 围栏;若看起来像坏 JSON,尽量只留下 reply 字段 */
    private String sanitizePlainReply(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        if (s.startsWith("{")) {
            String r = salvageReply(s);
            s = r != null ? r : "";
        }
        return s.trim();
    }

    /** 从模型原始输出里抠出 "reply":"..." 的值(处理转义);抢救不出返回 null */
    private String salvageReply(String raw) {
        if (raw == null) return null;
        Matcher m = REPLY_JSON.matcher(raw);
        if (m.find()) {
            try {
                return objectMapper.readValue("\"" + m.group(1) + "\"", String.class);
            } catch (Exception ignored) {
                return m.group(1);
            }
        }
        return null;
    }

    /** 回复最终清洗:去空白/围栏,限制长度;不在这里改写成模板句 */
    private String normalizeReply(String reply) {
        String s = sanitizePlainReply(reply == null ? "" : reply);
        s = s.replace("\r", "").trim();
        if (s.length() > 1500) s = s.substring(0, 1500).trim() + "…";
        return s;
    }

    // ==================================================================================
    // 画像合并 / 校验 / 缺失项
    // ==================================================================================

    /**
     * 前端表单是画像的"视图"。采用整份快照覆盖:用户在画像卡里手动清掉的值
     * 也能同步到服务端,而不是只增不减。传 null 表示前端没带画像,保持服务端原值。
     */
    private ChatProfileDTO overlayClientProfile(ChatProfileDTO base, ChatProfileDTO client, List<String> corrections) {
        if (client == null) return base;
        ChatProfileDTO p = new ChatProfileDTO();
        p.setOrigin(normalizeText(client.getOrigin(), 32, "出发地"));
        p.setCity(normalizeText(client.getCity(), 32, "目的地"));
        p.setStartDate(trimToNull(client.getStartDate()));
        p.setEndDate(trimToNull(client.getEndDate()));
        p.setDays(client.getDays());
        p.setBudget(client.getBudget());
        p.setPreferences(copyList(client.getPreferences()));
        p.setGroupSize(client.getGroupSize());
        p.setCompanionType(normalizeText(client.getCompanionType(), 20, "同行关系"));
        p.setPace(normalizeText(client.getPace(), 20, "节奏"));
        p.setTransport(normalizeText(client.getTransport(), 30, "市内交通"));
        p.setLodging(normalizeText(client.getLodging(), 100, "住宿偏好"));
        p.setLodgingMode(normalizeLodgingMode(client.getLodgingMode()));
        p.setMustVisit(copyList(client.getMustVisit()));
        p.setAvoid(copyList(client.getAvoid()));
        p.setArrival(clientAnchor(client.getArrival(), "到达", corrections));
        p.setDeparture(clientAnchor(client.getDeparture(), "返程", corrections));
        return p;
    }

    /** 前端锚点转服务端结构;空锚点(只有默认交通方式)视为未填,不落库 */
    private AnchorStateDTO clientAnchor(AnchorStateDTO c, String label, List<String> corrections) {
        if (c == null) return null;
        String date = c.getDate();
        String time = c.getTime();
        if (isBlank(date) && isBlank(time) && !isBlank(c.getDatetime())) {
            String dt = c.getDatetime().trim().replace(' ', 'T');
            String[] parts = dt.split("T");
            if (parts.length >= 1) date = parts[0];
            if (parts.length >= 2 && parts[1].length() >= 5) time = parts[1].substring(0, 5);
        }
        AnchorStateDTO a = new AnchorStateDTO();
        if (!isBlank(date)) {
            String v = normalizeDate(date, label + "日期", corrections);
            if (v != null && !v.isEmpty()) a.setDate(v);
        }
        if (!isBlank(time)) {
            String v = normalizeTime(time, label, corrections);
            if (v != null && !v.isEmpty()) a.setTime(v);
        }
        if (!isBlank(c.getLocation())) {
            a.setLocation(normalizeText(c.getLocation(), 50, label + "地点"));
        }
        if (!isBlank(c.getType())) {
            a.setType(normalizeTransitType(c.getType()));
        }
        if (c.getTimePeriod() != null) {
            a.setTimePeriod(c.getTimePeriod());
        }
        // 前端空锚点默认带 type=train,没有日期/时刻/地点时不能当成"用户已经选了高铁"
        if (!anchorHasFact(a)) return null;
        a.setSource("form");
        a.setConfidence(1.0);
        recomputeAnchorDatetime(a);
        return a;
    }

    /**
     * 把模型给的 profile_patch 合并进画像。补丁只含"本轮新增/修改"的字段:
     * null=没提到,空字符串/空数组=用户明确清空。
     */
    private ChatProfileDTO mergeProfile(ChatProfileDTO p, ChatProfileDTO patch,
                                        List<String> corrections, DateEvidence evidence) {
        String oldCity = nz(p.getCity());

        if (patch.getOrigin() != null) {
            p.setOrigin(normalizeText(patch.getOrigin(), 32, "出发地"));
        }
        if (patch.getCity() != null) {
            p.setCity(normalizeText(patch.getCity(), 32, "目的地"));
        }

        // 换了目的地,旧城市的站点和必去点不再适用;只换出发地不影响到达/返程锚点
        // (到达时间、成都东站这些事实跟用户从重庆还是武汉出发无关)。
        // 补丁里同时给了新锚点时会在下面重新写回,所以这里先清不会丢本轮信息。
        boolean cityClearedDates = false;
        boolean cityChanged = !nz(p.getCity()).equals(oldCity);
        if (cityChanged) {
            p.setArrival(null);
            p.setDeparture(null);
            p.setMustVisit(null);
            // 换城市默认不沿用旧城市的起止日期；用户明确说"日期不变"时才保留。
            // 补丁里同时给了新日期时会在下面重新写回。
            if (evidence == null || !evidence.explicitlyKeepsDates()) {
                boolean hadDates = !isBlank(p.getStartDate()) || !isBlank(p.getEndDate());
                p.setStartDate(null);
                p.setEndDate(null);
                cityClearedDates = hadDates;
                if (hadDates) {
                    corrections.add("换目的地后，日期需要重新确认");
                }
            }
        }

        boolean daysPatched = false;
        if (patch.getDays() != null) {
            int d = patch.getDays();
            if (d == 0) {
                p.setDays(null);
            } else if (d >= 1 && d <= MAX_DAYS) {
                p.setDays(d);
                daysPatched = true;
            } else {
                corrections.add("天数需要在 1~" + MAX_DAYS + " 天之间");
            }
        }
        if (patch.getBudget() != null) {
            BigDecimal b = patch.getBudget();
            if (b.signum() < 0) {
                corrections.add("预算不能是负数");
            } else if (b.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
                corrections.add("预算数字太大了，先按你原来的记");
            } else {
                p.setBudget(b.signum() == 0 ? null : b);
            }
        }
        if (patch.getGroupSize() != null) {
            int g = patch.getGroupSize();
            if (g == 0) p.setGroupSize(null);
            else if (g > 0 && g <= 50) p.setGroupSize(g);
            else corrections.add("同行人数看起来不太对");
        }
        if (patch.getCompanionType() != null) p.setCompanionType(normalizeText(patch.getCompanionType(), 20, "同行关系"));
        if (patch.getPace() != null) p.setPace(normalizeText(patch.getPace(), 20, "节奏"));
        if (patch.getTransport() != null) p.setTransport(normalizeText(patch.getTransport(), 30, "市内交通"));
        if (patch.getLodging() != null) p.setLodging(normalizeText(patch.getLodging(), 100, "住宿偏好"));
        if (patch.getLodgingMode() != null) p.setLodgingMode(normalizeLodgingMode(patch.getLodgingMode()));
        if (patch.getPreferences() != null) p.setPreferences(normalizeList(patch.getPreferences(), 10, 20));
        if (patch.getMustVisit() != null) p.setMustVisit(normalizeList(patch.getMustVisit(), 10, 30));
        if (patch.getAvoid() != null) p.setAvoid(normalizeList(patch.getAvoid(), 10, 30));
        if (patch.getConstraints() != null) {
            p.setConstraints(ConstraintExtractor.mergePatch(p.getConstraints(), patch.getConstraints()));
        }

        boolean startPatched = false;
        boolean endPatched = false;
        if (patch.getStartDate() != null) {
            String v = normalizeDate(patch.getStartDate(), "出发日期", corrections);
            if (v != null) {
                if (acceptDate(p.getStartDate(), v, evidence)) {
                    p.setStartDate(v.isEmpty() ? null : v);
                    startPatched = true;
                } else if (!cityClearedDates) {
                    corrections.add("出发日期没找到依据，先没记");
                }
            }
        }
        if (patch.getEndDate() != null) {
            String v = normalizeDate(patch.getEndDate(), "结束日期", corrections);
            if (v != null) {
                if (acceptDate(p.getEndDate(), v, evidence)) {
                    p.setEndDate(v.isEmpty() ? null : v);
                    endPatched = true;
                } else if (!cityClearedDates) {
                    corrections.add("结束日期没找到依据，先没记");
                }
            }
        }

        if (patch.getArrival() != null) mergeAnchor(p, true, patch.getArrival(), corrections, evidence);
        if (patch.getDeparture() != null) mergeAnchor(p, false, patch.getDeparture(), corrections, evidence);

        // 日期与天数联动:明确说了天数 → 推结束日;明确说了结束日 → 反推天数;
        // 只改出发日但有天数 → 按天数重推结束日。
        LocalDate start = parseIsoDate(p.getStartDate());
        LocalDate end = parseIsoDate(p.getEndDate());
        if (start != null && end != null && end.isBefore(start)) {
            corrections.add("结束日期早于出发日期，先没记结束日期");
            p.setEndDate(null);
            end = null;
        }
        if (daysPatched && start != null && !endPatched && p.getDays() != null) {
            p.setEndDate(start.plusDays(p.getDays() - 1L).toString());
        } else if (startPatched && start != null && p.getDays() != null && !endPatched) {
            p.setEndDate(start.plusDays(p.getDays() - 1L).toString());
        } else if (endPatched && start != null && p.getEndDate() != null) {
            LocalDate e = parseIsoDate(p.getEndDate());
            if (e != null) {
                long span = ChronoUnit.DAYS.between(start, e) + 1;
                if (span >= 1 && span <= MAX_DAYS) p.setDays((int) span);
                else {
                    corrections.add("行程跨度需要在 1~" + MAX_DAYS + " 天之间");
                    p.setEndDate(null);
                }
            }
        } else if (start != null && p.getEndDate() != null && !daysPatched) {
            LocalDate e = parseIsoDate(p.getEndDate());
            if (e != null) {
                long span = ChronoUnit.DAYS.between(start, e) + 1;
                if (span >= 1 && span <= MAX_DAYS) p.setDays((int) span);
            }
        }
        return p;
    }

    /**
     * 锚点补丁合并(逐字段):日期、时刻、站点、交通方式各自独立。
     * 这是"半成品锚点"能保住的关键——用户只说下午5点坐高铁,就只写 time/type,
     * 日期留空等下一轮,而不是整条丢弃。
     */
    private void mergeAnchor(ChatProfileDTO p, boolean arrival, AnchorStateDTO patch,
                             List<String> corrections, DateEvidence evidence) {
        String label = arrival ? "到达" : "返程";
        AnchorStateDTO a = arrival ? p.getArrival() : p.getDeparture();
        if (a == null) a = new AnchorStateDTO();

        // 兼容模型只给 datetime 的写法
        if (patch.getDatetime() != null && isBlank(patch.getDate()) && isBlank(patch.getTime())) {
            String dt = patch.getDatetime().trim();
            if (dt.isEmpty()) {
                a.setDate(null);
                a.setTime(null);
            } else {
                String[] parts = dt.replace(' ', 'T').split("T");
                if (parts.length >= 1) patch.setDate(parts[0]);
                if (parts.length >= 2 && parts[1].length() >= 5) patch.setTime(parts[1].substring(0, 5));
            }
        }

        boolean touched = false;
        if (patch.getDate() != null) {
            String v = normalizeDate(patch.getDate(), label + "日期", corrections);
            if (v != null) {
                if (acceptDate(a.getDate(), v, evidence)) {
                    a.setDate(v.isEmpty() ? null : v);
                    touched = true;
                } else {
                    corrections.add(label + "日期没找到依据，先没记");
                }
            }
        }
        if (patch.getTime() != null) {
            String v = normalizeTime(patch.getTime(), label, corrections);
            if (v != null) {
                if (v.isEmpty()) {
                    a.setTime(null);
                    a.setTimePeriod(null);
                    touched = true;
                } else if (a.getTime() != null && a.getTime().equals(v)) {
                    // 模型重复确认已有时刻，不需要新的证据
                    touched = true;
                } else if (evidence != null && !evidence.hasTime(v)) {
                    // 用户这句话里没有具体时刻：不要用模型算出来的 17:00 覆盖已有值
                    TimePeriod period = evidence.timePeriod() != null
                            ? evidence.timePeriod() : patch.getTimePeriod();
                    if (period != null) a.setTimePeriod(period);
                    touched = true;
                    if (a.getTime() != null) {
                        corrections.add(label + "时间这句话里没提，先保留之前记的 " + a.getTime());
                    } else {
                        corrections.add(label + "时间先按「" + periodText(period) + "」记，具体几点可以再说");
                    }
                } else {
                    a.setTime(v);
                    a.setTimePeriod(patch.getTimePeriod() != null ? patch.getTimePeriod() : null);
                    touched = true;
                }
            }
        } else if (patch.getTimePeriod() != null) {
            TimePeriod period = evidence != null && evidence.timePeriod() != null
                    ? evidence.timePeriod() : patch.getTimePeriod();
            a.setTimePeriod(period);
            touched = true;
        }
        if (patch.getLocation() != null) {
            a.setLocation(normalizeText(patch.getLocation(), 50, label + "地点"));
            touched = true;
        }
        if (patch.getType() != null) {
            String t = normalizeTransitType(patch.getType());
            a.setType(isBlank(t) ? null : t);
            touched = true;
        }
        if (patch.getConfidence() != null) {
            a.setConfidence(Math.max(0, Math.min(1, patch.getConfidence())));
        }
        if (touched) a.setSource("chat");
        recomputeAnchorDatetime(a);
        if (!anchorHasAny(a)) a = null;
        if (arrival) p.setArrival(a); else p.setDeparture(a);
    }

    /**
     * 日期能否写进画像：
     * - 空串=用户明确清空，允许；
     * - 与已有值相同=重复确认，允许；
     * - 否则必须能从本轮原话推出同一个日期。
     */
    private boolean acceptDate(String existing, String normalized, DateEvidence evidence) {
        if (normalized == null) return false;
        if (normalized.isEmpty()) return true;
        if (existing != null && existing.equals(normalized)) return true;
        return evidence != null && evidence.hasDate(normalized);
    }

    private String periodText(TimePeriod p) {
        if (p == null) return "某个时段";
        return switch (p) {
            case MORNING -> "上午";
            case AFTERNOON -> "下午";
            case EVENING -> "傍晚";
            case NIGHT -> "晚上";
            default -> "某个时段";
        };
    }

    /**
     * 最后一次整体对齐:校验日期可用性、锚点日期/时刻、用锚点补出发/结束日、
     * 用起止日反推天数。所有修正都只改单字段,不会把用户的整句信息清空。
     */
    /**
     * 合并模型给的对话要点：去空、去重、限长，最多留最近 20 条。
     * 早的要点被挤掉是刻意的——生成时只关心当前这次出行还成立的要求。
     */
    private ChatProfileDTO mergeNotes(ChatProfileDTO p, List<String> incoming) {
        List<String> notes = p.getNotes() == null ? new ArrayList<>() : new ArrayList<>(p.getNotes());
        for (String raw : incoming) {
            if (raw == null) continue;
            String n = raw.trim().replaceAll("\\s+", " ");
            if (n.isEmpty()) continue;
            if (n.length() > 80) n = n.substring(0, 80);
            if (!notes.contains(n)) notes.add(n);
        }
        while (notes.size() > 20) notes.remove(0);
        p.setNotes(notes);
        return p;
    }

    private ChatProfileDTO finalizeProfile(ChatProfileDTO p, List<String> corrections) {
        validateAnchor(p.getArrival());
        validateAnchor(p.getDeparture());
        if (!anchorHasAny(p.getArrival())) p.setArrival(null);
        if (!anchorHasAny(p.getDeparture())) p.setDeparture(null);

        LocalDate start = parseIsoDate(p.getStartDate());
        LocalDate end = parseIsoDate(p.getEndDate());
        if (!isBlank(p.getStartDate()) && start == null) p.setStartDate(null);
        if (!isBlank(p.getEndDate()) && end == null) p.setEndDate(null);

        // 用户只说了到达/返程的日期,也能补出出发/结束日
        if (start == null && p.getArrival() != null) {
            start = parseIsoDate(p.getArrival().getDate());
            if (start != null) p.setStartDate(start.toString());
        }
        if (end == null && p.getDeparture() != null) {
            end = parseIsoDate(p.getDeparture().getDate());
            if (end != null) p.setEndDate(end.toString());
        }

        if (start != null && end != null) {
            if (end.isBefore(start)) {
                corrections.add("结束日期早于出发日期，先没记结束日期");
                p.setEndDate(null);
                end = null;
            } else {
                long span = ChronoUnit.DAYS.between(start, end) + 1;
                if (span > MAX_DAYS) {
                    corrections.add("行程跨度超过 " + MAX_DAYS + " 天，先没记结束日期");
                    p.setEndDate(null);
                    p.setDays(null);
                    end = null;
                } else {
                    p.setDays((int) span);
                }
            }
        }
        // 有出发日 + 天数、没有结束日 → 补结束日
        if (start != null && p.getDays() != null && p.getEndDate() == null
                && p.getDays() >= 1 && p.getDays() <= MAX_DAYS) {
            p.setEndDate(start.plusDays(p.getDays() - 1L).toString());
        }
        if (p.getDays() != null && (p.getDays() < 1 || p.getDays() > MAX_DAYS)) {
            corrections.add("天数需要在 1~" + MAX_DAYS + " 天之间");
            p.setDays(null);
        }
        ensureDateConsistency(p, corrections);
        return p;
    }

    /**
     * 跨字段日期一致性：startDate ≤ arrival.date ≤ endDate，
     * 返程日不能早于出发日。冲突时以用户最近说出的锚点为准，并在回复里说明。
     */
    private void ensureDateConsistency(ChatProfileDTO p, List<String> corrections) {
        com.xingji.chat.DateRules.applyConsistency(p, corrections);
    }

    /** 锚点落库前的纯清洗:空字段清掉、交通方式归一、date+time 重算 datetime */
    private void validateAnchor(AnchorStateDTO a) {
        if (a == null) return;
        if (a.getDate() != null && a.getDate().isBlank()) a.setDate(null);
        if (a.getTime() != null && a.getTime().isBlank()) a.setTime(null);
        if (a.getType() != null) {
            String t = normalizeTransitType(a.getType());
            a.setType(isBlank(t) ? null : t);
        }
        recomputeAnchorDatetime(a);
    }

    /** 服务端计算的缺失项:只列真正影响排行程的关键格,可选偏好不在这里穷举 */
    private List<String> computeMissing(ChatProfileDTO p) {
        List<String> miss = new ArrayList<>();
        if (p == null) p = new ChatProfileDTO();
        if (isBlank(p.getCity())) miss.add("city");
        if (isBlank(p.getOrigin())) miss.add("origin");
        if (p.getDays() == null) miss.add("days");
        if (isBlank(p.getStartDate())) miss.add("startDate");
        if (p.getArrival() == null || isBlank(p.getArrival().getDate())) miss.add("arrival.date");
        if (p.getArrival() == null || isBlank(p.getArrival().getTime())) miss.add("arrival.time");
        if (p.getDeparture() == null || isBlank(p.getDeparture().getDate())) miss.add("departure.date");
        if (p.getDeparture() == null || isBlank(p.getDeparture().getTime())) miss.add("departure.time");
        // 住宿只在"目的地 + 天数/日期"都已经清楚之后才纳入缺失项。
        // 理由:用户刚说"想去成都"时问"住哪一带"毫无意义——他连玩几天、去哪都不知道,
        // 更别说住哪。等行程骨架确定后再问,才是有效的一问。
        if (isTripShapeReady(p)) {
            if (isBlank(p.getLodgingMode())) miss.add("lodgingMode");
            if (isBlank(p.getLodging())) miss.add("lodging");
        }
        return new ArrayList<>(new LinkedHashSet<>(miss));
    }

    /** 行程骨架是否已明确(目的地 + 天数或日期区间),住宿相关问题只在这之后问 */
    private boolean isTripShapeReady(ChatProfileDTO p) {
        if (p == null) return false;
        boolean hasRange = !isBlank(p.getStartDate()) && !isBlank(p.getEndDate());
        return !isBlank(p.getCity()) && (p.getDays() != null || hasRange);
    }

    // ==================================================================================
    // 字段规范化
    // ==================================================================================

    /**
     * 宽松日期解析:支持完整日期、月日、裸几号、今天/明天/后天。
     * 返回 ""=用户明确清空;null=格式不合法或越界(不改旧值,只追加修正提示);
     * 否则返回 ISO 'YYYY-MM-DD'。
     */
    private String normalizeDate(String raw, String label, List<String> corrections) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        if (s.contains("还没定") || s.contains("没定") || s.contains("不确定") || s.contains("未定")) return "";

        LocalDate today = LocalDate.now();
        LocalDate d = null;
        if (s.contains("大后天")) d = today.plusDays(3);
        else if (s.contains("后天")) d = today.plusDays(2);
        else if (s.contains("明天")) d = today.plusDays(1);
        else if (s.contains("今天") || s.contains("今晚")) d = today;

        if (d == null) {
            Matcher m = DATE_FULL.matcher(s);
            if (m.find()) {
                try {
                    d = LocalDate.of(Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                } catch (Exception ignored) {
                    // 继续尝试其它格式
                }
            }
        }
        if (d == null) {
            Matcher m = DATE_MONTH_DAY.matcher(s);
            if (m.find()) {
                try {
                    d = LocalDate.of(today.getYear(),
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    if (d.isBefore(today)) d = d.plusYears(1);
                } catch (Exception ignored) {
                    d = null;
                }
            }
        }
        if (d == null) {
            Matcher m = DATE_DAY.matcher(s);
            if (m.find()) {
                try {
                    d = today.withDayOfMonth(Integer.parseInt(m.group(1)));
                    if (d.isBefore(today)) d = d.plusMonths(1);
                } catch (Exception ignored) {
                    d = null;
                }
            }
        }
        if (d == null) {
            corrections.add(label + "没对上，这部分先没记");
            return null;
        }
        if (d.isBefore(today)) {
            corrections.add(label + "已经过去了，先没记这个日期");
            return null;
        }
        if (d.isAfter(today.plusYears(2))) {
            corrections.add(label + "太远了，先没记这个日期");
            return null;
        }
        return d.toString();
    }

    /** 宽松时刻解析:支持 17:00 / 下午5点 / 晚上7点半;返回 ""=清空,null=没看懂 */
    private String normalizeTime(String raw, String label, List<String> corrections) {
        if (raw == null) return null;
        String s = raw.trim().replace("：", ":");
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";

        Matcher hm = TIME_HM.matcher(s);
        if (hm.find()) {
            try {
                int h = Integer.parseInt(hm.group(1));
                int m = Integer.parseInt(hm.group(2));
                if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                    return String.format("%02d:%02d", h, m);
                }
            } catch (Exception ignored) {
                // 落到中文时刻解析
            }
        }

        Matcher cn = TIME_CN.matcher(s);
        if (cn.find()) {
            try {
                String period = cn.group(1);
                int h = Integer.parseInt(cn.group(2));
                String minuteText = cn.group(3);
                int m = 0;
                if ("半".equals(minuteText)) {
                    m = 30;
                } else if (cn.group(4) != null) {
                    m = Integer.parseInt(cn.group(4));
                }
                if (period != null) {
                    if (period.contains("下午") || period.contains("傍晚") || period.contains("晚上")) {
                        if (h < 12) h += 12;
                    } else if (period.contains("中午")) {
                        if (h < 11) h += 12;
                    } else if (period.contains("凌晨") && h == 12) {
                        h = 0;
                    }
                }
                if (h == 24 && m == 0) h = 0;
                if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                    return String.format("%02d:%02d", h, m);
                }
            } catch (Exception ignored) {
                // 落到失败分支
            }
        }
        corrections.add(label + "时间没看懂，先没记这个时间");
        return null;
    }

    /**
     * 住宿方式归一:只允许"住一处"/"换着住"两个值。
     * 模型常写成"一直住同一家""不换酒店""分两段住"这类口语,这里统一收敛;
     * 认不出就返回空串(视为未说明,按"住一处"默认处理),绝不把没听懂的话写进画像。
     */
    private String normalizeLodgingMode(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return "";
        if (v.contains("换") || v.contains("分") || v.contains("两段") || v.contains("不同")) return "换着住";
        if (v.contains("一处") || v.contains("一家") || v.contains("不换")
                || v.contains("一直") || v.contains("同一个") || v.contains("固定")) return "住一处";
        return "";
    }

    /** 交通方式枚举归一;空串=清空,null=未提供 */
    private String normalizeTransitType(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        if (v.isEmpty() || "null".equals(v)) return "";
        if (v.contains("train") || v.contains("高铁") || v.contains("动车") || v.contains("火车")) return "train";
        if (v.contains("flight") || v.contains("飞机") || v.contains("航班")) return "flight";
        if (v.contains("bus") || v.contains("大巴") || v.contains("客运")) return "bus";
        if (v.contains("car") || v.contains("自驾") || v.contains("开车")) return "car";
        return "other";
    }

    /** 文本清洗:空/null/未知类词视为清空;城市去掉"市"后缀;超长截断 */
    private String normalizeText(String raw, int max, String label) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        if (s.equals("未知") || s.equals("未定") || s.equals("待定") || s.equals("还没定")) return "";
        if ("目的地".equals(label) || "出发地".equals(label)) {
            s = s.replaceAll("市$", "");
        }
        if (s.length() > max) {
            log.warn("画像字段{}超长,已截断: {}", label, s);
            s = s.substring(0, max);
        }
        return s;
    }

    /** 列表清洗:去空、去重、限条数/长度;null=未提供,空数组=清空 */
    private List<String> normalizeList(List<String> raw, int maxItems, int maxLen) {
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null) continue;
            String v = item.trim();
            if (v.isEmpty() || "null".equalsIgnoreCase(v)) continue;
            if (v.length() > maxLen) v = v.substring(0, maxLen);
            if (!out.contains(v)) out.add(v);
            if (out.size() >= maxItems) break;
        }
        return out;
    }

    private List<String> copyList(List<String> raw) {
        return raw == null ? null : new ArrayList<>(raw);
    }

    private void recomputeAnchorDatetime(AnchorStateDTO a) {
        if (a == null) return;
        if (!isBlank(a.getDate()) && !isBlank(a.getTime())) {
            a.setDatetime(a.getDate() + "T" + a.getTime());
        } else {
            a.setDatetime(null);
        }
    }

    private boolean anchorHasFact(AnchorStateDTO a) {
        return a != null && (!isBlank(a.getDate()) || !isBlank(a.getTime()) || !isBlank(a.getLocation()));
    }

    private boolean anchorHasAny(AnchorStateDTO a) {
        return anchorHasFact(a) || (a != null && !isBlank(a.getType()));
    }

    private LocalDate parseIsoDate(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================================
    // 持久化 / 通用
    // ==================================================================================

    /** 本轮回复里是否提了问题（用于连续追问计数，P1 的策略层会读取） */
    private boolean asked(String reply) {
        return reply != null && (reply.contains("？") || reply.contains("?"));
    }

    private ChatMessage insertMessage(Integer sessionId, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        messageMapper.insert(m);
        m.setCreateTime(LocalDateTime.now());   // 前端展示用;DB 里由默认值落库
        return m;
    }

    /**
     * 历史只带最近 N 条,且只保留 user/assistant 文本。
     *
     * 另有一道重要过滤:被服务端拦截的越界请求(讲笑话、写代码…)
     * 连同它的拒绝回复一起从历史里剔除,不让模型看见。
     * 原因见 send() 里的说明——模型看到历史里有"越界问答",就会认为这类请求是被允许的,
     * 规则会一轮轮被稀释。剔除之后,规则每轮都是干净的。
     */
    private List<Map<String, String>> toHistory(List<ChatMessage> prior) {
        List<Map<String, String>> history = new ArrayList<>();
        if (prior == null || prior.isEmpty()) return history;
        int from = Math.max(0, prior.size() - HISTORY_LIMIT);
        for (int i = from; i < prior.size(); i++) {
            ChatMessage m = prior.get(i);
            if (m == null || isBlank(m.getRole()) || isBlank(m.getContent())) continue;
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) continue;
            // 越界请求不进历史(它的重复提问也无意义,一并剔除)
            if ("user".equals(m.getRole()) && intentRouter.route(m.getContent()) == ChatIntentRouter.Intent.OFF_TOPIC) {
                // 跳过这条 user 以及紧随其后的 assistant 回复
                if (i + 1 < prior.size()) {
                    ChatMessage next = prior.get(i + 1);
                    if (next != null && "assistant".equals(next.getRole())) {
                        i++;
                    }
                }
                continue;
            }
            Map<String, String> turn = new HashMap<>();
            turn.put("role", m.getRole());
            turn.put("content", m.getContent());
            history.add(turn);
        }
        return history;
    }

    /** 校验会话属于当前用户,返回会话;不存在→404,不归你→403 */
    private ChatSession ownedSession(Integer sessionId, Integer userId) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null) {
            throw new BizException(404, "会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new BizException(403, "无权访问该会话");
        }
        return session;
    }

    /** 用首句内容生成一个短标题(截断到 20 字) */
    private String briefTitle(String content) {
        String t = content.trim();
        return t.length() <= 20 ? t : t.substring(0, 20);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean hasActiveTravelContext(ChatProfileDTO profile) {
        return profile != null && !isBlank(profile.getCity()) && !actionResolver.ready(profile);
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /** 模型输出的对话契约 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatLLMOut {
        private String reply;

        @JsonProperty("profile_patch")
        private ChatProfileDTO profilePatch;

        /** 对话要点：结构化字段装不下的约束/偏好，见 ChatProfileDTO.notes */
        private List<String> notes;

        /** 旧协议字段，保留兼容 */
        private String action;

        private String intent;

        @JsonProperty("conversation_update")
        private ConversationUpdateDTO conversationUpdate;

        @JsonProperty("next_step")
        private String nextStep;
    }

    /** 解析后的一轮结果 */
    @Data
    private static class TurnParse {
        private String reply;
        private ChatProfileDTO patch;
        private List<String> notes;
        private String action;
        private ChatIntent intent = ChatIntent.UNKNOWN;
        private ChatStrategy strategy;
        private ConversationUpdateDTO conversationUpdate;
        private String nextStep;
        /** 模型这次到底有没有按 JSON 契约回答;false=纯文本兜底,结构化信息可能已经丢了 */
        private boolean structured;
    }
}
