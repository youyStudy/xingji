package com.xingji.mapper;

import com.xingji.pojo.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);
    User selectById(Integer id);
    User selectByPhone(String phone);
    User selectByEmail(String email);
    int updateById(User user);
}
