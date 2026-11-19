package com.xingji.chat;

import com.xingji.dto.ConstraintType;
import com.xingji.dto.TravelConstraintDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 约束抽取与归一。
 *
 * 模型可以给 profile_patch.constraints，但模型漏掉时这里要兜底——
 * “海鲜过敏”这种信息不能因为模型这轮没写就消失。
 */
public final class ConstraintExtractor {

    private static final Pattern SAFETY = Pattern.compile("过敏|忌口|禁食|孕妇|心脏病|哮喘|糖尿病|高血压|癫痫");
    private static final Pattern EXCLUSION = Pattern.compile(
            "不吃|不想|不要|别安排|不安排|不去|不能|避免|讨厌|不购物|不爬山|不爬|不想去|受不了");
    private static final Pattern PREFERENCE = Pattern.compile("喜欢|偏好|想吃|想看|想住|尽量|希望");

    private static final Pattern FOOD = Pattern.compile("吃|海鲜|辣|忌口|食物|菜|餐|过敏");
    private static final Pattern ACTIVITY = Pattern.compile("爬山|登山|徒步|爬坡|活动|运动|走不动");
    private static final Pattern LODGING = Pattern.compile("住|酒店|民宿|客栈");
    private static final Pattern SHOPPING = Pattern.compile("购物|商场|特产店|购物店");

    public static final Pattern SEAFOOD_WORDS = Pattern.compile(
            "海鲜|虾|蟹|贝|蚝|牡蛎|海胆|鱿鱼|章鱼|沙虫|土笋冻|鱼|海参|鲍鱼|生蚝");
    private static final Pattern HIKING_WORDS = Pattern.compile("爬山|登山|爬坡|徒步|登山道|步道|栈道");

    private ConstraintExtractor() {
    }

    /** 把本轮原话里的约束合并进已有列表；同时处理“之前说不爬山，这次可以接受”这类修改。 */
    public static List<TravelConstraintDTO> apply(String content, List<TravelConstraintDTO> existing) {
        List<TravelConstraintDTO> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (content == null || content.isBlank()) return list;
        String text = content.trim();

        if (isModificationSentence(text) && !list.isEmpty()) {
            // 修改语句只调整已有约束的生效状态；不要从同一句里再把旧约束抽出来重新激活
            deactivateMatches(text, list);
            return list;
        }
        for (String clause : splitClauses(text)) {
            TravelConstraintDTO c = classify(clause);
            if (c != null) upsert(list, c);
        }
        while (list.size() > 20) list.remove(0);
        return list;
    }

    /** 合并模型给出的 constraints 补丁；active=false 表示用户取消了该约束 */
    public static List<TravelConstraintDTO> mergePatch(List<TravelConstraintDTO> existing,
                                                       List<TravelConstraintDTO> patch) {
        List<TravelConstraintDTO> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        if (patch == null) return list;
        for (TravelConstraintDTO c : patch) {
            if (c == null || c.getContent() == null || c.getContent().isBlank()) continue;
            String content = c.getContent().trim();
            if (content.length() > 60) content = content.substring(0, 60);
            TravelConstraintDTO hit = null;
            for (TravelConstraintDTO e : list) {
                if (e != null && e.getContent() != null && sameConstraint(e, c)) {
                    hit = e;
                    break;
                }
            }
            boolean active = c.getActive() == null || c.getActive();
            if (hit != null) {
                hit.setActive(active);
                hit.setType(c.getType() == null ? hit.getType() : c.getType());
                hit.setScope(c.getScope() == null ? hit.getScope() : c.getScope());
                hit.setSourceText(c.getSourceText() == null ? hit.getSourceText() : c.getSourceText());
            } else if (active) {
                TravelConstraintDTO dto = new TravelConstraintDTO();
                dto.setContent(content);
                dto.setType(c.getType() == null ? ConstraintType.EXCLUSION : c.getType());
                dto.setScope(c.getScope() == null ? scopeOf(content) : c.getScope());
                dto.setActive(true);
                dto.setSourceText(c.getSourceText() == null ? content : c.getSourceText());
                list.add(dto);
            }
        }
        while (list.size() > 20) list.remove(0);
        return list;
    }

    public static List<TravelConstraintDTO> active(List<TravelConstraintDTO> list) {        List<TravelConstraintDTO> out = new ArrayList<>();
        if (list == null) return out;
        for (TravelConstraintDTO c : list) {
            if (c != null && c.isEffective() && c.getContent() != null && !c.getContent().isBlank()) {
                out.add(c);
            }
        }
        return out;
    }

