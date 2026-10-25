package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 【功能】订单实体，映射数据库表 t_order。
 *         存储一次购票的完整快照：影片名、影院、场次、座位、金额、取票码等。
 *         冗余了电影名、影院名等信息（下单时快照），即使电影被删也不影响历史订单。
 *
 * 【表结构】
 *          t_order(id, order_no, user_id, schedule_id, movie_id,
 *                  movie_title, cinema_name, hall_name, show_time, seats,
 *                  total_price, status, create_time, pay_time, ticket_code)
 *
 * 【状态机】
 *          pending → paid       ：用户支付成功
 *          pending → cancelled  ：用户取消 / 超时自动取消
 *          paid    → (不可取消)
 *
 * 【调用方】
 *          - OrderRepository          持久化
 *          - OrderService             下单/支付/取消/超时
 *          - OrderController          REST API
 *          - PageController           订单列表 / 支付页
 *
 * 【被调用】
 *          无（叶子实体）
 *
 * 【⚠️ 设计提醒】
 *          - totalPrice 用 Double：钱建议用 BigDecimal
 *          - createTime/payTime/showTime 是 String：排序统计靠字符串比较
 *          - orderNo 唯一但生成算法有碰撞风险（时间戳 + 3 位随机）
 */
@Data
@Entity
@Table(name = "t_order")
public class Order {

    /** 主键，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号，唯一，如 "MO1696123456789012" */
    @Column(unique = true, length = 50)
    private String orderNo;

    /** 下单用户 ID（用于越权校验：只能看/操作自己的订单） */
    private Long userId;

    /** 场次 ID */
    private Long scheduleId;

    /** 电影 ID（快照，便于统计） */
    private Long movieId;

    /** 电影名（快照） */
    @Column(length = 100)
    private String movieTitle;

    /** 影院名（快照） */
    @Column(length = 100)
    private String cinemaName;

    /** 影厅名（快照） */
    @Column(length = 50)
    private String hallName;

    /** 场次时间，格式 "yyyy-MM-dd HH:mm" */
    @Column(length = 50)
    private String showTime;

    /** 座位列表，逗号分隔，如 "3排5座,3排6座" */
    @Column(length = 500)
    private String seats;

    /** 总价 = 单价 × 座位数 */
    private Double totalPrice;

    /** 状态：pending / paid / cancelled */
    @Column(length = 20)
    private String status;

    /** 下单时间，格式 "yyyy-MM-dd HH:mm:ss"（用于超时判定） */
    @Column(length = 30)
    private String createTime;

    /** 支付时间，格式 "yyyy-MM-dd HH:mm:ss"（用于按天票房统计） */
    @Column(length = 30)
    private String payTime;

    /** 取票码，支付成功后生成，格式 "ABCD-1234" */
    @Column(length = 20)
    private String ticketCode;
}