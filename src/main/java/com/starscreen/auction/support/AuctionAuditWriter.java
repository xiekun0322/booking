package com.starscreen.auction.support;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.entity.AuctionAuditLog;
import com.starscreen.auction.repository.AuctionAuditLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 审计写入器。
 *
 * <p><b>写失败不抛异常</b>：审计是"必须留下证据"，但让它阻断主流程会得不偿失——
 * 用户出价成功却因为审计表写不进去而收到 500，是比"少一条审计"更严重的问题。
 * 这里记 ERROR 日志并继续，同时由日志采集侧兜底（ERROR 级别会进告警）。
 *
 * <p><b>参与调用方事务</b>：{@code Propagation.REQUIRED}，因为审计必须与业务状态同生共死。
 * 若业务回滚而审计留下，审计表就会出现"记录显示已成交、实际没成交"的假证据。
 */
@Component
@RequiredArgsConstructor
public class AuctionAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuctionAuditWriter.class);

    private final AuctionAuditLogRepository auditRepository;

    /**
     * @param bizType   BID / SETTLE / ITEM / DEPOSIT / DEAL
     * @param bizId     业务主键
     * @param actorId   操作人（系统操作填 0）
     * @param actorType USER / ADMIN / SYSTEM
     * @param action    动作名，例如 AUCTION_SETTLED
     * @param detail    可读详情（关键字段拼串，便于直接阅读，不要求结构化）
     * @param clientIp  客户端 IP，可空
     */
    @AuctionTransactional
    public void write(String bizType, Long bizId, Long actorId, String actorType,
                      String action, String detail, String clientIp) {
        try {
            AuctionAuditLog entry = new AuctionAuditLog();
            entry.setBizType(bizType);
            entry.setBizId(bizId);
            entry.setActorId(actorId == null ? 0L : actorId);
            entry.setActorType(actorType);
            entry.setAction(action);
            entry.setDetail(detail);
            entry.setClientIp(clientIp);
            entry.setCreateTime(LocalDateTime.now());
            auditRepository.save(entry);
        } catch (RuntimeException e) {
            log.error("拍卖审计写入失败 bizType={} bizId={} action={} detail={}",
                    bizType, bizId, action, detail, e);
        }
    }
}
