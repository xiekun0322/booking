package com.starscreen.common;

import lombok.Data;

/**
 * 统一响应包装类。
 *
 * <pre>
 *   { "code": 200, "message": "success", "data": { ... } }
 * </pre>
 *
 * <p>前端约定：{@code if (result.code !== 200) throw new Error(result.message);}
 *
 * <p><b>NOTE</b>：{@code success(T)} 与 {@code success()} 互为重载，
 * 调用 {@code Result.success(null)} 会有编译歧义，需显式转型。
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public Result() {}

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（带数据）。 */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /** 成功（无数据），用于删除、登出等。 */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /** 指定错误码。 */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 默认 500 错误。 */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
