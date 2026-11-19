package com.xingji.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 修改密码请求体：只收原密码与新密码，手机号/邮箱等一概不接受。 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$",
            message = "新密码需 8-32 位，且同时包含字母和数字")
    private String newPassword;
}
