package com.starscreen.auction.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starscreen.auction.dto.AuctionItemDTO;
import com.starscreen.auction.dto.BidDTO;
import com.starscreen.auction.dto.BidRequest;
import com.starscreen.auction.dto.BidResultDTO;
import com.starscreen.auction.dto.CreateAuctionItemRequest;
import com.starscreen.auction.dto.PageResult;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.service.AuctionService;
import com.starscreen.auction.service.BidService;
import com.starscreen.common.BusinessException;
import com.starscreen.common.Result;
import com.starscreen.common.RoleConstants;
import com.starscreen.common.SessionConstants;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 拍卖主接口：发布、列表、详情、出价、出价记录、取消。
 *
 * <p>资金相关接口（报名缴保证金、我的成交、成交款支付）在 {@code AuctionTradeController}，
 * 两者都挂在 {@code /api/auction} 下，但职责分开：本类只管"拍"，那边只管"钱"。
 *
 * <p><b>身份只从 Session 取</b>：不信任请求体里的 userId。
 * 前端传入的 userId 是可以随便改的，用它会变成"替别人出价"。
 */
@RestController
@RequestMapping("/api/auction")
@RequiredArgsConstructor
public class AuctionController {

    /** 列表页单页上限，防止 size=100000 把数据库拖死。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final AuctionService auctionService;
    private final BidService bidService;

    // ==================== 发布 ====================

    /** 发布拍品（登录用户即可，卖家取当前登录账号）。 */
    @PostMapping("/items")
    public Result<AuctionItemDTO> create(@Valid @RequestBody CreateAuctionItemRequest request,
                                         HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(auctionService.create(request, userId, request.getSellerName()));
    }

    // ==================== 查询（匿名可读）====================

    /**
     * 拍品列表。
     *
     * @param status 状态过滤，缺省为 ACTIVE；传空串按缺省处理（不能把空串当 null 去查，
     *               否则会变成 {@code status is null}，列表永远为空——这是"待拍版什么都没有"
     *               最常见的原因）
     */
    @GetMapping("/items")
    public Result<PageResult<AuctionItemDTO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        AuctionStatus parsed = parseStatus(status);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1) - 1;
        return Result.success(auctionService.page(parsed, safePage, safeSize));
    }

    @GetMapping("/items/{id}")
    public Result<AuctionItemDTO> detail(@PathVariable Long id) {
        return Result.success(auctionService.detail(id));
    }

    @GetMapping("/items/{id}/bids")
    public Result<PageResult<BidDTO>> bids(@PathVariable Long id,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1) - 1;
        return Result.success(auctionService.bidHistory(id, safePage, safeSize));
    }

    // ==================== 出价 ====================

    @PostMapping("/items/{id}/bids")
    public Result<BidResultDTO> bid(@PathVariable Long id,
                                    @Valid @RequestBody BidRequest request,
                                    HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        return Result.success(bidService.bid(id, userId, request.getAmount(),
                request.getIdempotencyKey(), clientIp(httpRequest)));
    }

    // ==================== 取消 ====================

    @PostMapping("/items/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        auctionService.cancel(id, userId, isAdmin(httpRequest));
        return Result.success();
    }

    // ==================== 内部 ====================

    /**
     * 状态解析。
     *
     * <p>空串按"未传"处理。这一步不能省：{@code @RequestParam(defaultValue="ACTIVE")}
     * 只对"完全没这个参数"生效，{@code ?status=} 会传进来一个空串，
     * 再转枚举就得到 null，查询条件退化成 {@code status is null}，列表永远空。
     */
    private AuctionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return AuctionStatus.ACTIVE;
        }
        try {
            return AuctionStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的状态：" + raw
                    + "（可选 DRAFT/SCHEDULED/ACTIVE/SETTLED/UNSOLD/DEFAULTED/CANCELLED）");
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (Long) session.getAttribute(SessionConstants.USER_ID);
    }

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object role = session.getAttribute(SessionConstants.ROLE);
        return role != null && RoleConstants.ADMIN.equals(String.valueOf(role));
    }

    private String clientIp(HttpServletRequest request) {
        // 反代后取 X-Forwarded-For 第一段，否则拿到的都是网关 IP，出价审计毫无意义
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
