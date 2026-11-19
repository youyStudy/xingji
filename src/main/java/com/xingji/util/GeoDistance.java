package com.xingji.util;

/**
 * 地理距离计算(纯公式,不依赖任何地图 SDK)。
 *
 * 用途:判断餐厅离当天景点有多远——模型经常把城东的餐厅排到城西的景点后面,
 * 用坐标算一下真实距离,超过阈值就让服务端兜底改掉,而不是全指望模型自觉。
 */
public final class GeoDistance {

    private GeoDistance() {}

    private static final double EARTH_RADIUS_M = 6371000.0;

    /**
     * 两点的球面距离(米)。任一参数为 null 返回 -1(表示未知,调用方应跳过判断)。
     * 用 Haversine 公式,城市内尺度误差可忽略。
     */
    public static double meters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return -1;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
