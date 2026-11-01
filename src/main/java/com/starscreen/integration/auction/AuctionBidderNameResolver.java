package com.starscreen.integration.auction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.support.BidderNameResolver;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 跨库取出价人昵称（拍卖 → 票务用户表）。
 *
 * <p><b>这是全项目唯一一处"拍卖读业务库"的地方</b>，刻意收敛在一个类里：
 * 出问题只需要看这一个文件；将来拍卖库拆出去，也只需要把这个实现换成
 * "调用户服务接口"，拍卖侧代码一行不动。
 *
 * <p><b>调用频率与成本</b>：每次出价一次主键查询（业务库），走主键索引，毫秒级。
 * 相比"出价消息异步回填昵称"的方案，这里牺牲一点点实时性成本换来了确定性——
 * 不需要额外消费者、不需要处理消息乱序，也不会出现"出价成功了但榜单上没名字"的中间态。
 *
 * <p><b>失败降级</b>：任何异常（业务库不可用、用户被删除）都返回 {@code 用户{id}}，
 * 绝不让昵称解析把出价打回去。
 */
@Component
@RequiredArgsConstructor
public class AuctionBidderNameResolver implements BidderNameResolver {

    private static final Logger log = LoggerFactory.getLogger(AuctionBidderNameResolver.class);

    private final UserRepository userRepository;

    @Override
    public String resolve(Long userId) {
        if (userId == null) {
            return "匿名";
        }
        try {
            return userRepository.findById(userId)
                    .map(User::getUsername)
                    .filter(name -> name != null && !name.isBlank())
                    .orElseGet(() -> fallback(userId));
        } catch (RuntimeException e) {
            log.warn("出价人昵称解析失败，降级为占位名 userId={}", userId, e);
            return fallback(userId);
        }
    }

    private String fallback(Long userId) {
        return "用户" + userId;
    }
}
