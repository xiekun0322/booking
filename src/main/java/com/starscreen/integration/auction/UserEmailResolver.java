package com.starscreen.integration.auction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.notify.RecipientResolver;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 跨库取用户邮箱（拍卖 → 票务用户表）。
 *
 * <p>与 {@code AuctionBidderNameResolver} 并列，是拍卖域读业务库的第二处，
 * 同样收敛在 {@code integration} 包内，避免依赖扩散。
 *
 * <p><b>只读、只走主键</b>：一次主键查询，毫秒级。作为通知落库的附带成本可以接受；
 * 若将来通知量级上来了，应改为在通知表里冗余邮箱，而不是加缓存。
 */
@Component
@RequiredArgsConstructor
public class UserEmailResolver implements RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(UserEmailResolver.class);

    private final UserRepository userRepository;

    @Override
    public String resolveEmail(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            return userRepository.findById(userId)
                    .map(User::getEmail)
                    .filter(email -> email != null && !email.isBlank())
                    .orElse(null);
        } catch (RuntimeException e) {
            // 取不到邮箱只是跳过外发，站内信照常写入
            log.warn("通知收件邮箱解析失败 userId={}", userId, e);
            return null;
        }
    }
}
