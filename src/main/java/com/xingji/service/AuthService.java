package com.xingji.service;

public interface AuthService {
    /** 传平台(qq/wechat)，返回一个 JWT（与密码登录同构，下游无差异）。 */
    String oauthLogin(String provider);
}
