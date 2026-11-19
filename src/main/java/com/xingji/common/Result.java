package com.xingji.common;

import lombok.Data;

/** 统一返回外壳：所有接口都返 {code, msg, data}，前端好统一处理。 */
@Data
public class Result<T> {

    private Integer code;
    private String msg;
    private T data;

    private Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }
}