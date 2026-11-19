package com.xingji.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xingji.cache.AmapCache;
import com.xingji.config.AmapProperties;
import com.xingji.dto.AmapDistrict;
import com.xingji.dto.AmapDirectionResponse;
import com.xingji.dto.AmapPoi;
import com.xingji.dto.AmapSearchResponse;
import com.xingji.dto.AmapWeatherResponse;
import com.xingji.pojo.Place;
import com.xingji.util.GeoUtil;
import com.xingji.vo.CityVO;
import com.xingji.vo.SpotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AmapClient {

    // RestTemplate是Spring提供的HTTP客户端工具类 作用就是在后端 Java 代码里发起 HTTP 请求（GET、POST）调用外部服务（这里就是高德服务器）
    private final RestTemplate restTemplate;
    private final AmapProperties props;
    private final AmapCache amapCache;

    private static final TypeReference<List<Place>> POI_LIST = new TypeReference<>() { };
    private static final TypeReference<RouteResult> ROUTE = new TypeReference<>() { };

    /**
     * 按城市浏览"风景名胜"类 POI(地图浏览功能用)。
     */
    public List<Place> searchPoi(String city) {
        return doSearch("风景名胜", city);
    }

    /**
     * 按具体地点名 + 城市精确搜索(校验阶段用):
     * 拿 AI 生成的 placeName 去高德查,查到说明这地方真实存在,查不到就该标异常。
     */
    public List<Place> searchByName(String keyword, String city) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return doSearch(keyword, city);
        // 校验会反复拿同一个景点名回查,POI 24h 内基本不变,缓存省配额也省一次网络往返;
        // 查不到的结果只留 10 分钟,避免把一次高德抖动固化成一整天"景点不存在"
        String key = "amap:poi:" + (city == null || city.isBlank() ? "-" : city.trim()) + ":" + kw;
        List<Place> cached = amapCache.get(key, POI_LIST);
        if (cached != null) return cached;
        List<Place> found = doSearch(keyword, city);
        amapCache.put(key, found, found.isEmpty() ? Duration.ofMinutes(10) : Duration.ofHours(24));
        return found;
    }

    /**
     * 查一个"区域/地标"的真实位置(住宿区域推荐用)。
     *
     * 住宿推荐的是区域不是酒店,所以拿"春熙路""宽窄巷子"这类地名去高德查,
     * 命中的头一条就是真实地标(实测:春熙路→春熙路步行街,宽窄巷子→宽窄巷子景区),
     * 坐标准确,可用于地图打点和"离景点多远"的判断。
     * 查不到返回 null —— 调用方应把该区域降级为纯文字,不画假点。
     */
    public Place resolveArea(String areaName, String city) {
        if (areaName == null || areaName.isBlank()) return null;
        try {
            List<Place> found = doSearch(areaName.trim(), city);
            if (found == null || found.isEmpty()) return null;
            // 优先取类型是"地标/商业街/广场"的,比拿到一个同名小区更贴近用户认知
            for (Place p : found) {
                if (p.getCategory() != null
                        && (p.getCategory().contains("商业街") || p.getCategory().contains("广场")
                        || p.getCategory().contains("风景名胜"))) {
                    return p;
                }
            }
            return found.get(0);
        } catch (Exception e) {
            return null;   // fail-safe:区域查不到就不给坐标,不影响主流程
        }
    }

    /**
     * 地球首页"景点浏览"用：拉城市的真实景点，并带出评分/门票/开放时间/图片。
     * 关键区别：这里用 extensions=all，高德才会回 biz_ext(rating/cost/open_time) 和 photos。
     * 校验链路(searchPoi/searchByName)仍用 base，不需要这些富字段，别混用。
     */
    /**
     * 按城市拉真实景点(带评分/门票/开放时间/图片)。
     *
     * ⚠️ 必须用"两路合并"检索,单路都会漏掉重要景点。实测(北海):
     *   · keywords=风景名胜   → 能拿到北海老城、金滩、侨港,但**拿不到涠洲岛**
     *     (关键词检索按文本相关度排,涠洲岛是离岸 21 海里的海岛,权重排不进前 50)
     *   · types=110000(风景名胜分类) → 能拿到涠洲岛、石螺口海滩、涠洲灯塔,但**拿不到北海老城**
     *     (老城的 POI 分类挂在"特色商业街",不属于 110000)
     * 两路合并去重才能覆盖完整。之前只用关键词,导致"北海最出名的涠洲岛"在候选池里根本不存在,
     * 生成的行程永远排不到它。
     *
     * 另外要做噪音过滤:types=110000 会返回一堆"XX庙""XX寺""XX广场"(评分 3.2~3.7),
     * 这些不是游客要去的地方,混进卡片和候选池都是噪音。
     */
    public List<SpotVO> browseSpots(String city) {
        java.util.Map<String, SpotVO> byId = new java.util.LinkedHashMap<>();
        fetchSpotsInto(byId, city, true);    // 第一路:关键词
        fetchSpotsInto(byId, city, false);   // 第二路:分类
        List<SpotVO> out = new java.util.ArrayList<>();
        for (SpotVO s : byId.values()) {
            if (isSpotNoise(s)) continue;
            out.add(s);
        }
        // 按评分从高到低:用户问"推荐景点"时要的是"最值得去的",不是高德返回顺序
        out.sort((a, b) -> Double.compare(parseRatingSafe(b.getRating()), parseRatingSafe(a.getRating())));
        return out;
    }

    /** 执行一路检索并并入结果表(按高德 POI id 去重) */
    private void fetchSpotsInto(java.util.Map<String, SpotVO> out, String city, boolean byKeyword) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl() + props.getPoiSearchPath())
                .queryParam("key", props.getKey())
                .queryParam("offset", 50)
                .queryParam("page", 1)
                .queryParam("extensions", "all")     // 富字段全靠它
                .queryParam("sortrule", "weight")
                .queryParam("city", city);
        if (byKeyword) {
            b.queryParam("keywords", "风景名胜");
        } else {
            b.queryParam("types", "110000");     // 风景名胜分类码
        }

        AmapSearchResponse resp;
        try {
            resp = restTemplate.getForObject(b.build().toUriString(), AmapSearchResponse.class);
        } catch (Exception e) {
            // 单路失败不影响另一路,也不让整个"看景点"报错
            return;
        }
        if (resp == null || !"1".equals(resp.getStatus()) || resp.getPois() == null) return;

        for (AmapPoi poi : resp.getPois()) {
            if (poi.getLocation() == null || poi.getLocation().isEmpty()) continue;
            String id = poi.getId();
            if (id == null || id.isBlank() || out.containsKey(id)) continue;
            String[] lnglat = poi.getLocation().split(",");
            double[] wgs = GeoUtil.gcj02ToWgs84(
                    Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));

            SpotVO s = new SpotVO();
            s.setAmapPoiId(id);
            s.setName(poi.getName());
            s.setCategory(poi.getType());
            s.setAddress(poi.getAddress());
            s.setCity(poi.getCityname());
            s.setTel(poi.getTel());
            s.setLng(BigDecimal.valueOf(wgs[0]));
            s.setLat(BigDecimal.valueOf(wgs[1]));
            AmapPoi.BizExt ext = poi.getBizExt();
            if (ext != null) {
                s.setRating(ext.getRating());
                s.setCost(ext.getCost());
                s.setOpenTime(ext.getOpenTime());
            }
            if (poi.getPhotos() != null && !poi.getPhotos().isEmpty()) {
                s.setPhoto(poi.getPhotos().get(0).getUrl());
            }
            out.put(id, s);
        }
    }

    /**
     * 明显不是"游客会去"的 POI:寺庙道观/教堂/城市广场/公园,以及公司、售票处、停车场这类场所。
     * 只按类型和名字判断,不做语义猜测 —— 宁可漏掉几个小众点,也不要让卡片里全是"XX庙"。
     */
    private boolean isSpotNoise(SpotVO s) {
        if (s == null || s.getName() == null || s.getName().isBlank()) return true;
        String type = s.getCategory() == null ? "" : s.getCategory();
        if (type.contains("寺庙道观") || type.contains("教堂") || type.contains("回教寺")
                || type.contains("城市广场") || type.contains("公园;")) {
            return true;
        }
        String name = s.getName();
        for (String w : new String[]{"公司", "售票", "停车场", "服务区", "管理处",
                "办事处", "纪念亭", "学校", "门市", "办事处"}) {
            if (name.contains(w)) return true;
        }
        return false;
    }

    /** 评分字符串转 double;解析不出返回 0(排到最后) */
    private double parseRatingSafe(String r) {
        if (r == null || r.isBlank()) return 0;
        try {
            return Double.parseDouble(r.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 真正拼 URL、发请求、解析高德 JSON 的公共逻辑,两个入口共用 */
    private List<Place> doSearch(String keywords, String city) {
        // UriComponentsBuilder自动帮你判断该写 ? 还是 &
        UriComponentsBuilder builder = UriComponentsBuilder
                // 起点 传入基础网址
                .fromHttpUrl(props.getBaseUrl() + props.getPoiSearchPath())
                // 往 URL后面拼接?key=value参数
                .queryParam("keywords", keywords)
                .queryParam("key", props.getKey())
                .queryParam("offset", 20)
                .queryParam("page", 1)
                .queryParam("extensions", "base");
        // city 可空:校验阶段只拿地点名全国搜,没限定城市就不带这个参数
        if (city != null && !city.isBlank()) {
            builder.queryParam("city", city);
        }
        String url = builder.build().toUriString();

        // get:代表发送GET请求
        // ForObject: 拿到响应之后,自动把返回的 JSON 字符串,转成一个 Java对象，直接返回给你。
        AmapSearchResponse resp = restTemplate.getForObject(url, AmapSearchResponse.class);

        if (resp == null || !"1".equals(resp.getStatus())) {
            String info = resp == null ? "无响应" : resp.getInfo() + "/" + resp.getInfocode();
            throw new RuntimeException("高德POI搜索失败: " + info);
        }

        List<Place> result = new ArrayList<>();

        for (AmapPoi poi : resp.getPois()) {
            if (poi.getLocation() == null || poi.getLocation().isEmpty()) continue;
            String[] lnglat = poi.getLocation().split(",");
            // 存 WGS-84(地球贴图按 WGS-84 排布,直接打高德坐标会整体偏几百米)。
            // 要回高德地图/导航时由 AmapNavigationBuilder 转回 GCJ-02,别在这里改。
            double[] wgs = GeoUtil.gcj02ToWgs84(
                    Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
            Place p = new Place();
            p.setAmapPoiId(poi.getId());
            p.setName(poi.getName());
            p.setCategory(poi.getType());
            p.setAddress(poi.getAddress());
            p.setCity(poi.getCityname());
            p.setLongitude(BigDecimal.valueOf(wgs[0]));
            p.setLatitude(BigDecimal.valueOf(wgs[1]));
            result.add(p);
        }
        return result;
    }

    /** 直辖市 / 特别行政区的 adcode：它们在树里是 province 级，永远不会以 "city" 级出现，手动兜底 */
    private static final java.util.Set<String> MUNICIPALITY_ADCODE =
            java.util.Set.of("110000", "120000", "310000", "500000", "810000", "820000");

    /**
     * 拉「全国地级市清单」给地球打点用：调高德行政区划接口，以"中国"为根往下取两级
     * （省 → 市），再把整棵树里 level=="city" 的节点抽平。约 340 个城，含冷门城。
     * 这解决"地球只有热门城市"的根因——城市来自真实行政区划，不是我手写的。
     */
    public List<CityVO> listCities() {
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl() + "/v3/config/district")
                .queryParam("keywords", "中国")
                .queryParam("subdistrict", "2")   // 向下取 2 级：省 + 地级市
                .queryParam("extensions", "base") // 只要名字/adcode/center，省流量
                .queryParam("key", props.getKey())
                .build().toUriString();

        AmapDistrict resp = restTemplate.getForObject(url, AmapDistrict.class);
        if (resp == null || !"1".equals(resp.getStatus())) {
            String info = resp == null ? "无响应" : resp.getInfo() + "/" + resp.getInfocode();
            throw new RuntimeException("高德行政区划查询失败: " + info);
        }

        List<CityVO> cities = new ArrayList<>();
        collectCities(resp.getDistricts(), cities);
        return cities;
    }

    /** 递归下钻：碰到 city 级就收；碰到直辖市/特区的 province 级也收；其余继续往孩子钻 */
    private void collectCities(List<AmapDistrict.District> list, List<CityVO> out) {
        if (list == null) return;
        for (AmapDistrict.District d : list) {
            boolean isCity = "city".equals(d.getLevel());
            boolean isMunicipality = "province".equals(d.getLevel())
                    && d.getAdcode() != null && MUNICIPALITY_ADCODE.contains(d.getAdcode());
            if (isCity || isMunicipality) {
                out.add(toCity(d));
            } else {
                collectCities(d.getDistricts(), out);
            }
        }
    }

    /** District → CityVO：把 center "经度,纬度" 拆成两个 BigDecimal */
    private CityVO toCity(AmapDistrict.District d) {
        CityVO c = new CityVO();
        c.setName(d.getName());
        c.setAdcode(d.getAdcode());
        c.setCenter(d.getCenter());
        if (d.getCenter() != null && d.getCenter().contains(",")) {
            String[] lnglat = d.getCenter().split(",");
            double[] wgs = GeoUtil.gcj02ToWgs84(
                    Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
            c.setLng(BigDecimal.valueOf(wgs[0]));
            c.setLat(BigDecimal.valueOf(wgs[1]));
        }
        return c;
    }

    // ===================== 天气(编排器的第 2 个数据源) =====================

    /**
     * 给行程生成 prompt 用的"天气简报":返回一段紧凑中文，形如
     * "10月19日 多云 20~26℃；10月20日 多云转阴 18~30℃"。
     * 只保留日期落在 [startDate, startDate+days-1]、且在高德预报范围内的天
     * (高德 extensions=all 预报约覆盖今天起 4 天；出发日太远就一条都匹配不上 → 返回空串)。
     * <p>
     * 铁律:天气是锦上添花。任何环节(adcode 解析 / 请求 / 解析 / 日期)出问题,一律返回空串,
     * 绝不抛异常、绝不拖垮整条生成链路。
     */
    public String weatherBriefForPrompt(String city, LocalDate startDate, Integer days) {
        if (city == null || city.isBlank() || startDate == null || days == null || days <= 0) {
            return "";
        }
        try {
            String adcode = resolveAdcode(city);
            if (adcode == null || adcode.isBlank()) return "";

            String url = UriComponentsBuilder
                    .fromHttpUrl(props.getBaseUrl() + props.getWeatherPath())
                    .queryParam("city", adcode)        // 天气接口只认 adcode，不认城市名
                    .queryParam("extensions", "all")   // all=逐天预报；base=实时(无逐天)
                    .queryParam("key", props.getKey())
                    .build().toUriString();

            AmapWeatherResponse resp = restTemplate.getForObject(url, AmapWeatherResponse.class);
            if (resp == null || !"1".equals(resp.getStatus())
                    || resp.getForecasts() == null || resp.getForecasts().isEmpty()) {
                return "";
            }
            List<AmapWeatherResponse.Cast> casts = resp.getForecasts().get(0).getCasts();
            if (casts == null || casts.isEmpty()) return "";

            LocalDate endDate = startDate.plusDays(days - 1L);
            StringBuilder sb = new StringBuilder();
            for (AmapWeatherResponse.Cast c : casts) {
                LocalDate d = parseDate(c.getDate());
                if (d == null || d.isBefore(startDate) || d.isAfter(endDate)) continue; // 只留行程区间内的天
                if (sb.length() > 0) sb.append("；");
                sb.append(formatMonthDay(d)).append(" ").append(condition(c));
                String temp = tempRange(c);
                if (temp != null) sb.append(" ").append(temp);
            }
            return sb.toString();  // 全没匹配上即空串(出发日超出预报范围等)
        } catch (Exception e) {
            return "";
        }
    }

    /** 城市名 → adcode(高德天气接口只认 adcode)。复用行政区划接口,只取命中的第一个;查不到返回 null,绝不抛。 */
    private String resolveAdcode(String city) {
        if (city == null || city.isBlank()) return null;
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(props.getBaseUrl() + "/v3/config/district")
                    .queryParam("keywords", city)
                    .queryParam("subdistrict", "0")   // 只取它自己这一层，不往下钻，省流量
                    .queryParam("extensions", "base")
                    .queryParam("key", props.getKey())
                    .build().toUriString();
            AmapDistrict resp = restTemplate.getForObject(url, AmapDistrict.class);
            if (resp == null || !"1".equals(resp.getStatus())
                    || resp.getDistricts() == null || resp.getDistricts().isEmpty()) {
                return null;
            }
            return resp.getDistricts().get(0).getAdcode();
        } catch (Exception e) {
            return null;   // fail-safe:adcode 解析失败 → 上层天气直接跳过
        }
    }

    // —— 天气简报的几个小拼装工具,抽出来保持上面主流程可读 ——

    /** yyyy-MM-dd 转 LocalDate;格式错/空返回 null */
    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** LocalDate → "10月19日" */
    private String formatMonthDay(LocalDate d) {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    /** 白天/夜间天气:相同就一个词,不同拼"X转Y"(如"多云转小雨") */
    private String condition(AmapWeatherResponse.Cast c) {
        String day = c.getDayweather();
        String night = c.getNightweather();
        if (day == null || day.isBlank()) return night == null ? "" : night;
        if (night == null || night.isBlank() || night.equals(day)) return day;
        return day + "转" + night;
    }

    /** 温度区间 low~high℃(daytemp 白天、nighttemp 夜间,取小~大更直观);都缺则 null */
    private String tempRange(AmapWeatherResponse.Cast c) {
        Integer day = parseIntSafe(c.getDaytemp());
        Integer night = parseIntSafe(c.getNighttemp());
        if (day == null && night == null) return null;
        if (day == null) return night + "℃";
        if (night == null) return day + "℃";
        int low = Math.min(day, night);
        int high = Math.max(day, night);
        return low + "~" + high + "℃";
    }

    /** 字符串转 int;非数字或空返回 null */
    private Integer parseIntSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 路径规划(REQ-012 相邻节点真实路线) =====================

    /**
     * 调高德 v3 路径规划,取第一条方案的 距离/耗时/折线。
     * origin/dest 必须是 GCJ-02 的 "经度,纬度"(用 AmapNavigationBuilder.positionOf 产出)。
     * mode: "walk"→walking,其余→driving。
     * ★ fail-safe:任何异常/非成功/无方案都返回 null,调用方据此降级(只留导航链接、不写距离)。
     */
    public RouteResult direction(String origin, String dest, String mode) {
        if (origin == null || origin.isBlank() || dest == null || dest.isBlank()) return null;
        String seg = "walk".equals(mode) ? "walking" : "driving";
        // 同一对起点终点的路线在 7 天内不会变,重算整条行程时可直接命中,不用再烧高德配额
        String cacheKey = "amap:route:" + seg + ":" + origin + ":" + dest;
        RouteResult cached = amapCache.get(cacheKey, ROUTE);
        if (cached != null) return cached;
        String path = props.getDirectionPath();
        if (path == null || path.isBlank()) path = "/v3/direction/";
        String url = UriComponentsBuilder
                .fromHttpUrl(props.getBaseUrl() + path + seg)
                .queryParam("origin", origin)
                .queryParam("destination", dest)
                .queryParam("strategy", 0)
                .queryParam("key", props.getKey())
                .build().toUriString();
        // 高德偶发的连接超时或限流会让单段路线静默缺失(前端只剩导航链接),退避 1s 重试一次;
        // 仍失败则保持 fail-safe,由调用方降级,不中断整次刷新。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AmapDirectionResponse resp = restTemplate.getForObject(url, AmapDirectionResponse.class);
                if (resp != null && "1".equals(resp.getStatus()) && resp.getRoute() != null
                        && resp.getRoute().getPaths() != null && !resp.getRoute().getPaths().isEmpty()) {
                    AmapDirectionResponse.Path p = resp.getRoute().getPaths().get(0);
                    RouteResult r = new RouteResult();
                    r.setDistance(parseIntSafe(p.getDistance()));
                    r.setDuration(parseIntSafe(p.getDuration()));
                    StringBuilder pl = new StringBuilder();
                    if (p.getSteps() != null) {
                        for (AmapDirectionResponse.Step s : p.getSteps()) {
                            if (s.getPolyline() != null && !s.getPolyline().isBlank()) {
                                if (pl.length() > 0) pl.append(";");
                                pl.append(s.getPolyline());
                            }
                        }
                    }
                    r.setPolyline(pl.length() > 0 ? pl.toString() : null);
                    amapCache.put(cacheKey, r, Duration.ofDays(7));
                    return r;
                }
            } catch (Exception e) {
                // 网络异常:落到下方的退避重试
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;   // 路径规划失败绝不拖垮主流程
    }
}

/*
* AmapClient（外部调用封装） — 为什么单独拎出来、不写在 service 里？ 它是「专门跟高德说话的那个人」。
* 把「怎么发 HTTP、拼 URL、解析高德 JSON、处理高德报错」这些又脏又专的活全关在这一个类里。
* 好处：service 里就干净地写 List<Place> places = amapClient.searchPoi("西湖","杭州")，完全不用知道高德长什么样、也不用知道 HTTP。
* 将来哪天换地图平台，只改这个类，业务层一行不动——这就是我之前说的「解耦」的落点。这也是为什么它是 AmapClient 而不是散在 service 里的一堆 RestTemplate 调用。
*
* */