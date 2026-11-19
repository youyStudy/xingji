package com.xingji.vo;

import lombok.Data;

/** 返回给前端的用户资料视图：绝不包含 password 等敏感字段。 */
@Data
public class UserVO {
    private Integer id;
    private String phone;
    private String email;
    private String nickname;
    private String avatar;
    private String avatarSrc; // 取景原图：前端重取头像时回到这张，不随裁切变小
    private Integer gender;   // 0-保密 1-男 2-女
    private Integer role;   // 0-普通 1-管理员
}
