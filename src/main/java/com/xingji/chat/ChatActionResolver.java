package com.xingji.chat;

import com.xingji.dto.ChatAction;
import com.xingji.dto.ChatIntent;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 服务端最终动作。
 * 信息齐全 ≠ 用户授权：只有用户明确说"生成/开始排"才返回 GENERATE，
 * 模型给的 next_step 只能建议，不能决定后端执行。
 */
@Component
public class ChatActionResolver {

    private static final Pattern AUTHORIZE = Pattern.compile(
            "生成吧|开始生成|生成行程|出个行程|出份行程|出个方案|按这个生成|直接生成|帮我生成|排出来|就这么排|开始排");
    private static final Pattern DENY = Pattern.compile("先别生成|不用生成|不要生成|暂时不生成|先不生成");

    public ChatAction resolve(ChatProfileDTO profile, String content,
                              ChatIntent intent, ChatStrategy nextStep) {
        if (!ready(profile)) return ChatAction.CHAT;
        String text = content == null ? "" : content;
        if (DENY.matcher(text).find()) return ChatAction.CHAT;
        if (AUTHORIZE.matcher(text).find()) return ChatAction.GENERATE;
        return ChatAction.READY;
    }

    /** 目的地 + 天数或日期区间 + 出发/到达日期，且日期关系自洽 */
    public boolean ready(ChatProfileDTO p) {
        if (p == null) return false;
        if (!DateRules.consistent(p)) return false;
        boolean hasRange = notBlank(p.getStartDate()) && notBlank(p.getEndDate());
        boolean hasDepart = notBlank(p.getStartDate())
                || (p.getArrival() != null && notBlank(p.getArrival().getDate()));
        return notBlank(p.getCity()) && (p.getDays() != null || hasRange) && hasDepart;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}