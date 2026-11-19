package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 分享管理接口(需登录):给行程签发/撤销分享链接。真正"看"分享走 PublicShareController。 */
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    /** 为某行程创建分享链接,返回 token(前端拼成 /public/share/{token} 的访问地址) */
    @PostMapping("/{itineraryId}")
    public Result<String> create(@PathVariable Integer itineraryId) {
        Integer userId = UserContext.getUserId();
        return Result.ok(shareService.createShare(itineraryId, userId));
    }

    /** 撤销某行程的全部分享链接 */
    @DeleteMapping("/{itineraryId}")
    public Result<Void> revoke(@PathVariable Integer itineraryId) {
        Integer userId = UserContext.getUserId();
        shareService.revokeShare(itineraryId, userId);
        return Result.ok();
    }
}
