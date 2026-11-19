package com.xingji.Interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.util.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 登录鉴权拦截器：凡走到这里的请求都必须带合法 token，验过就把 userId 放进 UserContext。 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;   // Spring Boot 已自带一个 Bean，直接拿来序列化

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) throws Exception {
        // 1. 放行跨域预检请求，OPTIONS 不带 token  `OPTIONS`是HTTP的"探测/问路" 方法
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 2. 取 Authorization 头，剥掉 "Bearer " 前缀
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            write401(response);
            return false;
        }
        String token = header.substring(7).trim();

        // 3. 验票：解析出 userId 塞进 UserContext；非法/过期会抛异常
        try {
            Integer userId = jwtTokenProvider.parseUserId(token);
            UserContext.setUserId(userId);
            return true;
        } catch (Exception e) {
            write401(response);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response, Object handler, Exception ex) {
        // 4. 无论成功失败，请求收尾清理 ThreadLocal，防串号
        UserContext.clear();
    }

    /** 拦截器不在 controller 链路里，@RestControllerAdvice 接不住，只能手动写 401。 */
    private void write401(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<Void> body = Result.fail(401, "未登录或登录已失效");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}