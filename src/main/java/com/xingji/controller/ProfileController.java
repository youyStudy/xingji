package com.xingji.controller;

import com.xingji.common.Result;
import com.xingji.common.UserContext;
import com.xingji.dto.ChangePasswordDTO;
import com.xingji.dto.UpdateProfileDTO;
import com.xingji.service.UserService;
import com.xingji.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 个人中心（UC01）：读取/编辑当前登录用户资料。走 /api/user，需 JWT（不在放行名单里）。 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;

    /** 取我的资料（不含密码） */
    @GetMapping("/profile")
    public Result<UserVO> profile() {
        Integer userId = UserContext.getUserId();
        return Result.ok(userService.getProfile(userId));
    }

    /** 改我的资料：昵称/邮箱/头像/性别 */
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        Integer userId = UserContext.getUserId();
        return Result.ok(userService.updateProfile(userId, dto));
    }

    /** 改我的密码：校验原密码 → 更新新密码哈希 */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        Integer userId = UserContext.getUserId();
        userService.changePassword(userId, dto);
        return Result.ok();
    }
}