    public static boolean isModificationSentence(String text) {
        return Pattern.compile("之前|原来|以前|不再|取消|可以接受|改成|改成").matcher(text).find();
    }

    private static void deactivateMatches(String text, List<TravelConstraintDTO> list) {
        for (TravelConstraintDTO c : list) {
            if (c == null || c.getContent() == null) continue;
            String content = c.getContent();
            boolean sameTopic =
                    (mentionsHiking(content) && mentionsHiking(text))
                    || (mentionsSeafood(content) && mentionsSeafood(text))
                    || (mentionsShopping(content) && mentionsShopping(text));
            if (sameTopic) {
                c.setActive(false);
            }
        }
    }

    private static TravelConstraintDTO classify(String clause) {
        String c = clause.trim().replaceAll("^[，,。；;\\s]+|[，,。；;\\s]+$", "");
        if (c.isEmpty() || c.length() > 60) return null;
        ConstraintType type;
        if (SAFETY.matcher(c).find()) type = ConstraintType.SAFETY;
        else if (EXCLUSION.matcher(c).find()) type = ConstraintType.EXCLUSION;
        else if (PREFERENCE.matcher(c).find()) type = ConstraintType.PREFERENCE;
        else return null;

        TravelConstraintDTO dto = new TravelConstraintDTO();
        dto.setContent(c);
        dto.setType(type);
        dto.setScope(scopeOf(c));
        dto.setActive(true);
        dto.setSourceText(c);
        return dto;
    }

    private static String scopeOf(String c) {
        if (FOOD.matcher(c).find()) return "food";
        if (ACTIVITY.matcher(c).find()) return "activity";
        if (LODGING.matcher(c).find()) return "lodging";
        if (SHOPPING.matcher(c).find()) return "place";
        return "general";
    }

    private static void upsert(List<TravelConstraintDTO> list, TravelConstraintDTO incoming) {
        for (TravelConstraintDTO c : list) {
            if (c == null || c.getContent() == null) continue;
            if (sameConstraint(c, incoming)) {
                c.setActive(true);
                // 同一件事取更严重的类型和更短的表述，避免"海鲜过敏/她海鲜过敏/别安排海鲜"三条并存
                if (rank(incoming.getType()) > rank(c.getType())
                        || (rank(incoming.getType()) == rank(c.getType())
                        && incoming.getContent().length() < c.getContent().length())) {
                    c.setContent(incoming.getContent());
                    c.setType(incoming.getType());
                    c.setScope(incoming.getScope());
                }
                c.setSourceText(incoming.getSourceText());
                return;
            }
        }
        list.add(incoming);
    }

    private static boolean sameConstraint(TravelConstraintDTO a, TravelConstraintDTO b) {
        String x = norm(a.getContent());
        String y = norm(b.getContent());
        if (x.equals(y) || x.contains(y) || y.contains(x)) return true;
        boolean sameScope = a.getScope() == null || b.getScope() == null || a.getScope().equals(b.getScope());
        boolean sameTopic = (mentionsSeafood(x) && mentionsSeafood(y))
                || (mentionsHiking(x) && mentionsHiking(y))
                || (mentionsShopping(x) && mentionsShopping(y));
        return sameScope && sameTopic;
    }

    private static int rank(ConstraintType t) {
        if (t == ConstraintType.SAFETY) return 3;
        if (t == ConstraintType.EXCLUSION) return 2;
        return 1;
    }

    private static List<String> splitClauses(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("[。！？；;\\n]")) {
            for (String part : s.split("[，,]")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s，,。；;、]", "");
    }

    public static boolean mentionsSeafood(String text) {
        return SEAFOOD_WORDS.matcher(text == null ? "" : text).find();
    }

    public static boolean mentionsHiking(String text) {
        return HIKING_WORDS.matcher(text == null ? "" : text).find();
    }

    public static boolean mentionsShopping(String text) {
        return SHOPPING.matcher(text == null ? "" : text).find();
    }

    /** 判断一个地点/菜品名是否明确属于海鲜类（保守：命中即视为违反安全约束） */
    public static boolean isSeafoodName(String name) {
        return SEAFOOD_WORDS.matcher(name == null ? "" : name).find();
    }

    /** 判断一个景点名是否明确是登山/徒步类；"中山路"这类干扰用后缀与特征词规避 */
    public static boolean isHikingName(String name) {
        if (name == null || name.isBlank()) return false;
        if (HIKING_WORDS.matcher(name).find()) return true;
        return Pattern.compile("(山$|山[^路街道站]|峰$|岭$|登山)").matcher(name.trim()).find();
    }

    public static boolean isShoppingName(String name) {
        return SHOPPING.matcher(name == null ? "" : name).find();
    }
}