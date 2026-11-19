package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Integer id;        // 用户ID
    private String phone;      // 手机号(登录用)
    private String email;      // 邮箱(登录用)
    private String password;   // BCrypt 密码哈希
    private String nickname;   // 昵称
    private String avatar;     // 头像URL
    private String avatarSrc;  // 头像取景原图URL
    private Integer gender;    // 性别 0-保密 1-男 2-女
    private Integer role;      // 0-普通用户 1-管理员
    private Integer deleted;   // 软删除 0-正常 1-已删除
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
