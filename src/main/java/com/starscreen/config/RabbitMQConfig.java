package com.starscreen.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // 1. 正常下单时，发送消息的交换机
    public static final String ORDER_EXCHANGE = "order.exchange";
    // 2. 正常下单时，发送消息的队列（这个队列没有消费者，专门用来憋 10 分钟）
    public static final String ORDER_QUEUE = "order.queue";
    public static final String ORDER_ROUTING_KEY = "order.create";

    // 3. 死信交换机（消息过期后，转发到这里）
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    // 4. 死信队列（消费者监听这个队列，真正干取消操作的）
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";

    // ================= 1. 声明正常业务队列（带死信配置） =================
    @Bean
    public Queue orderQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息在这个队列里存活 10 分钟 (600000 毫秒)，过期后变成死信
        args.put("x-message-ttl", 600000);
        // 过期后，丢给哪个死信交换机？
        args.put("x-dead-letter-exchange", ORDER_DELAY_EXCHANGE);
        // 丢给死信交换机的哪个路由键？
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

    // ================= 2. 声明死信队列（消费者真正监听的） =================
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
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }
}
