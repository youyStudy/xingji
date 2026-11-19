package com.xingji.service;

import com.xingji.dto.ChangePasswordDTO;
import com.xingji.dto.UpdateProfileDTO;
import com.xingji.vo.UserVO;

/** 用户业务接口（UC01，REQ-001~003）。 */
public interface UserService {

    /** 注册：查重手机号/邮箱 → BCrypt 加密密码 → 落库。 */
    void register(String phone, String email, String rawPassword, String nickname);

    /** 登录：按手机号查用户 → 校验密码 → 返回 JWT。 */
    String login(String phone, String rawPassword);

    /** 取某用户资料（去掉密码，返回给前端的 UserVO）。 */
    UserVO getProfile(Integer userId);

    /** 编辑资料：只改昵称/邮箱/头像，返回更新后的 UserVO。 */
    UserVO updateProfile(Integer userId, UpdateProfileDTO dto);

    /** 改密码：校验原密码 → BCrypt 重哈希 → 只更新 password 字段。 */
    void changePassword(Integer userId, ChangePasswordDTO dto);
}
