package com.xingji.LLM;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 对话意图分流:在调用大模型之前,先用确定性规则把用户这句话归成三类。
 *
 * 为什么要有这一层,而不是全交给 system 提示词:
 *   1) 实测把「你好」「谢谢」这类寒暄发给模型,要花 1~11 秒、几百 token,换回来的只是一句模板话;
 *   2) 更严重的是——模型自己决定拒绝一个越界请求时,这次「越界问 + 越界答」会原样进入历史,
 *      之后的每一轮模型都看得见"我以前讲过笑话",规则就这样被历史一点点稀释,
 *      实测同一会话连续 8 次越界请求,后 8 次一次都没拒绝。
 *   服务端拦截 = 这次往返根本不产生历史,也就不会被后面的轮次当证据。
 *
 * 判断策略:宁可放过,不可错杀。
 *   - 先看是否命中明确越界特征(代码/翻译/创作/学科知识/娱乐内容),
 *   - 再看是否带旅行信号词(城市/天/预算/景点/出发/到达…)。带旅行信号的一律放行给模型,
 *     因为"帮我写一个成都三日游的安排"里也有"写",不能按关键词一刀切。
 *   - 两边都不明确 → 交给模型,模型那边还有 prompt 兜底。
 */
@Slf4j
@Component
public class ChatIntentRouter {

    /** 分流结果 */
    public enum Intent {
        /** 纯寒暄/确认,服务端直接回,不调模型、不进历史 */
        SMALLTALK,
        /** 明确越界,服务端礼貌拒绝并拉回旅行,不调模型、不进历史 */
        OFF_TOPIC,
        /** 旅行规划相关,走大模型 */
        TRAVEL
    }

    // ==================== 寒暄/确认词 ====================
    // 整句匹配(去掉标点空白后全等),避免"好的我有空就去成都玩三天"被误判成寒暄
    private static final List<String> SMALLTALK_EXACT = List.of(
            "谢谢", "谢谢你", "谢谢您", "感谢", "多谢", "谢了", "thanks", "thankyou", "thank you", "3q",
            "好的", "好", "嗯", "嗯嗯", "行", "可以", "可以可以", "明白了", "明白", "知道了", "知道",
            "收到", "收到了", "ok", "okay", "收到谢谢", "好的谢谢", "好滴", "中", "是的", "对的",
            "你叫什么", "你叫什么名字", "你是谁", "你能做什么", "你会什么", "你是什么", "介绍一下你自己",
            "在吗", "你好", "您好", "嗨", "哈喽", "hi", "hello", "hey", "早上好", "上午好", "中午好",
            "下午好", "晚上好", "晚安", "拜拜", "再见", "bye", "88", "辛苦了", "辛苦",
            "不错", "厉害", "真棒", "牛", "哈", "哈哈", "哈哈哈", "嘿嘿", "嗯好", "好嘞"
    );

    // ==================== 明确越界特征 ====================
    // 只看"请求做另一件事"的信号,不看单字(单字命中率太高)
    private static final Pattern OFF_TOPIC = Pattern.compile(
            "写(?:一段|个|一个|段|点)?(?:代码|脚本|程序|爬虫|函数|算法|正则|sql|SQL|python|Python|java|Java|js|JS)"
            + "|(?:爬虫|写代码|敲代码|编程|debug|调试代码)"
            + "|翻译(?:成|为)?(?:英文|英语|中文|日文|日语|韩语|法文|法语)"
            + "|(?:背|来|念|讲)(?:一首|一首诗|首诗|首)(?:诗|词)?"
            + "|(?:讲|说|来|整|编)(?:一?个)?(?:笑话|故事|鬼故事|段子|相声|绕口令|脑筋急转弯|谜语|成语故事|冷笑话)"
            + "|(?:说说|讲讲|介绍)(?:一下)?(?:相对论|量子|微积分|物理|化学|历史(?!上的)|地理知识)"
            + "|(?:股票|基金|炒股|理财|期货|比特币|加密货币)"
            + "|(?:请假条|辞职信|简历|情书|周报|工作总结|作文|论文)"
            + "|(?:写|作|对)(?:一首|首诗|对联|诗词)"
            + "|(?:怎么|如何|怎样)(?:做|制造|制作)(?:炸弹|毒|枪|毒品)"
            + "|(?:数学题|算术|算什么|解方程|[0-9]+\\s*[+\\-×÷*\\/]\\s*[0-9]+)"
            + "|(?:讲|说|教)(?:一下)?(?:怎么|如何)?(?:炒股|买股票)"
            + "|(?:和|跟)(?:女朋友|男朋友|老婆|对象|父母)(?:分手|吵架|离婚|闹矛盾)"
            + "|(?:游戏|王者|原神|英雄联盟|steam)"
            + "|(?:帮|替)我(?:写|做|画|编)(?:一?[个份条篇张幅])?(?!.*(?:行程|路线|攻略|计划|安排))"
    );

