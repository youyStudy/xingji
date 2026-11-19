package com.xingji.chat;

import com.xingji.dto.ChatIntent;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.dto.ConversationStateDTO;
import com.xingji.dto.ConversationUpdateDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 把模型建议的 conversation_update 与服务端事实合并成下一轮状态。 */
@Component
public class ConversationStateService {

    private static final Set<String> STAGES = Set.of("COLLECTING", "DISCUSSING", "READY");

    public ConversationStateDTO merge(ConversationStateDTO state, ConversationUpdateDTO update,
                                      ChatIntent intent, ChatStrategy strategy, boolean asked,
                                      boolean ready, ChatProfileDTO profile, List<String> missing) {
        if (state == null) state = new ConversationStateDTO();

        String stage;
        if (ready) {
            stage = "READY";
        } else if (update != null && update.getStage() != null
                && STAGES.contains(update.getStage().trim().toUpperCase())) {
            stage = update.getStage().trim().toUpperCase();
        } else {
            stage = (missing == null || missing.isEmpty()) ? "DISCUSSING" : "COLLECTING";
        }
        state.setStage(stage);
        state.setLastIntent(intent == null ? ChatIntent.UNKNOWN.name() : intent.name());
        state.setLastStrategy(strategy == null ? null : strategy.name());

        int consecutive = state.getConsecutiveQuestions() == null ? 0 : state.getConsecutiveQuestions();
        state.setConsecutiveQuestions(asked && strategy == ChatStrategy.ASK ? consecutive + 1 : 0);

        String topic = update != null ? clean(update.getCurrentTopic(), 40) : null;
        if (topic != null) {
            state.setCurrentTopic(topic);
        } else if (profile != null && profile.getCity() != null && !profile.getCity().isBlank()
                && (state.getCurrentTopic() == null || state.getCurrentTopic().isBlank())) {
            state.setCurrentTopic(profile.getCity() + "旅行安排");
        }

        if (update != null && update.getUndecidedItems() != null && !update.getUndecidedItems().isEmpty()) {
            state.setUndecidedItems(sanitizeList(update.getUndecidedItems()));
        }
        if (update != null && update.getAcceptedSuggestions() != null
                && !update.getAcceptedSuggestions().isEmpty()) {
            state.setAcceptedSuggestions(sanitizeList(update.getAcceptedSuggestions()));
        }
        return state;
    }

    private List<String> sanitizeList(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : raw) {
            String v = clean(s, 50);
            if (v == null || !seen.add(v)) continue;
            out.add(v);
            if (out.size() >= 10) break;
        }
        return out;
    }

    private String clean(String s, int max) {
        if (s == null) return null;
        String v = s.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }
}