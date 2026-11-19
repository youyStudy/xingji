package com.xingji.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserAuth {

    private Integer id;
    private Integer userId;   // 关联用户
    private String provider;  // 平台: qq / wechat
    private String openId;    // 该平台内用户唯一标识
    private String unionId;   // 跨应用统一标识(微信)
    private String nickname;  // 授权昵称快照
    private String avatar;    // 授权头像快照
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
