package com.starscreen.auction.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 拍卖 MQ 拓扑。
 *
 * <p><b>只保留真正有消费者的队列</b>：{@code auction.deposit.queue} 由
 * {@code AuctionDepositEventConsumer} 消费。早期还声明过 {@code auction.bid.queue}
 * 与 {@code auction.close.queue}，但它们没有任何消费者——消息只会在队列里堆积，
 * 直到触达长度上限被丢进死信。声明一个没人消费的队列不是"为将来预留"，
 * 而是给运维埋了一个必然会响的告警。需要广播时再按本类的形状新增即可。
 *
 * <p><b>为什么保证金事件单独一条队列</b>：它是"内部资金指令"，必须恰好一次生效；
 * 与通知类事件混在一起时，任何一个订阅方扩容或改绑定都会影响资金链路。
 *
 * <p><b>队列参数必须与 broker 上已存在的队列一致</b>：RabbitMQ 在声明时逐项比对参数，
 * 任何一项不同都会返回 406 PRECONDITION_FAILED 并导致应用启动失败。
 * 改这里的参数，必须同时删除旧队列（上线前置动作，见 README）。
 */
@Configuration
public class AuctionRabbitConfig {

    /** 拍卖业务交换机。 */
    public static final String AUCTION_EXCHANGE = "auction.exchange";

    /** 保证金事件队列：退款触发与悔拍罚没共用。 */
    public static final String DEPOSIT_QUEUE = "auction.deposit.queue";
    public static final String DEPOSIT_ROUTING_KEY = "auction.deposit";

    /** 死信交换机与队列。 */
    public static final String DEAD_EXCHANGE = "auction.dlx";
    public static final String DEAD_QUEUE = "auction.dead.queue";
    public static final String DEAD_ROUTING_KEY = "auction.dead";

    /** 保证金队列上限：每场拍卖每人一条，量级不大，纯属防失控。 */
    private static final int DEPOSIT_QUEUE_MAX_LENGTH = 200_000;

    @Bean
    public DirectExchange auctionExchange() {
        return new DirectExchange(AUCTION_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange auctionDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue auctionDepositQueue() {
        return QueueBuilder.durable(DEPOSIT_QUEUE)
                .withArgument("x-max-length", DEPOSIT_QUEUE_MAX_LENGTH)
                // 满了不丢消息、也不阻塞发布方：转投死信，留给人工处理
                .withArgument("x-overflow", "reject-publish-dlx")
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 死信队列不设长度上限：进了这里就是需要人工处理的异常，
     * 再加一个上限只会让"异常消息"也被丢掉。
     */
    @Bean
    public Queue auctionDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding auctionDepositBinding() {
        return BindingBuilder.bind(auctionDepositQueue())
                .to(auctionExchange())
                .with(DEPOSIT_ROUTING_KEY);
    }

    @Bean
    public Binding auctionDeadBinding() {
        return BindingBuilder.bind(auctionDeadQueue())
                .to(auctionDeadExchange())
                .with(DEAD_ROUTING_KEY);
    }
}
