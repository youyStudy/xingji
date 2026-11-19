package com.xingji.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 编辑个人资料入参：只允许改昵称/邮箱/头像/性别，手机号与密码不在此列。 */
@Data
public class UpdateProfileDTO {
    private String nickname;
    private String email;
    private String avatar;
    private String avatarSrc; // 头像取景原图；只在换图或重取时带上

    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;   // 0-保密 1-男 2-女
}
