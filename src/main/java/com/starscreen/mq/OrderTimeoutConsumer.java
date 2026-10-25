package com.starscreen.mq;

import com.starscreen.config.RabbitMQConfig;
import com.starscreen.entity.Seat;
import com.starscreen.repository.OrderRepository;
import com.starscreen.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;

    /**
     * 监听死信队列。10 分钟后，消息会跑到这里
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_DELAY_QUEUE)
    @Transactional
    public void handleOrderTimeout(Long orderId) {
        log.info("【MQ超时消费】收到超时检查消息，orderId={}", orderId);

        // 1. 核心：尝试原子取消（只有 pending 才能被取消）
        int affected = orderRepository.updateToCancelled(orderId);

        if (affected > 0) {
            // 2. 如果修改成功，说明这 10 分钟内用户确实没付款。释放座位。
            List<Seat> seats = seatRepository.findByOrderId(orderId);
            for (Seat seat : seats) {
                seat.setStatus("available");
                seat.setOrderId(null);
            }
            seatRepository.saveAll(seats);
            
            log.info("【MQ超时消费】订单 {} 超时未支付，已成功取消并释放座位", orderId);
        } else {
            // 3. 如果修改失败，说明用户已经付了钱，或者手动取消了。
            log.info("【MQ超时消费】订单 {} 已支付或已取消，无需处理", orderId);
        }
    }
}