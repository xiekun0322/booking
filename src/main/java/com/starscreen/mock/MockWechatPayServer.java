package com.starscreen.mock;

import com.starscreen.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockWechatPayServer {

    // 注入我们的核心业务服务
    private final OrderService orderService;

    /**
     * 模拟微信服务器接收统一下单后，用户扫码付款，并在3秒后异步回调通知商户
     * @param orderId 订单ID
     */
    public void simulateUserPayAndCallback(Long orderId) {
        log.info("[模拟微信服务器] 已生成二维码，等待用户扫码...");

        CompletableFuture.runAsync(() -> {
            try {
                // 模拟用户掏出手机扫码、输密码、付款的 3 秒钟
                Thread.sleep(3000);
                
                log.info("[模拟微信服务器] 用户付款成功！正在向商户发送异步回调...");
                
                // 核心：这里的回调，是第三方系统主动调用我们的后端逻辑
                // 直接调用 Service 层，确保 @Transactional 事务生效
                orderService.handlePayCallback(orderId);
                
                log.info("[模拟微信服务器] 回调已发送完毕。");               
            } catch (InterruptedException e) {
                log.error("[模拟微信服务器] 回调发送被中断", e);
                // 核心修复：恢复线程的中断状态
                Thread.currentThread().interrupt(); 
            } catch (Exception e) {
                log.error("[模拟微信服务器] 回调发送失败", e);
            }
        });
    }
}
