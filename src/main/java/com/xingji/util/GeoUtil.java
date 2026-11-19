package com.xingji.util;

/**
 * 坐标纠偏工具：高德给的是 GCJ-02（火星坐标系），而地球贴图按 WGS-84（GPS 真实经纬度）排布。
 * 直接用高德坐标打点，整体会偏移几百米。这里做 GCJ-02 → WGS-84 的粗纠偏（减掉偏移量），
 * 让城市点/景点落位更准。境外高德本来就不加偏，直接原样返回。
 *
 * 算法是业界通用的"一次性减去偏移"近似版，误差米级，足够地球这种全球尺度展示用。
 */
public final class GeoUtil {

    private GeoUtil() {}   // 纯静态工具类，禁止实例化

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;                        // Krasovsky 长半轴
    private static final double EE = 0.00669342162296594323;          // 第一偏心率平方

    /** 大致判断是否在中国范围外（外边不加偏） */
    public static boolean outOfChina(double lng, double lat) {
        return !(lng >= 72.004 && lng <= 137.8347 && lat >= 0.8293 && lat <= 55.8271);
    }

    /**
     * GCJ-02 → WGS-84。入参高德经纬度(lng, lat)，返回纠偏后的 [lng, lat]。
     */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        return new double[]{lng - dLng, lat - dLat};
    }

    /**
     * WGS-84 → GCJ-02。
     *
     * 为什么需要反向转换:数据库里存的是 WGS-84(doSearch 把高德坐标纠偏后存进来的),
     * 但高德 URI API(uri.amap.com)只认 GCJ-02。拿 WGS-84 去拼导航链接会偏几百米,
     * 用户点开导航会被导到错误的位置。拼导航链接前必须用它转回来。
     *
     * 与 gcj02ToWgs84 互为逆运算,用同样的"一次性加偏移"近似,误差米级,
     * 对导航起点/终点定位足够(城市内街道级)。
     */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
