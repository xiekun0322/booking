package com.starscreen.auction.notify;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 打日志的邮件通道（未配置 SMTP 时的默认实现）。
 *
 * <p><b>为什么用日志而不是直接失败</b>：没有邮件服务时，如果发送直接失败，
 * 每条通知都会退避重试到 DEAD，把"退款死信"这类真正需要关注的指标淹掉。
 * 记录成"已发送到日志"能保证链路本身可验证，同时明确提示当前是模拟状态。
 *
 * <p>接入真实通道时新增一个 {@link MailSender} 实现并删除本类即可，
 * 通知链路与重试逻辑不需要任何改动。
 */
@Component
public class LogMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LogMailSender.class);

    /** 只提示一次：每条通知都刷一行同样的警告没有信息量。 */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    @Override
    public boolean send(String to, String subject, String content) {
        if (warned.compareAndSet(false, true)) {
            log.warn("【通知】未配置 SMTP，邮件仅写入日志（模拟发送）。生产环境请接入真实通道。");
        }
        log.info("【通知-模拟邮件】to={} subject={} content={}", to, subject, content);
        return true;
    }
}