    // ==================== 旅行信号词 ====================
    // 命中任意一个就把这句话当旅行请求放行给模型(宁可放过)
    private static final Pattern TRAVEL_SIGNAL = Pattern.compile(
            "旅行|旅游|出游|出行|行程|攻略|路线|景点|打卡|游玩|玩|逛|自由行|跟团|自驾"
            + "|出发|到达|返程|回程|抵达|酒店|住宿|民宿|机票|高铁|动车|火车|航班|车票|车站|机场"
            + "|预算|花费|花费|花销|人均|多少钱够|费用|省钱|贵不贵"
            + "|几天|三天|两天|一天|四天|五天|周末|假期|年假|出差|放假"
            + "|去哪|去哪儿|去哪里|好玩|值得|推荐|安排|规划|计划|行程单"
    );

    // 常见城市/省份名(只做信号词用,不是完整清单;地名在画像里由模型抽取)
    private static final Pattern PLACE_SIGNAL = Pattern.compile(
            "北京|上海|广州|深圳|成都|重庆|杭州|南京|西安|武汉|长沙|厦门|青岛|大连|昆明|大理|丽江"
            + "|三亚|海口|桂林|阳朔|苏州|扬州|无锡|宁波|福州|合肥|郑州|济南|天津|沈阳|哈尔滨|长春"
            + "|呼和浩特|乌鲁木齐|拉萨|西宁|兰州|银川|太原|石家庄|南昌|贵阳|南宁|珠海|佛山|东莞"
            + "|香港|澳门|台北|高雄|泰国|日本|韩国|新加坡|马来西亚|越南|欧洲|美国|英国|法国|意大利"
            + "|张家界|九寨沟|黄山|泰山|华山|峨眉山|稻城|香格里拉|凤凰|平遥|敦煌|呼伦贝尔|婺源"
            + "|四川|云南|广西|贵州|湖南|湖北|陕西|甘肃|青海|新疆|西藏|内蒙古|福建|浙江|江苏|广东"
            + "|山东|河南|河北|山西|安徽|江西|辽宁|吉林|黑龙江|海南|宁夏"
    );

