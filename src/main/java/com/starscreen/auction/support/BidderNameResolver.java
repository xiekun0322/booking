package com.starscreen.auction.support;

/**
 * 出价人展示名解析口（端口）。
 *
 * <p><b>为什么要一个端口而不是直接查 UserRepository</b>：
 * 用户表在<b>业务库</b>，拍卖服务和出价表在<b>拍卖库</b>。直接在拍卖服务里依赖
 * 业务库仓储，会让两个数据源在代码层面耦合——将来拍卖库独立部署时，
 * 这里会变成无法编译的硬依赖。
 *
 * <p>接口留在拍卖侧（依赖倒置），实现放在 {@code com.starscreen.integration.auction}，
 * 由集成层承担"跨库取昵称"这件脏活。
 *
 * <p><b>实现必须容错</b>：解析失败只能降级成占位名，绝不能抛异常——
 * 昵称只影响展示，而它所在的调用链路是"用户正在出价"，不能因为一个名字查不到就让出价失败。
 */
public interface BidderNameResolver {

    /**
     * @param userId 用户 ID
     * @return 展示名；解析不到时返回兜底文案（不返回 null）
     */
    String resolve(Long userId);
}
