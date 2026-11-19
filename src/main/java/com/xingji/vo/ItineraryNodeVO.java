package com.xingji.vo;

import com.xingji.pojo.ItineraryNode;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 行程节点(带地图能力的输出 VO)。
 *
 * 为什么要单独包一层、不直接吐 ItineraryNode:
 *   ItineraryNode 只有 placeId 外键,前端拿不到坐标,只能拿 placeName 再去地理编码猜位置
 *   —— 猜错就会把点画到别处。这里把核验回填的真实坐标和拼好的高德导航链接一并带出去,
 *   前端"拿到就能用",不用再猜。
 *
 * 坐标是 GCJ-02(已从库里的 WGS-84 转回来),可直接用于高德地图/导航。
 * 没有经过高德核验的节点(餐饮/住宿/交通 这类 AI 建议项,或核验失败的景点)坐标为 null、
 * navUrl 也为 null —— 前端应显示"未核验",而不是画一个假点。
 */
@Data
public class ItineraryNodeVO {

    /** 原始节点字段(前端编辑表单仍按它读写,保持兼容) */
    private ItineraryNode node;

    // ===== 以下为新增的地图能力字段 =====

    /** GCJ-02 经度;null=该节点没有核验到真实坐标 */
    private BigDecimal lng;

    /** GCJ-02 纬度;null=同上 */
    private BigDecimal lat;

    /** 高德单点标记链接:点开直接在地图上定位到这个地点;null=未核验 */
    private String navUrl;

    /** 是否具备定位能力(= 有真实坐标)。前端据此决定是否显示"导航"按钮 */
    private boolean located;

    /** 未定位的原因,给用户看的:如"AI 建议项，未做地图核验" */
    private String locationNote;
}