    /** 去掉空白与标点,只留文字和数字,用于整句精确比对 */
    private String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}。，、！？；：”“‘’（）【】《》…—～·\\u3000]", "").toLowerCase();
    }

    /**
     * 从这句话里认出城市名(用于"用户第一句就提到目的地"的场景)。
     *
     * 为什么需要:服务端在调模型之前要先取景点候选池,但此时本轮的城市还没被抽取进画像。
     * 用已知行政区/城市名词表做一次确定性识别,就能让"我想去北海玩"这一句
     * 立刻带上真实景点卡片,而不用等下一轮。
     * 认不出返回 null(调用方退回用画像里的城市)。
     */
    public String detectPlace(String content) {
        if (content == null || content.isBlank()) return null;
        java.util.regex.Matcher m = PLACE_SIGNAL.matcher(content);
        if (!m.find()) return null;
        String raw = m.group();
        // 去掉"省/市/自治区"这类后缀,和高德检索、画像里的写法保持一致
        return raw.replaceAll("(省|市|自治区|特别行政区|壮族|回族|维吾尔)$", "");
    }

    /** 判断这句话该怎么处理;null/空白输入由调用方先挡掉 */
    public Intent route(String content) {
        if (content == null || content.isBlank()) return Intent.TRAVEL;
        String raw = content.trim();
        String norm = normalize(raw);

        // 1) 寒暄/确认:整句精确命中才算。带问句/带旅行词的都不算。
        if (norm.length() <= 20 && SMALLTALK_EXACT.contains(norm)) {
            return Intent.SMALLTALK;
        }

        boolean hasTravel = TRAVEL_SIGNAL.matcher(raw).find() || PLACE_SIGNAL.matcher(raw).find();
        boolean hasOffTopic = OFF_TOPIC.matcher(raw).find();

        // 2) 带旅行信号 → 一律放行给模型(哪怕句子里同时有"写""讲"这类动词)
        if (hasTravel) return Intent.TRAVEL;

        // 3) 没有旅行信号,但命中明确越界特征 → 服务端拒绝
        if (hasOffTopic) return Intent.OFF_TOPIC;

        // 4) 都不明确(如"随便""嗯…""今天心情不好")→ 交给模型,模型那边 prompt 兜底
        return Intent.TRAVEL;
    }

    /**
     * 判断是不是纯确认词。已有目的地、行程还没聊完时，这类输入交给模型接着上下文回答，
     * 比服务端反复吐同一句模板更有对话感；没有上下文时仍然走秒回，避免无意义调模型。
     */
    public boolean isBareAcknowledgement(String content) {
        String norm = normalize(content);
        return List.of("嗯", "嗯嗯", "好", "好的", "好滴", "好嘞", "行", "可以", "可以可以",
                "收到", "收到了", "收到谢谢", "好的谢谢", "明白", "明白了", "知道", "知道了",
                "ok", "okay", "中", "是的", "对的").contains(norm);
    }

    // ==================== 预置话术 ====================
    // 要求:拒绝之后必须给一句把用户拉回旅行的引导,不能冷冷结束。

    /**
     * 越界请求的拒绝话术(带拉回引导)。
     * 已经知道目的地时,引导语要接着当前进度说("接着看成都这边"),而不是每次都从零开始问"想去哪儿"。
     */
    public String offTopicReply(String city) {
        if (city == null || city.isBlank()) {
            return "这个我真帮不上忙。旅行这边你接着说，目的地、天数或大概预算都行。";
        }
        return "这个我真帮不上忙。咱们先接着看" + city + "，日期和住宿不急着一次说完。";
    }

    /**
     * 寒暄话术。context 是画像里已有的目的地(可空),用来让回答贴合当前对话,
     * 而不是每一句都回同一句模板。
     */
    public String smalltalkReply(String normalizedInput, String city, List<String> missing) {
        String focus = focusLine(city, missing);
        if (normalizedInput.startsWith("你叫什么") || normalizedInput.startsWith("你是谁")
                || normalizedInput.startsWith("你能做什么") || normalizedInput.startsWith("你会什么")
                || normalizedInput.startsWith("介绍一下你自己") || normalizedInput.startsWith("你是什么")) {
            return "我是「行迹」的旅行规划助手，帮你把想去的地方排成一份能照着走的行程。" + focus;
        }
        if (normalizedInput.startsWith("在吗") || normalizedInput.startsWith("你好")
                || normalizedInput.startsWith("您好") || normalizedInput.startsWith("嗨")
                || normalizedInput.startsWith("哈喽") || normalizedInput.startsWith("hi")
                || normalizedInput.startsWith("hello")) {
            return "在的。" + focus;
        }
        if (normalizedInput.startsWith("拜拜") || normalizedInput.startsWith("再见")
                || normalizedInput.startsWith("bye") || normalizedInput.startsWith("晚安")) {
            return "好的，需要排行程的时候随时叫我。";
        }
        if (normalizedInput.startsWith("谢谢") || normalizedInput.startsWith("感谢")
                || normalizedInput.startsWith("多谢") || normalizedInput.startsWith("谢了")) {
            return city == null || city.isBlank()
                    ? "不客气，有旅行想法随时说。"
                    : "不客气，" + city + "这边还有想补的随时说。";
        }
        // 其余(好的/嗯/行/收到/哈哈…):接住确认,不机械推进日期
        if (city == null || city.isBlank()) {
            return "好，那先从目的地开始，想去哪儿？";
        }
        if (missing != null && missing.contains("startDate")) {
            return "好，那就先按现在这个方向看。日期没定也没关系，先聊路线、吃住也行。";
        }
        return "好，那就接着按" + city + "这边来。有想补的直接说。";
    }

    /** 根据当前画像给一句"下一步做什么",避免每轮都是同一句模板 */
    private String focusLine(String city, List<String> missing) {
        if (city == null || city.isBlank()) return "想去哪个城市？";
        if (missing != null && missing.contains("days")) {
            return city + "这边可以先看，玩几天我也可以先给你一个常见节奏参考。";
        }
        if (missing != null && missing.contains("startDate")) {
            return city + "可以先记着，日期没定也没关系，想先看路线、住宿区域或美食都可以。";
        }
        return city + "这边咱们接着看，有想先聊的路线或吃住，直接说就行。";
    }
}
