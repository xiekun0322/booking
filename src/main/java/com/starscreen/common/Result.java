package com.starscreen.common;

import lombok.Data;

/**
 * 【功能】统一响应包装类。
 *         所有 REST 接口都返回 Result<T> 结构，前端通过 code 判断成功/失败。
 *         成功：code=200；业务异常：code=400/401/403/500 等。
 *
 * 【调用方】
 *          - 所有 Controller：用 Result.success(...) / Result.error(...) 包装返回值
 *          - GlobalExceptionHandler：捕获异常时返回 Result.error(...)
 *
 * 【被调用】
 *          本类仅依赖 Lombok 的 @Data，不依赖任何其他业务类，属于叶子节点。
 *
 * 【前端约定】
 *          前端 JS 里常见的判断：
 *            if (result.code !== 200) throw new Error(result.message);
 *
 * 【数据结构示例】
 *          {
 *            "code": 200,
 *            "message": "success",
 *            "data": { ... }
 *          }
 *
 * @param <T> 业务数据类型（如 Order、UserVO、List<SeatVO> 等）
 */
@Data
public class Result<T> {

    /** 状态码：200 成功，其它为错误码 */
    private Integer code;

    /** 提示信息：成功固定为 "success"，失败为可读的错误描述 */
    private String message;

    /** 业务数据：失败时为 null */
    private T data;

    /** 无参构造：给 Jackson 反序列化用（本项目实际主要用下面的静态工厂） */
    public Result() {}

    /** 全参构造：一般不直接调用，用静态工厂方法更语义化 */
    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 【功能】成功响应（带数据）。
     * 【调用链】Controller → Result.success(data) → 前端
     * @param data 业务数据
     * @return code=200, message="success", data=data
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 【功能】成功响应（无数据）。
     *          典型场景：删除操作、登出操作。
     * 【调用链】Controller → Result.success() → 前端
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /**
     * 【功能】自定义错误码的失败响应。
     * 【调用链】
     *   - GlobalExceptionHandler.handleBusiness() 用 BusinessException.getCode()
     *   - Controller 中主动返回，如 Result.error(401, "请先登录")
     * @param code    业务错误码
     * @param message 错误描述
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 【功能】默认 500 错误响应。
     * 【调用链】GlobalExceptionHandler.handleException() 兜底时使用
     */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
