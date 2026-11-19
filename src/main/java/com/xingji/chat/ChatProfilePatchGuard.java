package com.xingji.chat;

import com.xingji.dto.AnchorStateDTO;
import com.xingji.dto.ChatProfileDTO;
import com.xingji.dto.TimePeriod;
import com.xingji.dto.TravelConstraintDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 纯确认词本身不携带新事实。模型偶尔会把上一轮未回答的开放问题补成一个
 * 具体天数或日期，这里只保留上一句助手确实提出过、用户这声确认能够指向的值。
 */
@Component
public class ChatProfilePatchGuard {

    public ChatProfileDTO guard(ChatProfileDTO patch, String lastAssistantReply,
                                boolean bareAcknowledgement) {
        if (!bareAcknowledgement || patch == null) return patch;

        ChatProfileDTO guarded = new ChatProfileDTO();
        String source = lastAssistantReply == null ? "" : lastAssistantReply;
        if (source.isBlank()) return guarded;

        if (mentioned(source, patch.getOrigin())) guarded.setOrigin(patch.getOrigin());
        if (mentioned(source, patch.getCity())) guarded.setCity(patch.getCity());
        if (mentioned(source, patch.getCompanionType())) guarded.setCompanionType(patch.getCompanionType());
        if (mentioned(source, patch.getPace())) guarded.setPace(patch.getPace());
        if (mentioned(source, patch.getTransport())) guarded.setTransport(patch.getTransport());
        if (mentioned(source, patch.getLodging())) guarded.setLodging(patch.getLodging());
        if (mentioned(source, patch.getLodgingMode())) guarded.setLodgingMode(patch.getLodgingMode());

        if (patch.getDays() != null && containsDayCount(source, patch.getDays())) {
            guarded.setDays(patch.getDays());
        }
        if (patch.getGroupSize() != null && containsPeopleCount(source, patch.getGroupSize())) {
            guarded.setGroupSize(patch.getGroupSize());
        }
        if (patch.getBudget() != null && containsNumber(source, patch.getBudget())) {
            guarded.setBudget(patch.getBudget());
        }

        DateEvidence evidence = DateEvidence.of(source);
        if (dateMentioned(evidence, patch.getStartDate())) guarded.setStartDate(patch.getStartDate());
        if (dateMentioned(evidence, patch.getEndDate())) guarded.setEndDate(patch.getEndDate());

        guarded.setPreferences(mentionedItems(source, patch.getPreferences()));
        guarded.setMustVisit(mentionedItems(source, patch.getMustVisit()));
        guarded.setAvoid(mentionedItems(source, patch.getAvoid()));
        guarded.setConstraints(mentionedConstraints(source, patch.getConstraints()));
        guarded.setArrival(guardAnchor(patch.getArrival(), source, evidence));
        guarded.setDeparture(guardAnchor(patch.getDeparture(), source, evidence));
        return guarded;
    }

    private AnchorStateDTO guardAnchor(AnchorStateDTO patch, String source, DateEvidence evidence) {
        if (patch == null) return null;

        AnchorStateDTO guarded = new AnchorStateDTO();
        if (dateMentioned(evidence, patch.getDate())) guarded.setDate(patch.getDate());
        if (mentioned(source, patch.getTime()) && evidence.hasTime(patch.getTime())) {
            guarded.setTime(patch.getTime());
        }
        if (mentioned(source, patch.getLocation())) guarded.setLocation(patch.getLocation());
        if (patch.getType() != null && mentioned(source, transitLabel(patch.getType()))) {
            guarded.setType(patch.getType());
        }
        if (patch.getTimePeriod() != null && mentioned(source, periodLabel(patch.getTimePeriod()))) {
            guarded.setTimePeriod(patch.getTimePeriod());
        }
        if (guarded.getDate() == null && guarded.getTime() == null
                && guarded.getLocation() == null && guarded.getType() == null
                && guarded.getTimePeriod() == null) {
            return null;
        }
        guarded.setSource("chat");
        guarded.setConfidence(patch.getConfidence());
        return guarded;
    }

    private List<String> mentionedItems(String source, List<String> items) {
        if (items == null) return null;
        List<String> kept = new ArrayList<>();
        for (String item : items) {
            if (mentioned(source, item)) kept.add(item);
        }
        return kept;
    }

    private List<TravelConstraintDTO> mentionedConstraints(String source,
                                                           List<TravelConstraintDTO> constraints) {
        if (constraints == null) return null;
        List<TravelConstraintDTO> kept = new ArrayList<>();
        for (TravelConstraintDTO constraint : constraints) {
            if (constraint != null && mentioned(source, constraint.getContent())) kept.add(constraint);
        }
        return kept;
    }

    private boolean mentioned(String source, String value) {
        return value != null && !value.isBlank() && source.contains(value);
    }

    private boolean dateMentioned(DateEvidence evidence, String value) {
        return value != null && !value.isBlank() && evidence.hasDate(value);
    }

    private boolean containsDayCount(String source, int days) {
        return Pattern.compile("(?<!第)(?:" + days + "|" + chineseVariants(days) + ")\\s*天")
                .matcher(source).find();
    }

    private boolean containsPeopleCount(String source, int people) {
        return Pattern.compile("(?:" + people + "|" + chineseVariants(people) + ")\\s*(?:个)?\\s*(?:人|位)")
                .matcher(source).find();
    }

    private boolean containsNumber(String source, BigDecimal number) {
        String value = number.stripTrailingZeros().toPlainString();
        return Pattern.compile("(?<!\\d)" + Pattern.quote(value) + "(?!\\d)")
                .matcher(source).find();
    }

    private String chineseNumber(int value) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (value < 10) return digits[value];
        if (value == 10) return "十";
        if (value < 20) return "十" + digits[value - 10];
        if (value < 100) {
            int tens = value / 10;
            int ones = value % 10;
            return digits[tens] + "十" + (ones == 0 ? "" : digits[ones]);
        }
        return String.valueOf(value);
    }

    private String chineseVariants(int value) {
        String chinese = chineseNumber(value);
        return value == 2 ? "(?:二|两)" : chinese;
    }

    private String transitLabel(String type) {
        if (type == null) return "";
        return switch (type) {
            case "train" -> "高铁";
            case "flight" -> "飞机";
            case "bus" -> "大巴";
            case "car" -> "自驾";
            default -> type;
        };
    }

    private String periodLabel(TimePeriod period) {
        if (period == null) return "";
        return switch (period) {
            case MORNING -> "上午";
            case AFTERNOON -> "下午";
            case EVENING -> "傍晚";
            case NIGHT -> "晚上";
            case UNKNOWN -> "不确定";
        };
    }
}
