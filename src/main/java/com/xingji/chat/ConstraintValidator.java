package com.xingji.chat;

import com.xingji.dto.ConstraintType;
import com.xingji.dto.GeneratedItineraryDTO;
import com.xingji.dto.TravelConstraintDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成结果的约束二次校验。
 *
 * 只在推荐阶段过滤不够：用户可能直接点生成，模型也可能在生成时自行加景点。
 * 这里按用户的有效约束删除明确违规的节点；无法判断的（如成分未知的菜）不删，
 * 但记日志，绝不宣称"已满足安全要求"。
 */
@Slf4j
@Component
public class ConstraintValidator {

    public int enforce(GeneratedItineraryDTO gen, List<TravelConstraintDTO> constraints) {
        List<TravelConstraintDTO> active = ConstraintExtractor.active(constraints);
        if (gen == null || gen.getDays() == null || active.isEmpty()) return 0;

        boolean noSeafood = false;
        boolean noHiking = false;
        boolean noShopping = false;
        boolean safetyFood = false;
        for (TravelConstraintDTO c : active) {
            String text = c.getContent() == null ? "" : c.getContent();
            if (ConstraintExtractor.mentionsSeafood(text)) {
                noSeafood = true;
                if (c.getType() == ConstraintType.SAFETY) safetyFood = true;
            } else if (c.getType() == ConstraintType.SAFETY && text.contains("过敏")) {
                safetyFood = true;
            }
            if (ConstraintExtractor.mentionsHiking(text)) noHiking = true;
            if (ConstraintExtractor.mentionsShopping(text)) noShopping = true;
        }
        if (!noSeafood && !noHiking && !noShopping) {
            if (safetyFood) {
                log.warn("存在未指明具体食物的安全约束，生成结果无法逐项判定，保留节点并需人工核实");
            }
            return 0;
        }

        int dropped = 0;
        List<String> unverified = new ArrayList<>();
        for (GeneratedItineraryDTO.GeneratedDay day : gen.getDays()) {
            if (day == null || day.getNodes() == null) continue;
            List<GeneratedItineraryDTO.GeneratedNode> kept = new ArrayList<>();
            for (GeneratedItineraryDTO.GeneratedNode n : day.getNodes()) {
                if (n == null) continue;
                String name = n.getPlaceName() == null ? "" : n.getPlaceName();
                String type = n.getNodeType() == null ? "" : n.getNodeType().trim();
                boolean foodOrSpot = "餐饮".equals(type) || "景点".equals(type);

                if (noSeafood && foodOrSpot && ConstraintExtractor.isSeafoodName(name)) {
                    dropped++;
                    log.info("约束校验:删除海鲜相关节点 -> {}", name);
                    continue;
                }
                if (noHiking && "景点".equals(type) && ConstraintExtractor.isHikingName(name)) {
                    dropped++;
                    log.info("约束校验:删除登山相关节点 -> {}", name);
                    continue;
                }
                if (noShopping && ConstraintExtractor.isShoppingName(name)) {
                    dropped++;
                    log.info("约束校验:删除购物节点 -> {}", name);
                    continue;
                }
                if (safetyFood && "餐饮".equals(type) && !ConstraintExtractor.isSeafoodName(name)) {
                    // 成分未知的餐饮：保留，但必须让上层知道"未核实"
                    unverified.add(name);
                }
                kept.add(n);
            }
            day.setNodes(kept);
        }
        if (dropped > 0) {
            log.info("约束校验:共删除 {} 个违反用户约束的节点", dropped);
        }
        if (!unverified.isEmpty()) {
            log.warn("约束校验:以下餐饮无法确认是否含过敏原，需人工核实: {}", unverified);
        }
        return dropped;
    }
}