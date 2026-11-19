package com.xingji.common;

/** 业务异常：service 里校验不通过就抛它，交给全局异常处理器统一转成 Result。 */
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(String message) {
        this(500, message);
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
