package com.starscreen.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器：把 Controller/Service 抛出的异常统一转成 {@link Result}。
 *
 * <p>匹配顺序按"最具体优先"：
 * BusinessException → 参数校验 → 静态资源 404 → 时钟回拨 → Constraint → 兜底。
 *
 * <p>日志规范：
 * <ul>
 *   <li>业务异常：warn，不打堆栈；</li>
 *   <li>系统异常：error，打完整堆栈；</li>
 *   <li>静态资源 404：不打日志（避免噪音）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = "参数错误";
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null) {
            msg = fieldError.getDefaultMessage();
        }
        return Result.error(400, msg);
    }

    /** 静态资源 404：返回空 body 的 HTTP 404，不打日志。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** @RequestParam / @PathVariable 校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().iterator().next().getMessage();
        return Result.error(400, msg);
    }

    /** 雪花算法时钟回拨，属系统级灾难，error 级别告警。 */
    @ExceptionHandler(ClockBackwardsException.class)
    public Result<Void> handleClockBackwards(ClockBackwardsException e) {
        log.error("生成分布式ID失败，系统时钟发生回拨：{}", e.getMessage(), e);
        return Result.error(500, "系统繁忙，请稍后重试（时间同步中）");
    }

    /** 兜底：不打 e.getMessage() 给前端，避免泄露 SQL/路径。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
