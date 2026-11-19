package com.xingji.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 注册请求体。只收该由客户端填的字段，id/role/deleted 一律不接受。 */
@Data
public class RegisterDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Email(message = "邮箱格式不正确")
    private String email;   // 可选，不填不校验

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$",
            message = "密码需 8-32 位，且同时包含字母和数字")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最多 20 字")
    private String nickname;
}