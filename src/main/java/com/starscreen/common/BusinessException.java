package com.starscreen.common;

/**
 * 业务异常（可预期的规则不满足）。
 *
 * <p>与系统异常的区别：
 * <ul>
 *   <li>BusinessException：{@link GlobalExceptionHandler} 转成可读 JSON，不打堆栈；</li>
 *   <li>其它 Exception：打完整堆栈，返回通用 "系统繁忙"。</li>
 * </ul>
 *
 * <p>继承 {@link RuntimeException}：
 * 可直接 throw（无需 throws 声明），且 {@code @Transactional} 遇之自动回滚。
 */
public class BusinessException extends RuntimeException {

    /** 业务错误码，默认 500；常用 400 / 401 / 403 / 404。 */
    private final Integer code;

    /** 默认错误码 500。 */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /** 指定错误码。 */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
