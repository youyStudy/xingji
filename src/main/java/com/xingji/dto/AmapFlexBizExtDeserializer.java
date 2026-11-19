package com.xingji.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * 高德 place 接口的 biz_ext 是"多态"字段:
 *   - extensions=all 且有数据 → {"rating":"4.8","cost":"60","open_time":"..."}
 *   - extensions=base,或该 POI 没有商业信息 → []
 * 直接映射成 BizExt 对象时,遇到空数组会抛
 *   "Cannot deserialize value of type AmapPoi$BizExt from Array value"
 * 这段解析在 doSearch 里,异常会被校验层 catch 成"地点不存在"——
 * 表现为:明明真实存在的地点,校验却全部标异常(matched=0)。
 * 这里把空数组/null 吃成 null,对象照常解析。
 */
public class AmapFlexBizExtDeserializer extends JsonDeserializer<AmapPoi.BizExt> {

    @Override
    public AmapPoi.BizExt deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.START_ARRAY) {
            p.skipChildren();      // []（或异常形状的数组）一律当成"没有商业信息"
            return null;
        }
        if (t == JsonToken.VALUE_NULL) return null;
        return p.readValueAs(AmapPoi.BizExt.class);
    }
}
