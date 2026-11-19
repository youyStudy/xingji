package com.xingji.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 大模型 JSON 输出修复器。
 *
 * 为什么需要它:LLM 输出结构化 JSON 时经常出现"局部语法错误",最典型的是漏写字符串引号 ——
 * 实测 GLM-4.5-Flash 连续 3 次都在同一处把
 *   "endTime":"17:00"  写成了   "endTime":17:00
 * 结果整段 JSON 解析失败。这类错误不是模型"不懂",而是长输出时的偶发抖动,
 * 用代码修复的成本远低于让用户再等 2 分钟重新生成一遍。
 *
 * 实现上刻意用逐字符扫描而不是正则:正则里的反斜杠转义在 Java 源码中极易写错,
 * 而这里要处理的恰恰是引号、冒号这类字符,扫一遍反而更直白、更可控。
 *
 * 修复策略:只做"删歧义、补齐明显缺失"的无损修复,不猜测业务语义。
 * 修不好就原样返回,交给上层决定重试还是报错——绝不产出"看起来对但其实错了"的 JSON。
 */
@Slf4j
public final class JsonRepair {

    /** 需要检查是否漏引号的时间字段名 */
    private static final String[] TIME_KEYS = {"startTime", "endTime", "time", "datetime"};

    private JsonRepair() {}

    /**
     * 修复常见的 LLM JSON 语法错误。修复不了就原样返回。
     * 调用方负责再尝试一次解析;这里不抛异常。
     */
    public static String repair(String json) {
        if (json == null || json.isBlank()) return json;
        String original = json;
        String s = stripFence(json);
        s = normalizeQuotes(s);   // 中文引号 -> 英文引号
        s = quoteBareTimes(s);    // 时间值漏引号
        s = normalizeLiterals(s); // Python 的 True/False/None
        s = removeTrailingCommas(s);
        s = closeUnbalanced(s);   // 截断补全
        if (!s.equals(original)) {
            log.debug("[JsonRepair] 已尝试修复模型 JSON, 原长={} 新长={}", original.length(), s.length());
        }
        return s;
    }

    /** 去掉首尾的 ```json / ``` 围栏(模型经常无视"不要 markdown"的要求) */
    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    /**
     * 给漏引号的时间值补上引号:"endTime":17:00 -> "endTime":"17:00"
     *
     * 做法:扫描每个 "key": 之后的位置,如果紧跟着是 `数字:数字` 形式(而不是引号),
     * 就把这段裸值包进引号。只处理 TIME_KEYS 里的键,避免误伤 cost 这类数字字段。
     */
    private static String quoteBareTimes(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            // 找一个键名: "'xxx'" 后面跟冒号
            if (s.charAt(i) == '"' && i + 1 < s.length()) {
                int keyEnd = s.indexOf('"', i + 1);
                if (keyEnd > i) {
                    String key = s.substring(i + 1, keyEnd);
                    int j = keyEnd + 1;
                    // 跳过空白找冒号
                    while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                    if (j < s.length() && s.charAt(j) == ':' && isTimeKey(key)) {
                        int v = j + 1;
                        while (v < s.length() && Character.isWhitespace(s.charAt(v))) v++;
                        // 值不是引号开头,且形如 17:00 或 9:00:00
                        if (v < s.length() && s.charAt(v) != '"' && looksLikeTime(s, v)) {
                            int end = v;
                            while (end < s.length()) {
                                char c = s.charAt(end);
                                if (Character.isDigit(c) || c == ':') end++;
                                else break;
                            }
                            out.append(s, i, v);
                            out.append('"').append(s, v, end).append('"');
                            i = end;
                            continue;
                        }
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static boolean isTimeKey(String key) {
        for (String k : TIME_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /** 从 pos 开始是否形如 数字:数字 (1-2 位小时 : 2 位分钟) */
    private static boolean looksLikeTime(String s, int pos) {
        int p = pos;
        int digs = 0;
        while (p < s.length() && Character.isDigit(s.charAt(p)) && digs < 2) { p++; digs++; }
        if (digs == 0 || p >= s.length() || s.charAt(p) != ':') return false;
        p++;
        int m = 0;
        while (p < s.length() && Character.isDigit(s.charAt(p)) && m < 2) { p++; m++; }
        return m == 2;
    }

    /** 中文全角引号 -> 英文引号(模型偶尔输出中文引号) */
    private static String normalizeQuotes(String s) {
        return s.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');
    }

    /** Python 风格的真假值 -> JSON 字面量(只在冒号后的值位置替换) */
    private static String normalizeLiterals(String s) {
        return s.replace(": True", ": true").replace(":True", ":true")
                .replace(": False", ": false").replace(":False", ":false")
                .replace(": None", ": null").replace(":None", ":null");
    }

    /** 去掉 } 和 ] 前面多余的逗号(模型很爱写 {"a":1,}) */
    private static String removeTrailingCommas(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    continue;   // 这个逗号是多余的,跳过
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 补全未闭合的 { [ 和字符串引号,并去掉残留尾随逗号。
     * 只在"括号确实不平衡"时动手;正常 JSON 原样返回。
     */
    private static String closeUnbalanced(String s) {
        // 用栈记录还没闭合的括号:['{', '[', ...]
        // 补全时必须按后进先出的逆序,才能得到正确的嵌套层次(先补最内层的 ] 再补外层 })
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false, escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') stack.push(c);
            else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
            }
        }
        if (stack.isEmpty() && !inString) return s;

        String body = trimTrailingComma(s);
        StringBuilder sb = new StringBuilder(body);
        if (inString) sb.append('"');            // 被截断在字符串中间:先闭合引号
        body = trimTrailingComma(sb.toString());
        sb = new StringBuilder(body);
        while (!stack.isEmpty()) {                 // 逆序补全:栈顶是最内层
            char open = stack.pop();
            sb.append(open == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    /** 去掉末尾残留的逗号与空白 */
    private static String trimTrailingComma(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (Character.isWhitespace(c) || c == ',') end--;
            else break;
        }
        return s.substring(0, end);
    }

    /** 从模型原始文本里抠出最外层 JSON 对象(第一个 { 到最后一个 }),并做修复 */
    public static String extractAndRepair(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = stripFence(raw);
        int start = cleaned.indexOf('{');
        if (start < 0) return null;
        int end = cleaned.lastIndexOf('}');
        String candidate = (end > start) ? cleaned.substring(start, end + 1) : cleaned.substring(start);
        return repair(candidate);
    }
}
