package com.starscreen.auction.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拍卖库事务（绑定 {@code auctionTransactionManager}）。
 *
 * <p><b>为什么不用原生 {@code @Transactional}</b>：项目是双数据源，
 * 默认事务管理器是业务库的 {@code transactionManager}。在拍卖服务里写裸
 * {@code @Transactional} 不会报错，但会把拍卖表的读写放进<b>业务库事务</b>——
 * 轻则数据写不进去，重则跨库连接混用把业务库连接池占满。
 * 这类错误编译期发现不了，所以统一用一个语义明确的注解。
 *
 * <p>{@code rollbackFor = Exception.class}：本项目全部异常都是 RuntimeException，
 * 但加上它对受检异常也生效，避免以后有人抛受检异常时静默提交。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional(
        transactionManager = AuctionDataSourceConfig.AUCTION_TX_MANAGER,
        rollbackFor = Exception.class,
        propagation = Propagation.REQUIRED)
public @interface AuctionTransactional {

    /**
     * 只读事务（列表查询）。
     *
     * <p><b>必须 {@code @AliasFor}</b>：否则这个属性只是注解自己的一个字段，
     * <b>不会传给底层 {@code @Transactional}</b>——写了 {@code readOnly = true}
     * 但事务仍然是读写事务，既不路由只读库，也让 Hibernate 的脏检查白白跑一遍。
     * 这类"看着生效、实际没生效"的配置，比没有更危险。
     */
    @AliasFor(annotation = Transactional.class, attribute = "readOnly")
    boolean readOnly() default false;
}
