package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.service.ShareService;
import com.xingji.vo.ItineraryDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开分享访问接口(免登录)。路径以 /api/public 开头,已在 WebMvcConfig 放行拦截器。
 * 访问者凭 token 看被分享的行程详情;token 无效/已撤销则 404。
 */
@RestController
@RequestMapping("/api/public/share")
@RequiredArgsConstructor
public class PublicShareController {

    private final ShareService shareService;

    @GetMapping("/{token}")
    public Result<ItineraryDetailVO> view(@PathVariable String token) {
        return Result.ok(shareService.viewByToken(token));
    }
}
