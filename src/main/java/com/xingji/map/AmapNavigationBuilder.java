package com.xingji.map;

import com.xingji.pojo.Place;
import com.xingji.util.GeoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德导航链接生成器(不依赖任何外部服务,纯字符串拼装)。
 *
 * 只做一件事:把行程里的真实坐标,拼成用户点一下就能跳进高德 App/网页的 URL。
 * 用高德官方的 URI API(uri.amap.com),不调接口、不消耗配额、不经过大模型——
 * 所以这里产出的东西是确定性的,不会"编"。
 *
 * 坐标系:数据库 Place 存的是 WGS-84(见 Place 注释),而高德 URI 只认 GCJ-02,
 * 所以这里统一先转回 GCJ-02 再拼(见 GeoUtil.wgs84ToGcj02)。这是导航准不准的关键。
 */
@Slf4j
@Component
public class AmapNavigationBuilder {

    private static final String BASE = "https://uri.amap.com";

    /** 高德 URI 支持的出行方式 */
    public static final String MODE_WALK = "walk";
    public static final String MODE_CAR = "car";
    public static final String MODE_BUS = "bus";
    public static final String MODE_RIDE = "ride";

    /**
     * 把 WGS-84 坐标格式化成高德 URI 要的 "经度,纬度"(GCJ-02,保留 6 位小数≈0.1 米精度)。
     * 任一坐标缺失返回 null——没有坐标就不拼链接,绝不用地点名去"猜"一个位置。
     */
    public String positionOf(BigDecimal lng, BigDecimal lat) {
        if (lng == null || lat == null) return null;
        double[] gcj = GeoUtil.wgs84ToGcj02(lng.doubleValue(), lat.doubleValue());
        return String.format("%.6f,%.6f", gcj[0], gcj[1]);
    }

    /** Place → 高德坐标串;没有坐标返回 null */
    public String positionOf(Place place) {
        if (place == null) return null;
        return positionOf(place.getLongitude(), place.getLatitude());
    }

    /**
     * 单点标记链接:点开高德地图,直接定位到这个地点并显示名称。
     * 形如 https://uri.amap.com/marker?position=116.473195,39.993253&name=天安门&src=行迹&coordinate=gaode&callnative=1
     *
     * @param position 由 positionOf() 产出的 GCJ-02 坐标串;为 null 时返回 null
     * @param name     地点名(会做 URL 编码)
     */
    public String markerUrl(String position, String name) {
        if (position == null || position.isBlank()) return null;
        StringBuilder sb = new StringBuilder(BASE).append("/marker");
        sb.append("?position=").append(position);
        if (name != null && !name.isBlank()) {
            sb.append("&name=").append(encode(name));
        }
        sb.append("&coordinate=gaode");   // 明确告诉高德:我传的就是 GCJ-02
        sb.append("&callnative=1");       // 装了高德 App 就直接唤起 App
        sb.append("&src=").append(encode("行迹"));
        return sb.toString();
    }

    /** Place → 单点标记链接 */
    public String markerUrl(Place place) {
        if (place == null) return null;
        return markerUrl(positionOf(place), place.getName());
    }

    /**
     * 路线导航链接:从 A 到 B,指定出行方式。
     * 形如 https://uri.amap.com/navigation?from=116.4,39.9,起点&to=116.5,39.9,终点&mode=car&callnative=1
     *
     * @param fromPosition positionOf() 产出的 GCJ-02 坐标串
     * @param fromName     起点名称(可空)
     * @param toPosition   终点坐标串
     * @param toName       终点名称(可空)
     * @param mode         walk / car / bus / ride,传 null 默认 car
     */
    public String routeUrl(String fromPosition, String fromName,
                           String toPosition, String toName, String mode) {
        if (fromPosition == null || toPosition == null) return null;
        StringBuilder sb = new StringBuilder(BASE).append("/navigation");
        sb.append("?from=").append(fromPosition);
        if (fromName != null && !fromName.isBlank()) sb.append(",").append(encode(fromName));
        sb.append("&to=").append(toPosition);
        if (toName != null && !toName.isBlank()) sb.append(",").append(encode(toName));
        sb.append("&mode=").append(isValidMode(mode) ? mode : MODE_CAR);
        sb.append("&coordinate=gaode");
        sb.append("&callnative=1");
        sb.append("&src=").append(encode("行迹"));
        return sb.toString();
    }

    /**
     * 多点串联导航(一天走完一条线):
     * 高德 URI 的 navigation 接口只支持 from/to 两点,所以多点用"分段"表达——
     * 返回相邻两点之间的路线链接列表,前端按天依次展示,用户逐段跟随即可。
     *
     * 只有相邻两个点都有坐标时才产生一段;中间缺坐标的段落会被跳过(不会用无坐标点瞎连)。
     *
     * @param points 当天按顺序排好的地点(已按 seq 排序)
     * @return 分段路线列表,顺序与 points 一致;无法生成时返回空列表
     */
    public List<RouteSegmentLink> dayRouteLinks(List<Place> points, String mode) {
        List<RouteSegmentLink> out = new ArrayList<>();
        if (points == null || points.size() < 2) return out;
        for (int i = 0; i + 1 < points.size(); i++) {
            Place a = points.get(i);
            Place b = points.get(i + 1);
            String from = positionOf(a);
            String to = positionOf(b);
            if (from == null || to == null) continue;   // 缺坐标就不连,避免误导
            RouteSegmentLink seg = new RouteSegmentLink();
            seg.setSeq(i + 1);
            seg.setFromName(a.getName());
            seg.setToName(b.getName());
            seg.setUrl(routeUrl(from, a.getName(), to, b.getName(), mode));
            out.add(seg);
        }
        return out;
    }

    private boolean isValidMode(String mode) {
        return MODE_WALK.equals(mode) || MODE_CAR.equals(mode)
                || MODE_BUS.equals(mode) || MODE_RIDE.equals(mode);
    }

    /**
     * URL 编码。手写而不用 URLEncoder:后者会把空格编成 '+'、把逗号也编掉,
     * 而高德 URI 明确要求 params 里保留 "," 作为分隔符。
     */
    private String encode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /** 一段相邻两点之间的导航(dayRouteLinks 的返回值) */
    @lombok.Data
    public static class RouteSegmentLink {
        private int seq;
        private String fromName;
        private String toName;
        private String url;
    }
}
