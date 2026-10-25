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

/**
 * 【功能】全局异常处理器。
 *         统一捕获所有 Controller 抛出的异常，转换成 Result JSON 返回给前端，
 *         避免把 Java 堆栈直接暴露给用户，也避免每个 Controller 都写 try-catch。
 *
 * 【触发机制】Spring MVC 的 @RestControllerAdvice
 *          - Controller 方法或 Service 方法抛出异常时，Spring 会查找匹配的 @ExceptionHandler
 *          - 匹配顺序：最具体的异常类型优先（BusinessException 优先于 Exception）
 *
 * 【调用方】
 *          由 Spring MVC 框架自动调用，开发者不主动调用。
 *          异常来源：
 *            - Controller 层主动 throw
 *            - Service 层 throw（BusinessException 占多数）
 *            - 参数校验失败：@Valid + @NotNull / @NotBlank / @Size
 *
 * 【被调用】
 *          Result.error(...) —— 构造统一响应体
 *
 * 【返回规范】
 *          - BusinessException、参数校验：HTTP 200 + Result{code, message, data=null}
 *          - NoResourceFoundException：HTTP 404，空 body
 *          - 其它异常：HTTP 200 + Result{code=500, message="系统繁忙，请稍后重试"}
 *
 * 【日志规范】
 *          - 业务异常：log.warn（不打堆栈）
 *          - 系统异常：log.error（打完整堆栈）
 *          - 静态资源 404：不打日志（避免 favicon 之类的噪音）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 【功能】处理业务异常。
     * 【调用链】Service/Controller 抛 BusinessException → 本方法 → Result.error(code, message)
     * 【日志】warn 级别，只打消息，不打堆栈
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 【功能】处理参数校验异常（@Valid 失败）。
     *          例如：CreateOrderRequest 中 @NotEmpty 座位列表、LoginRequest 中 @NotBlank 用户名。
     * 【调用链】
     *   Controller 上标注 @Valid → 校验失败 → MethodArgumentNotValidException
     *   → 本方法 → 取出第一个字段错误消息 → Result.error(400, msg)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = "参数错误";
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null) {
            msg = fieldError.getDefaultMessage();
        }
        return Result.error(400, msg);
    }

    /**
     * 【功能】处理静态资源 404。
     *          典型来源：/favicon.ico、Spring DevTools 探测、浏览器预请求。
     * 【为什么单独处理】若走 handleException 兜底，会打大量无意义堆栈日志。
     * 【返回】HTTP 404，空 body，前端浏览器控制台不会看到刺眼的 JSON 报错。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 【功能】兜底异常处理。
     *          捕获所有未被上面方法匹配的异常（NullPointerException、数据库异常等）。
     * 【调用链】任意 Exception → 本方法 → log.error(堆栈) → Result.error(500, 通用文案)
     * 【安全考量】不把 e.getMessage() 直接返回给前端，避免泄露 SQL、路径、类名等信息。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}