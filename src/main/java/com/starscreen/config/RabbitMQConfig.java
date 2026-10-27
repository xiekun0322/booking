package com.starscreen.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：利用 TTL + 死信队列实现"延迟 10 分钟检查订单"。
 *
 * <p>消息流向：
 * <pre>
 *   生产者 → ORDER_EXCHANGE/ORDER_ROUTING_KEY → ORDER_QUEUE（TTL 10 分钟）
 *   TTL 到期 → ORDER_DELAY_EXCHANGE → ORDER_DELAY_QUEUE
 *   OrderTimeoutConsumer 监听 ORDER_DELAY_QUEUE
 * </pre>
 *
 * <p><b>NOTE</b>：ORDER_QUEUE 命名容易误解——它是"正常队列"，
 * 只是恰好没有消费者、专用来憋 TTL。想清楚改名请同步全部引用。
 * <p><b>NOTE</b>：未设置 {@code x-max-length} / 消费失败重试上限，
 * 队列堆积或消费端异常时需要额外的 DLQ 策略（见 application.properties
 * 的 spring.rabbitmq.listener.simple.retry 配置）。
 */
@Configuration
public class RabbitMQConfig {

    /** 正常下单时发送消息的交换机。 */
    public static final String ORDER_EXCHANGE = "order.exchange";

    /** 正常下单队列（无消费者，仅承载 TTL）。 */
    public static final String ORDER_QUEUE = "order.queue";

    public static final String ORDER_ROUTING_KEY = "order.create";

    /** 死信交换机（TTL 到期后转发到此）。 */
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";

    /** 死信队列，消费者监听它执行取消。 */
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";

    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";

    // ==================== 1. 正常业务队列（带死信配置） ====================

    @Bean
    public Queue orderQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 600_000);                       // 10 分钟
        args.put("x-dead-letter-exchange", ORDER_DELAY_EXCHANGE);
        args.put("x-dead-letter-routing-key", ORDER_DELAY_ROUTING_KEY);
        return QueueBuilder.durable(ORDER_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_ROUTING_KEY);
    }

    // ==================== 2. 死信队列（消费者监听） ====================

    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(ORDER_DELAY_QUEUE).build();
    }

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE);
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with(ORDER_DELAY_ROUTING_KEY);
    }
}
