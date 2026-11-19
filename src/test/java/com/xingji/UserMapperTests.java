package com.xingji;

import com.xingji.mapper.UserMapper;
import com.xingji.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * UserMapper 冒烟测试：验证 数据源 + MyBatis 映射 + 建表 + 驼峰转换 整条地基是否打通。
 * 类上加 @Transactional：每个测试方法结束自动回滚，不会往库里留脏数据，可反复运行。
 */
@SpringBootTest
@Transactional
class UserMapperTests {

    @Autowired
    private UserMapper userMapper;

    @Test
    void insertAndSelectByPhone() {
        // 1. 造一个用户并插入
        User user = new User();
        user.setPhone("13800001111");
        user.setEmail("test1111@xingji.com");
        user.setPassword("aBCryptHashPlaceholder");
        user.setNickname("测试用户");
        user.setRole(0);

        int rows = userMapper.insert(user);
        assertEquals(1, rows, "insert 应影响 1 行");
        assertNotNull(user.getId(), "插入后自增 id 应回填到实体");

        // 2. 按手机号查回来，验证映射
        User found = userMapper.selectByPhone("13800001111");
        assertNotNull(found, "应能按手机号查到刚插入的用户");
        assertEquals("测试用户", found.getNickname(), "昵称字段应正确映射");
        assertEquals(user.getId(), found.getId(), "查回的 id 应与回填的一致");
        assertNotNull(found.getCreateTime(), "create_time 应由数据库默认值填充并映射");
    }
}
