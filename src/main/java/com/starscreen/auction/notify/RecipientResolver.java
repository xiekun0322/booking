package com.starscreen.auction.notify;

/**
 * 收件地址解析（端口）。
 *
 * <p>邮箱在业务库的 {@code users} 表，拍卖库拿不到，只能通过端口跨库取。
 * 与出价昵称解析同一个套路：拍卖域不直接依赖 {@code UserRepository}，
 * 将来拍卖库独立部署时只需替换实现，通知链路不动。
 *
 * <p>实现方必须自己吞掉异常并返回 {@code null}：取不到邮箱只应导致"外发跳过"，
 * 不能让一条通知因为查用户失败而写不进去。
 */
public interface RecipientResolver {

    /**
     * 取用户邮箱。
     *
     * @return 邮箱；用户不存在、未填邮箱或查询失败时返回 {@code null}
     */
    String resolveEmail(Long userId);
}
