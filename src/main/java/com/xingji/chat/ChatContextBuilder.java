package com.xingji.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.ChatStrategy;
import com.xingji.dto.ConstraintType;
import com.xingji.dto.TravelConstraintDTO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 本轮模型输入的组装。
 * 原则：给事实和策略，不给"必须照做的流程"。
 */
@Component
@RequiredArgsConstructor
public class ChatContextBuilder {

    private final ObjectMapper objectMapper;

    public String build(String content, ChatProfileDTO p, ChatStrategy strategy,
                        List<String> missing, String lastAsked, String facts,
                        List<SpotVO> spots, boolean ready) {
        return build(content, p, strategy, missing, lastAsked, facts, spots, ready, null, false);
    }

    public String build(String content, ChatProfileDTO p, ChatStrategy strategy,
                        List<String> missing, String lastAsked, String facts,
                        List<SpotVO> spots, boolean ready,
                        String lastAssistantReply, boolean bareAcknowledgement) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统时间] 今天是 ").append(LocalDate.now())
                .append("（").append(weekdayZh(LocalDate.now())).append("）。\n");
        sb.append("[已确认画像，只作背景，不要逐项复述] ")
                .append(profileJson(p)).append("\n");

        List<TravelConstraintDTO> cons = ConstraintExtractor.active(p.getConstraints());
        if (!cons.isEmpty()) {
            sb.append("[用户硬约束(推荐与计划中不得违反)]\n");
            for (TravelConstraintDTO c : cons) {
                sb.append("  - ").append(c.getContent());
                if (c.getType() == ConstraintType.SAFETY) {
                    sb.append("（安全约束：无法确认成分/安全性的内容必须提示用户自行核实，不得宣称安全）");
                }
                sb.append("\n");
            }
        }

        String nextGap = strategy == ChatStrategy.ASK ? nextGap(missing) : null;
        if (nextGap != null) {
            sb.append("[可自然推进的一项] ").append(nextGap).append("\n");
        }

        if (lastAsked != null && !lastAsked.isBlank()) {
            sb.append("[上一轮你最后问的是] ").append(lastAsked)
                    .append("（先判断用户这轮是否已经回应；回应了就顺着往下，没回应就别原样再问）\n");
        }
        if (lastAssistantReply != null && !lastAssistantReply.isBlank()) {
            sb.append("[上一条助手回复] ").append(compact(lastAssistantReply, 220))
                    .append("（不要原样复用这句话）\n");
        }
        if (bareAcknowledgement) {
            sb.append("[本轮是纯确认] 用户没有给新需求。不要停留在“随时补一句”这种空话上，")
                    .append("能判断方向就给一个具体、可执行的下一步。")
                    .append("但不要默认天数，不要写“先按3天/两天搭一版”，也不要擅自列“第一天/第二天/第三天”。")
                    .append("不要把这句话当成用户新增了日期、天数、人数或预算；profile_patch 留空。\n");
        }
        if (facts != null && !facts.isBlank()) {
            sb.append("[本轮已识别事实，仅供后台抽取] ").append(facts)
                    .append("（不要在回复里逐项复述）\n");
        }

        sb.append("[交流节奏] ").append(strategyHint(strategy)).append("\n");
        if (ready) {
            sb.append("[状态] 信息已经够开排，可以自然提示用户点「生成行程」；仍然先回应用户当前这句话。\n");
        }

        if (spots != null && !spots.isEmpty()) {
            sb.append("[系统已查到的真实景点(前端会以图文卡片展示)]\n");
            for (SpotVO s : spots) {
                sb.append("  - ").append(s.getName());
                if (s.getCategory() != null && !s.getCategory().isBlank()) {
                    sb.append("（").append(s.getCategory()).append("）");
                }
                sb.append("\n");
            }
            sb.append("[景点卡片] 评分、门票、开放时间、价格由前端卡片展示，模型输入里不提供；")
                    .append("正文只补充怎么选、怎么串、带什么人要注意什么，不要报具体金额或营业时间。\n");
        }

        sb.append("[用户本轮消息] ").append(content).append("\n");
        sb.append("[输出契约] 只输出一个 JSON 对象，不要 Markdown、不要解释文字：\n")
                .append("{\"reply\":\"给用户看的自然语言\",")
                .append("\"intent\":\"EXPLORE|PLAN|DISCUSS|MODIFY|CONFIRM|SMALL_TALK|OUT_OF_SCOPE|UNKNOWN\",")
                .append("\"profile_patch\":{},")
                .append("\"conversation_update\":{},")
                .append("\"next_step\":\"ANSWER|SUGGEST|ASK|DISCUSS|CONFIRM|COMPLETE\"}\n")
                .append("next_step 只是建议，服务端会自行决定是否可生成。");
        return sb.toString();
    }

    private String strategyHint(ChatStrategy s) {
        if (s == null) return "先回应用户这句话，按正常交流自然推进。";
        return switch (s) {
            case ANSWER -> "用户正在问一件事，先把这件事答清楚；可以自然结束，不必为了收资料追问。";
            case SUGGEST -> "用户还没决定或不想继续被追问，先给一个具体建议或暂定方案，让他容易接话。";
            case ASK -> "如果确实需要一个信息才能继续，只自然推进一个最关键的点；不要复盘已有字段。";
            case DISCUSS -> "顺着当前话题讨论、比较或补充判断，不急着推进流程。";
            case CONFIRM -> "简短接住用户的确认，不要重复整份画像。";
            case COMPLETE -> "信息已经够用，先接住当前这句话，再自然提示可以生成行程。";
        };
    }

    private String nextGap(List<String> missing) {
        if (missing == null || missing.isEmpty()) return null;
        if (missing.contains("city")) return "目的地城市";
        if (missing.contains("days")) return "大概玩几天";
        if (missing.contains("startDate")) return "大概什么时候出发；没定就先聊节奏或季节，不要替用户编日期、周末或星期";
        return null;
    }

    private String profileJson(ChatProfileDTO p) {
        try {
            return objectMapper.writeValueAsString(p == null ? new ChatProfileDTO() : p);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String compact(String text, int maxChars) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars).trim() + "…";
    }

    private String weekdayZh(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }
}
