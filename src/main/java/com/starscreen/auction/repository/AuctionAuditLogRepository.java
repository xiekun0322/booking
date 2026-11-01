package com.starscreen.auction.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.starscreen.auction.entity.AuctionAuditLog;

/** 审计日志仓储（只追加，不提供更新/删除）。 */
public interface AuctionAuditLogRepository extends JpaRepository<AuctionAuditLog, Long> {
}
