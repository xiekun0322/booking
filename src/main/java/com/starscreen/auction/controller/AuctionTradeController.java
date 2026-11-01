package com.starscreen.auction.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.starscreen.auction.dto.DealDTO;
import com.starscreen.auction.dto.DepositDTO;
import com.starscreen.auction.dto.PayTicketDTO;
import com.starscreen.auction.service.DealService;
import com.starscreen.auction.service.DepositService;
import com.starscreen.common.Result;
import com.starscreen.common.SessionConstants;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 拍卖交易域接口：报名缴保证金、我的保证金、我的成交、成交款支付。
 *
 * <p><b>与票务（选座）接口完全分离</b>：这些路径只读写拍卖库，
 * 不碰 {@code t_order} / {@code t_seat}，也不调用票务的支付回调。
 * 两边唯一的共享是"同一个支付通道"（在模拟实现里就是 {@code MockPayService}）。
 *
 * <p><b>身份只从 Session 取</b>，不信任请求体里的 userId；未登录返回 401。
 * 出价相关的接口仍在 {@link AuctionController}，这里只管钱。
 */
@RestController
@RequestMapping("/api/auction")
@RequiredArgsConstructor
public class AuctionTradeController {

    private final DepositService depositService;
    private final DealService dealService;

    /** 报名参拍：创建保证金记录并返回模拟收银台凭证。 */
    @PostMapping("/items/{itemId}/enroll")
    public Result<PayTicketDTO> enroll(@PathVariable Long itemId, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(depositService.enroll(itemId, userId));
    }

    /** 我的保证金（含状态与下一步动作提示）。 */
    @GetMapping("/deposits/mine")
    public Result<List<DepositDTO>> myDeposits(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(depositService.myDeposits(userId));
    }

    /** 重新支付保证金（支付失败 / 取消后）。 */
    @PostMapping("/deposits/{id}/pay")
    public Result<PayTicketDTO> retryPay(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(depositService.retryPay(id, userId));
    }

    /** 我的成交（读 auction_deal）。 */
    @GetMapping("/deals/mine")
    public Result<List<DealDTO>> myDeals(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(dealService.myDeals(userId));
    }

    /** 成交单详情。 */
    @GetMapping("/deals/{id}")
    public Result<DealDTO> dealDetail(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(dealService.detail(id));
    }

    /** 按拍品查成交单（拍品详情页用；无成交单返回 404）。 */
    @GetMapping("/items/{itemId}/deal")
    public Result<DealDTO> dealByItem(@PathVariable Long itemId) {
        return Result.success(dealService.byItem(itemId));
    }

    /** 支付成交款。 */
    @PostMapping("/deals/{id}/pay")
    public Result<PayTicketDTO> payDeal(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(dealService.pay(id, userId));
    }

    // ==================== 内部 ====================

    private Long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (Long) session.getAttribute(SessionConstants.USER_ID);
    }
}
