package com.xingji.mapper;

import com.xingji.pojo.UserAuth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserAuthMapper {

    // 按openId查
    UserAuth findByProviderAndOpenId(@Param("provider")String provider,
                                     @Param("provider")String openId);

    int insert(UserAuth userAuth);
}
