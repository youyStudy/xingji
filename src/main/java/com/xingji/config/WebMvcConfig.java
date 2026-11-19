package com.xingji.config;

import com.xingji.Interceptor.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 把鉴权拦截器挂上，并配好拦截/放行路径 + 跨域。 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;   // 注入 Spring 管理的实例，绝不 new
    private final MediaProperties mediaProps;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")                // 凡 /api 开头都验票
                .excludePathPatterns("/api/auth/**",       // 注册/登录放行，避免鸡生蛋
                        "/api/public/**",                  // 公开分享查看免登录
                        "/api/place/cities",               // 地球免登录：全量城市点
                        "/api/place/recommend",            // 地球免登录：按城市看景点
                        "/api/place/recommend/smart",      // 地球免登录：AI 智能推荐选点
                        "/api/place/inspiration");         // 地球免登录：AI 二创种草文案
    }

    /** 开发期跨域：Vite(5173) 调后端(8080) 属于跨域。生产用网关/Nginx 时可去掉。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 把 /uploads/** 这个URL前缀映射到磁盘上的 uploadPath 目录
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + mediaProps.getUploadPath() + "/");
    }


}