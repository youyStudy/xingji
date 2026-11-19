package com.xingji.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德 place 接口在 extensions=all 时，tel / address / cityname 这类字段是「多态」的：
 * 单个值时是字符串，多个值时直接给你包成 JSON 数组，没有值时是空数组 []。
 * 若 DTO 把它们声明成 String，遇到数组就抛
 *   "Cannot deserialize value of type java.lang.String from Array value"
 * —— 表现为「有的城市能查、有的城市一查就崩」。
 *
 * 这个反序列化器同时接受标量和数组：数组就把非空元素用「 / 」拼成一个字符串，
 * 空数组返回 null，标量原样返回。给这些字段加
 *   @JsonDeserialize(using = AmapFlexStringDeserializer.class)
 * 即可，不影响别处的正常 String 解析。
 */
public class AmapFlexStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_ARRAY) {
            List<String> parts = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_NULL) continue;
                String v = p.getValueAsString();
                if (v != null && !v.trim().isEmpty()) parts.add(v.trim());
            }
            return parts.isEmpty() ? null : String.join(" / ", parts);
        }
        if (t == JsonToken.VALUE_NULL) return null;
        // 标量（字符串/数字）原样取
        String v = p.getValueAsString();
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
