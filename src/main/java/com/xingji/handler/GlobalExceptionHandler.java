package com.xingji.handler;

import com.xingji.common.BizException;
import com.xingji.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** 全局异常处理：把异常统一转成 Result，堆栈只进日志、不外泄给前端。 */
@Slf4j
@RestControllerAdvice //声明 "我是全局异常处理类"，自动对所有 Controller 生效
public class GlobalExceptionHandler {

    /** 1. 业务异常：直接用异常自带的 code 返回（401/409 等）。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 2. @Valid 校验失败：把所有字段的中文提示拼成一句，返 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg);
    }

    /** 请求体不是合法 JSON，或字段类型无法转换：属于客户端输入错误，返 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return Result.fail(400, "请求体格式不正确");
    }

    /** 3. 兜底：完整堆栈记日志，前端只给笼统 500，不泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(500, "服务器内部错误");
    }
}