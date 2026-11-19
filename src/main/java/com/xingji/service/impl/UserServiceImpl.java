package com.xingji.service.impl;

import com.xingji.common.BizException;
import com.xingji.dto.ChangePasswordDTO;
import com.xingji.dto.UpdateProfileDTO;
import com.xingji.mapper.UserMapper;
import com.xingji.pojo.User;
import com.xingji.service.UserService;
import com.xingji.util.JwtTokenProvider;
import com.xingji.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;  // token验证

    // 直接 new 一个即可，BCryptPasswordEncoder 无状态、线程安全
    //`BCryptPasswordEncoder` 是Spring Security 提供的密码加密工具，专门用来做密码哈希加密
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(String phone, String email, String rawPassword, String nickname) {
        // 查重（selectByPhone/ByEmail 已在 XML 里过滤 deleted=0，软删的账号不占用）
        if (userMapper.selectByPhone(phone) != null) {
            throw new BizException(409, "手机号已注册");
        }
        // 前端email可以不填直接注册
        if (email != null && !email.isBlank() && userMapper.selectByEmail(email) != null) {
            throw new BizException(409, "邮箱已被使用");
        }

        User user = new User();
        user.setPhone(phone);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(rawPassword));  // 存哈希，绝不存明文
        user.setNickname(nickname);
        user.setRole(0);  // 默认普通用户
        userMapper.insert(user);
    }

    @Override
    public String login(String phone, String rawPassword) {
        User user = userMapper.selectByPhone(phone);
        // 用户不存在 或 密码不匹配，统一同一句话，不透露“账号是否存在”
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            // UC01 §3a1：记录登录失败日志（内部区分原因，手机号脱敏后落盘，对外仍返回统一话术防枚举）
            log.warn("登录失败：phone={} reason={}", maskPhone(phone),
                    user == null ? "账号不存在" : "密码错误");
            throw new BizException(401, "手机号或密码错误");
        }
        return jwtTokenProvider.createToken(user.getId());
    }

    @Override
    public UserVO getProfile(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return toVO(user);
    }

    @Override
    public UserVO updateProfile(Integer userId, UpdateProfileDTO dto) {
        User exist = userMapper.selectById(userId);
        if (exist == null) {
            throw new BizException(404, "用户不存在");
        }
        // 只带上要改的非空字段：updateById 的动态 <set> 会跳过 null/空，password/role 不会被误清
        User patch = new User();
        patch.setId(userId);
        if (dto != null) {
            if (dto.getNickname() != null && !dto.getNickname().isBlank()) patch.setNickname(dto.getNickname());
            if (dto.getEmail() != null && !dto.getEmail().isBlank()) patch.setEmail(dto.getEmail());
            if (dto.getAvatar() != null && !dto.getAvatar().isBlank()) patch.setAvatar(dto.getAvatar());
            if (dto.getAvatarSrc() != null && !dto.getAvatarSrc().isBlank()) patch.setAvatarSrc(dto.getAvatarSrc());
            if (dto.getGender() != null) patch.setGender(dto.getGender());  // 0 合法，只判 null
        }
        userMapper.updateById(patch);
        return toVO(userMapper.selectById(userId));
    }

    @Override
    public void changePassword(Integer userId, ChangePasswordDTO dto) {
        User exist = userMapper.selectById(userId);
        if (exist == null) {
            throw new BizException(404, "用户不存在");
        }
        // 先验原密码：BCrypt 只能靠 matches 比对哈希，拿不到明文
        if (!passwordEncoder.matches(dto.getOldPassword(), exist.getPassword())) {
            throw new BizException(400, "原密码不正确");
        }
        if (dto.getNewPassword().equals(dto.getOldPassword())) {
            throw new BizException(400, "新密码不能与原密码相同");
        }
        // 只带上 id + 新哈希，动态 <set> 跳过其它 null 字段，绝不误伤昵称/邮箱
        User patch = new User();
        patch.setId(userId);
        patch.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(patch);
    }

    /** 手机号脱敏：保留前3后4，中间打码，用于日志/展示，避免明文 PII 落盘。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 实体 → 视图：剥掉 password 等敏感字段 */
    private UserVO toVO(User u) {
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setPhone(u.getPhone());
        vo.setEmail(u.getEmail());
        vo.setNickname(u.getNickname());
        vo.setAvatar(u.getAvatar());
        vo.setAvatarSrc(u.getAvatarSrc());
        vo.setGender(u.getGender());
        vo.setRole(u.getRole());
        return vo;
    }
}
