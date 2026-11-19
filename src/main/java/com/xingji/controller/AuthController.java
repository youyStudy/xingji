package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 第三方登录接口（UC01 扩展）。/api/auth/oauth/** 已在 excludePathPatterns("/api/auth/**") 下放行。 */
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;


    /** mock 登录：跳过真跳转，直接拿固定 openId 走「查绑定→自动注册→签 JWT」全链路。 */
    @PostMapping("/{provider}")
    public Result<Map<String, String>> mockLogin(@PathVariable String provider) {
        String token = authService.oauthLogin(provider);
        return Result.ok(Map.of("token", token));
    }

    /** 第一步：前端点"QQ登录"先调这个，拿到授权页 URL，然后 window.location 跳过去。 */
    @GetMapping("/{provider}/authUrl")
    public Result<Map<String, String>> getAuthUrl(@PathVariable String provider) {
        // TODO: 真授权时调 JustAuth 生成 state + 授权URL，现在 mock 模式不走到这里
        throw new UnsupportedOperationException("mock 模式请用 POST /api/auth/oauth/{provider}");
    }

    /** 第二步：用户在 QQ/微信页面点同意后，平台带着 code 回调这个接口。 */
    @GetMapping("/{provider}/callback")
    public Result<Map<String, String>> callback(@PathVariable String provider,
                                                @RequestParam String code,
                                                @RequestParam String state) {
        // TODO: 真授权时：拿 code 换 AuthUser → 翻译成 ThirdPartyUserInfo → oauthLogin 签 JWT
        throw new UnsupportedOperationException("mock 模式请用 POST /api/auth/oauth/{provider}");
    }
}