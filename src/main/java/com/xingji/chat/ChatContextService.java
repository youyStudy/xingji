package com.xingji.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.ChatContextDTO;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ConstraintType;
import com.xingji.dto.ConversationStateDTO;
import com.xingji.dto.TravelConstraintDTO;
import com.xingji.pojo.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * chat_session.context 的唯一读写入口。
 *
 * v2 结构：{version, profile, conversationState}。
 * 旧数据是裸 ChatProfileDTO——读取时就地迁移，不虚构旧数据里没有的日期或约束。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextService {

    private final ObjectMapper objectMapper;

    public ChatContextDTO read(ChatSession session) {
        if (session == null || session.getContext() == null || session.getContext().isBlank()) {
            return new ChatContextDTO();
        }
        String json = session.getContext();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root != null && root.has("version")) {
                ChatContextDTO ctx = objectMapper.treeToValue(root, ChatContextDTO.class);
                return normalize(ctx);
            }
            ChatProfileDTO legacy = objectMapper.treeToValue(root, ChatProfileDTO.class);
            return migrate(session.getId(), legacy);
        } catch (Exception e) {
            log.warn("会话上下文解析失败，按空上下文处理, sessionId={}", session.getId(), e);
            return new ChatContextDTO();
        }
    }

    public ChatProfileDTO readProfile(ChatSession session) {
        return read(session).getProfile();
    }

    public String write(ChatProfileDTO profile, ConversationStateDTO state) {
        ChatContextDTO ctx = new ChatContextDTO();
        ctx.setProfile(profile == null ? new ChatProfileDTO() : profile);
        ctx.setConversationState(state == null ? new ConversationStateDTO() : state);
        return write(ctx);
    }

    public String write(ChatContextDTO ctx) {
        try {
            ChatContextDTO safe = normalize(ctx);
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            log.error("会话上下文序列化失败", e);
            return "{\"version\":" + ChatContextDTO.CURRENT_VERSION + "}";
        }
    }

    private ChatContextDTO normalize(ChatContextDTO ctx) {
        if (ctx == null) ctx = new ChatContextDTO();
        if (ctx.getProfile() == null) ctx.setProfile(new ChatProfileDTO());
        if (ctx.getConversationState() == null) ctx.setConversationState(new ConversationStateDTO());
        ctx.setVersion(ChatContextDTO.CURRENT_VERSION);
        return ctx;
    }

    /**
     * 旧版裸画像迁移：
     * - avoid 转为 EXCLUSION 约束（保留原字符串，不改变语义）；
     * - notes 原样保留；
     * - 不补充任何旧数据里没有的日期/约束；
     * - 对话状态用默认值。
     */
    private ChatContextDTO migrate(Integer sessionId, ChatProfileDTO legacy) {
        ChatProfileDTO profile = legacy == null ? new ChatProfileDTO() : legacy;
        List<TravelConstraintDTO> constraints = profile.getConstraints();
        if (constraints == null) constraints = new ArrayList<>();
        if (constraints.isEmpty() && profile.getAvoid() != null) {
            for (String a : profile.getAvoid()) {
                if (a == null || a.isBlank()) continue;
                TravelConstraintDTO c = new TravelConstraintDTO();
                c.setContent(a.trim());
                c.setType(ConstraintType.EXCLUSION);
                c.setScope("general");
                c.setActive(true);
                c.setSourceText(a.trim());
                constraints.add(c);
            }
        }
        profile.setConstraints(constraints);

        ChatContextDTO ctx = new ChatContextDTO();
        ctx.setProfile(profile);
        ctx.setConversationState(new ConversationStateDTO());
        log.info("旧版会话上下文已迁移到 v{}, sessionId={}", ChatContextDTO.CURRENT_VERSION, sessionId);
        return ctx;
    }
}