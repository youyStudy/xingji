package com.xingji.dto;

import lombok.Data;

import java.util.List;

/**
 * 高德 v3 路径规划（driving / walking）响应体的最小映射。
 * 只映射我们要的：status + route.paths[0].{distance,duration,steps[].polyline}。
 * 其余字段 Jackson 忽略。
 */
@Data
public class AmapDirectionResponse {

    private String status;    // "1" 成功
    private String info;
    private String infocode;
    private Route route;

    @Data
    public static class Route {
        private List<Path> paths;
    }

    @Data
    public static class Path {
        private String distance; // 米（字符串，高德都返字符串）
        private String duration; // 秒
        private List<Step> steps;
    }

    @Data
    public static class Step {
        private String polyline; // "lng,lat;lng,lat;..."
    }
}
