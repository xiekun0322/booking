package com.starscreen.auction.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.config.AuctionProperties;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * 成交存证派发器（异步）。
 *
 * <p><b>为什么必须异步</b>：存证要调外部网关，RT 从几十毫秒到几秒不等。
 * 放在结算事务里，一次关拍会串行等 N 次网络往返，直接把关拍扫描拖到分钟级——
 * 表现为"拍品到点了还没结束"，而真正的原因是存证网关慢。
 *
 * <p><b>独立单线程池</b>：存证是"做了更好、不做也不影响成交"的动作，
 * 用独立线程避免它和出价、结算抢线程；单线程保证同一时刻只压一个外部请求。
 *
 * <p><b>失败只记日志</b>：存证失败不回滚成交。拍卖的法律效力来自数据库状态与审计日志，
 * 存证是增强证据，不是前置条件。
 */
@Component
@RequiredArgsConstructor
public class ChainNotarizeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChainNotarizeDispatcher.class);

    private final AuctionProperties properties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "auction-chain-notary");
        thread.setDaemon(true);
        return thread;
    });

    /** 提交一条成交存证。 */
    public void submitSettle(Long itemId, String payload) {
        if ("NONE".equalsIgnoreCase(properties.getChainMode())) {
            // 关闭态也留一条本地指纹，便于事后自证数据未被篡改
            log.debug("存证已关闭（chainMode=NONE），仅记录本地摘要 itemId={} sha256={}",
                    itemId, sha256(payload));
            return;
        }
        executor.submit(() -> {
            try {
                String digest = sha256(payload);
                if ("HTTP".equalsIgnoreCase(properties.getChainMode())) {
                    // 真实对接点：POST properties.getChainEndpoint()，body 为 payload + digest。
                    // 当前项目没有存证服务，保留开关与日志，避免伪造一个"已上链"的假象。
                    log.info("【拍卖存证】待上链 itemId={} endpoint={} sha256={}",
                            itemId, properties.getChainEndpoint(), digest);
                } else {
                    log.info("【拍卖存证】itemId={} sha256={}", itemId, digest);
                }
            } catch (RuntimeException e) {
                log.error("【拍卖存证】失败，不影响成交 itemId={}", itemId, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必定支持 SHA-256，走到这里说明运行环境异常
            return "unavailable";
        }
    }
}
