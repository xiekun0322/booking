package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.dto.CreateOrderRequest;
import com.starscreen.entity.Order;
import com.starscreen.service.OrderService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 【功能】订单 REST API（前台使用）。
 *         下单、支付、取消、查询我的订单。
 *
 * 【路径前缀】/api/orders
 *
 * 【调用方】
 *          - seat.html:    POST /api/orders              确认选座
 *          - movie-order.html: POST /api/orders/{id}/pay      支付
 *                              POST /api/orders/{id}/cancel   取消
 *          - 未来 REST 客户端：GET /api/orders 我的订单列表
 *
 * 【被调用】
 *          OrderService → OrderRepository / SeatRepository
 *
 * 【鉴权】
 *          每个方法从 HttpSession 拿 userId，
 *          null 时统一返回 Result.error(401, "请先登录")。
 *          LoginInterceptor 兜底，但业务代码仍然做了一次判空，双保险。
 *
 * 【返回格式】统一 Result<Order> / Result<List<Order>>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 【功能】查询当前用户的全部订单。
     * 【调用链】GET /api/orders
     *           → session.getAttribute("userId")
     *           → orderService.listPagedByUser(userId, "all", 0, 1000)
     * 【⚠️ 隐患】size=1000 硬编码，订单多时会被截断。
     *            建议改为分页参数。
     */
    @GetMapping
    public Result<List<Order>> list(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(orderService.listPagedByUser(userId, "all", 0, 1000).getContent());
    }

    /**
     * 【功能】查询单笔订单详情。
     * 【调用链】GET /api/orders/{id}
     * 【越权检查】
     *          - order 不存在 → 404
     *          - userId 不为 null 且不匹配 → 403
     *          - userId 为 null 时【不拦截】，会返回订单详情！
     *            虽然 LoginInterceptor 会拦住未登录请求，
     *            但业务代码更严谨的写法应该加 userId == null 判空。
     */
    @GetMapping("/{id}")
    public Result<Order> detail(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        Order order = orderService.getById(id);
        if (order == null) return Result.error(404, "订单不存在");
        if (userId != null && !userId.equals(order.getUserId())) {
            return Result.error(403, "无权查看该订单");
        }
        return Result.success(order);
    }

    /**
     * 【功能】创建订单（锁座）。
     * 【调用链】
     *   seat.html: confirmBtn 点击
     *   → POST /api/orders {scheduleId, seats}
     *   → @Valid 校验 CreateOrderRequest
     *   → session 取 userId
     *   → orderService.createOrder(userId, scheduleId, seats)
     *   → 前端拿到 order.id 后跳转 /order/{id}
     * 【异常】
     *   - 参数校验失败 → 400 "请至少选择一个座位" 等
     *   - 座位被抢 → 500 "座位已被选走：3排5座"
     */
    @PostMapping
    public Result<Order> create(@RequestBody @Valid CreateOrderRequest req, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(orderService.createOrder(userId, req.getScheduleId(), req.getSeats()));
    }

    /**
     * 【功能】支付订单。
     * 【调用链】movie-order.html: payBtn 点击
     *           → POST /api/orders/{id}/pay
     *           → orderService.pay(userId, id)
     *           → 前端 reload 页面显示取票码
     * 【异常】"订单已支付" / "订单状态不允许支付" / "无权操作"
     */
    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(orderService.pay(userId, id));
    }

    /**
     * 【功能】取消订单。
     * 【调用链】movie-order.html: cancelBtn 点击
     *           → POST /api/orders/{id}/cancel
     *           → orderService.cancel(userId, id)
     * 【异常】"已支付订单不可取消" / "无权操作"
     */
    @PostMapping("/{id}/cancel")
    public Result<Order> cancel(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) return Result.error(401, "请先登录");
        return Result.success(orderService.cancel(userId, id));
    }
}