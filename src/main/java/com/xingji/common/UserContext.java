package com.xingji.common;

/** 当前登录用户上下文。同一请求同一线程，拦截器存、业务层取，请求结束清空。 */
public class UserContext {

    private static final ThreadLocal<Integer> USER_ID = new ThreadLocal<>();

    private UserContext() {
        // 工具类，纯静态方法，不需要 new 实例
    }

    public static void setUserId(Integer userId) {
        USER_ID.set(userId);
    }

    public static Integer getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();   // remove 而非 set(null)，彻底清掉线程副本
    }
}