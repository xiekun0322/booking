package com.starscreen.common;

/**
 * 雪花算法时钟回拨异常。
 *
 * <p>服务器时间发生回退时，生成的 ID 可能重复，故直接抛出。
 * {@link GlobalExceptionHandler} 会记录 error 级别日志并返回通用错误。
 */
public class ClockBackwardsException extends RuntimeException {

    public ClockBackwardsException(String message) {
        super(message);
    }
}
