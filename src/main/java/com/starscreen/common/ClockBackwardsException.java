package com.starscreen.common;

/**
 * 【功能】时钟回拨专用异常。
 *         当服务器时间发生回退，导致雪花算法可能生成重复 ID 时抛出。
 */
public class ClockBackwardsException extends RuntimeException {
    
    public ClockBackwardsException(String message) {
        super(message);
    }
}
