package com.xingji.chat;

import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.dto.ConversationStateDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 轻量策略：决定这轮"先回答 / 给建议 / 问一个 / 继续聊 / 确认 / 收尾"。
 * 不堆关键词规则，只综合用户是否在提问、是否在犹豫、连续追问次数和画像是否齐全。
 */
@Component
public class ChatStrategyService {

    private static final Pattern ASK = Pattern.compile("？|\\?|吗|怎么|如何|多少|哪些|有什么|推荐|能不能|值不值|好不好");
    private static final Pattern VAGUE = Pattern.compile(
            "随便|你定|你看着办|都行|都可以|无所谓|方便就行|还没想好|没想好|不确定|看情况|再说吧|还没定|未定");
    private static final List<String> CORE_MISSING = List.of("city", "days", "startDate");

    public ChatStrategy decide(ChatProfileDTO profile, ConversationStateDTO state,
                               String content, boolean ready, List<String> missing) {
        boolean asks = asks(content);
        if (asks) return ChatStrategy.ANSWER;
        if (ready) return ChatStrategy.COMPLETE;
        if (VAGUE.matcher(content == null ? "" : content).find()) return ChatStrategy.SUGGEST;

        int consecutive = state == null || state.getConsecutiveQuestions() == null
                ? 0 : state.getConsecutiveQuestions();
        if (consecutive >= 1) return ChatStrategy.SUGGEST;
        if (state != null && "ASK".equalsIgnoreCase(state.getLastStrategy())) {
            return ChatStrategy.SUGGEST;
        }

        if (missing != null) {
            for (String m : CORE_MISSING) {
                if (missing.contains(m)) return ChatStrategy.ASK;
            }
        }
        return ChatStrategy.DISCUSS;
    }

    public boolean asks(String content) {
        return content != null && ASK.matcher(content).find();
    }
}
