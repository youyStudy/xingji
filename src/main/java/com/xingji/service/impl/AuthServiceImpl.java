package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.config.OAuthProperties;
import com.xingji.dto.ThirdPartyUserInfo;
import com.xingji.mapper.UserAuthMapper;
import com.xingji.mapper.UserMapper;
import com.xingji.pojo.User;
import com.xingji.pojo.UserAuth;
import com.xingji.service.AuthService;
import com.xingji.util.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional   // 分支B要写 user + user_auth 两张表，保证原子性、不留孤儿用户
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserAuthMapper userAuthMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthProperties oauthProperties;

    @Override
    public String oauthLogin(String provider) {
        // 0. 非法平台先挡在门口，别让它往下走到写库
        if (!"qq".equals(provider) && !"wechat".equals(provider)) {
            throw new BizException(400, "不支持的登录平台");
        }

        // 1. 拿到"这个人是谁"（mock 或 真授权，都归一成 ThirdPartyUserInfo）
        ThirdPartyUserInfo info = fetchUserInfo(provider);

        // 2. 按 provider+openId 查有没有已存在的绑定
        UserAuth exist = userAuthMapper.findByProviderAndOpenId(provider, info.getOpenId());
        Integer userId;

        if (exist != null) {
            // 3A. 命中已有绑定 → 直接用绑定的 userId
            userId = exist.getUserId();
        } else {
            // 3B. 首次登录 → 自动注册 user + 建绑定
            User user = new User();
            user.setNickname(info.getNickname());
            user.setAvatar(info.getAvatar());
            user.setRole(0);
            // phone/email/password 全 null：user.password 当初设计成 nullable 就是为了这个场景
            userMapper.insert(user);   // useGeneratedKeys 让 user.getId() 立刻可用

            UserAuth auth = new UserAuth();
            auth.setUserId(user.getId());
            auth.setProvider(provider);
            auth.setOpenId(info.getOpenId());
            auth.setUnionId(info.getUnionId());
            auth.setNickname(info.getNickname());
            auth.setAvatar(info.getAvatar());
            userAuthMapper.insert(auth);

            userId = user.getId();
        }

        // 4. 签发 JWT —— 跟密码登录完全同一个 provider、同一种 token
        return jwtTokenProvider.createToken(userId);
    }

    /** 按 mock 开关分岔：mock 走假数据跑通，非 mock 走真 JustAuth（现留桩）。 */
    private ThirdPartyUserInfo fetchUserInfo(String provider) {
        if (oauthProperties.isMock()) {
            // 固定 openId：保证第二次登录能命中已有绑定，能演示"返回用户"分支
            if ("qq".equals(provider)) {
                return new ThirdPartyUserInfo("mock-qq-fixed-001", null, "QQ用户小明",
                        "https://mock.qq.com/avatar.png");
            } else {
                return new ThirdPartyUserInfo("mock-wechat-fixed-001",
                        "mock-union-001", "微信用户阿强", "https://mock.weixin.com/avatar.png");
            }
        }
        // TODO: 真接 JustAuth 时，用 AuthRequest 换 code 得到 AuthUser，再翻译成 ThirdPartyUserInfo
        throw new UnsupportedOperationException("真授权分支待接入");
    }
}