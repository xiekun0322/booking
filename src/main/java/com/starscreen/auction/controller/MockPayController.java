package com.starscreen.auction.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starscreen.auction.dto.MockPayNotifyRequest;
import com.starscreen.auction.dto.PayTicketDTO;
import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.MockPayResult;
import com.starscreen.auction.pay.MockPayService;
import com.starscreen.common.BusinessException;
import com.starscreen.common.Result;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 模拟支付网关的对外接口（仅用于开发/联调/验收，生产必须关闭）。
 *
 * <p><b>开关</b>：{@code app.auction.mock-pay-enabled=false} 时整个 Controller 不装配。
 * 这类"能直接把订单标成已支付"的接口留在生产环境，等于给了一个免费提货通道——
 * 上线前必须显式关掉，而不是靠"没人知道路径"。
 *
 * <p><b>覆盖的四种通道行为</b>：
 * <ol>
 *   <li><b>模拟下单</b>：由 {@code POST /api/auction/items/{id}/enroll}（保证金）或
 *       {@code POST /api/auction/deals/{id}/pay}（成交款）触发，返回 {@code payNo} 与收银台地址；</li>
 *   <li><b>发起支付</b>：{@code GET /mock-pay/checkout?payNo=} 查看收银台状态；</li>
 *   <li><b>支付成功</b>：{@code POST /mock-pay/{payNo}/success}；</li>
 *   <li><b>支付失败 / 用户取消</b>：{@code POST /mock-pay/{payNo}/fail|cancel}。</li>
 * </ol>
 *
 * <p><b>为什么回调要单独一个 notify 接口</b>：真实网关只会往一个固定地址 POST 通知，
 * 成功/失败由报文里的字段区分。保留这个统一入口，接真网关时只需改这一个方法的验参与解析。
 */
@RestController
@RequestMapping("/api/auction/mock-pay")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auction", name = "mock-pay-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockPayController {

    private static final Logger log = LoggerFactory.getLogger(MockPayController.class);

    private final MockPayService payService;

    /** 模拟收银台：查看这笔支付单当前状态与可执行动作。 */
    @GetMapping("/checkout")
    public Result<PayTicketDTO> checkout(@RequestParam String payNo) {
        return Result.success(payService.ticket(payNo));
    }

    /** 查询支付单（含通道状态，便于验证状态流转）。 */
    @GetMapping("/{payNo}")
    public Result<AuctionPayOrder> query(@PathVariable String payNo) {
        return Result.success(payService.get(payNo));
    }

    /** 统一回调入口（对齐真实网关的 notify）。 */
    @PostMapping("/notify")
    public Result<AuctionPayOrder> notify(@RequestBody @Valid MockPayNotifyRequest req) {
        MockPayResult result = parseResult(req.getResult());
        log.info("【模拟支付】收到回调 payNo={} result={} reason={}", req.getPayNo(), result, req.getReason());
        return Result.success(payService.notify(req.getPayNo(), result, req.getReason()));
    }

    /** 便捷入口：模拟支付成功。 */
    @PostMapping("/{payNo}/success")
    public Result<AuctionPayOrder> success(@PathVariable String payNo) {
        return Result.success(payService.notify(payNo, MockPayResult.SUCCESS, null));
    }

    /** 便捷入口：模拟支付失败。 */
    @PostMapping("/{payNo}/fail")
    public Result<AuctionPayOrder> fail(@PathVariable String payNo,
                                        @RequestParam(required = false) String reason) {
        return Result.success(payService.notify(payNo, MockPayResult.FAIL, reason));
    }

    /** 便捷入口：模拟用户取消支付。 */
    @PostMapping("/{payNo}/cancel")
    public Result<AuctionPayOrder> cancel(@PathVariable String payNo,
                                          @RequestParam(required = false) String reason) {
        return Result.success(payService.notify(payNo, MockPayResult.CANCEL, reason));
    }

    private MockPayResult parseResult(String raw) {
        try {
            return MockPayResult.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的支付结果：" + raw + "（可选 SUCCESS / FAIL / CANCEL）");
        }
    }
}
