package com.xingji.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 第三方平台返回的用户信息抽象。mock 分支和真 JustAuth 分支都产这个，业务层只认它。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThirdPartyUserInfo {
    private String openId;
    private String unionId;
    private String nickname;
    private String avatar;
}