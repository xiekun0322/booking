package com.starscreen.auction.notify;

/**
 * 外发邮件通道（端口）。
 *
 * <p>拍卖域只依赖这个接口，不依赖任何具体邮件实现：当前只有一个打日志的
 * {@link LogMailSender}，将来接入真实 SMTP/云邮件服务时新增一个实现即可，
 * 通知链路与重试逻辑一行不用改。
 *
 * <p>{@link #send} 抛异常即视为失败，由 worker 负责退避重试；
 * 返回 {@code false} 也按失败处理（通道明确拒绝）。
 */
public interface MailSender {

    /**
     * 发送一封通知邮件。
     *
     * @param to      收件地址（非空由调用方保证）
     * @param subject 主题
     * @param content 正文（纯文本）
     * @return 是否受理成功
     * @throws RuntimeException 通道异常
     */
    boolean send(String to, String subject, String content);
}
