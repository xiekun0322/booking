package com.starscreen.auction.web;

import java.util.regex.Pattern;

/**
 * 拍卖接口的匿名放行规则。
 *
 * <p>抽成独立类的理由：放行规则是<b>安全边界</b>，分散在拦截器和配置里各写一份，
 * 迟早会分叉——一份放开了写接口，另一份没放开，出问题时看哪份都像对的。
 * 这里只有一份判断，拦截器与配置都调它。
 *
 * <p><b>只放行 GET，且只放行明确的读路径</b>：用正则精确匹配，
 * 不用 {@code startsWith("/api/auction")}——那种写法会把
 * {@code POST /api/auction/items}（发布拍品）和 {@code /deals/mine}（我的成交）一起放出去。
 */
public final class AuctionPublicAccess {

    /** 拍品列表 / 拍品详情 / 出价记录 / 拍品成交单。 */
    private static final Pattern PUBLIC_READ = Pattern.compile(
            "^/api/auction/items(?:/\\d+)?(?:/(?:bids|deal))?/?$");

    /** 模拟支付通道前缀：收银台查询 + 网关回调。 */
    private static final String MOCK_PAY_PREFIX = "/api/auction/mock-pay";

    private AuctionPublicAccess() {
    }

    /**
     * 是否允许匿名读取。
     *
     * @param method HTTP 方法
     * @param uri    请求路径（不含 query string）
     */
    public static boolean isPublicRead(String method, String uri) {
        if (method == null || uri == null) {
            return false;
        }
        // 只有 GET 是读；HEAD/OPTIONS 交给框架处理，不在这里放行
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        return PUBLIC_READ.matcher(uri).matches();
    }

    /**
     * 是否属于模拟支付通道。
     *
     * <p><b>为什么必须匿名放行</b>：支付网关的服务端回调不带用户 session——
     * 真实网关也是这样（它拿不到你的 Cookie，只会带签名）。如果这里要求登录，
     * 回调永远进不来，表现为"用户付了钱但订单一直待支付"。
     *
     * <p><b>放行不等于敞开</b>：这道口子的安全性由 {@code app.auction.mock-pay-enabled}
     * 开关兜底——关闭后 {@code MockPayController} 根本不装配，路径直接 404。
     * 生产环境必须关闭；这是"用配置控制攻击面"，而不是"靠没人知道路径"。
     */
    public static boolean isMockPay(String uri) {
        return uri != null && uri.startsWith(MOCK_PAY_PREFIX);
    }
}
