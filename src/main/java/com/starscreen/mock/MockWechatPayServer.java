package com.starscreen.mock;

import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;

import com.starscreen.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 模拟第三方支付服务器：下单后 3 秒异步回调 {@link OrderService#handlePayCallback}。
 *
 * <p>回调走 Java 内部调用（不经 HTTP），保证事务生效。
 *
 * <p><b>NOTE</b>：使用 {@code ForkJoinPool.commonPool} + {@code Thread.sleep}，
 * 并发高时会阻塞公共池；生产环境应换用专用的 {@code ScheduledExecutorService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockWechatPayServer {

    private final OrderService orderService;

    /** 异步 3 秒后触发支付成功回调。 */
    public void simulateUserPayAndCallback(Long orderId) {
        log.info("[模拟微信服务器] 已生成二维码，等待用户扫码...");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3000);
                log.info("[模拟微信服务器] 用户付款成功！正在向商户发送异步回调...");
                orderService.handlePayCallback(orderId);
                log.info("[模拟微信服务器] 回调已发送完毕。");
            } catch (InterruptedException e) {
                log.error("[模拟微信服务器] 回调发送被中断", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[模拟微信服务器] 回调发送失败", e);
            }
        });
    }
}
