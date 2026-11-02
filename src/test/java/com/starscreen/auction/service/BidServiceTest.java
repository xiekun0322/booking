package com.starscreen.auction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.dto.BidResultDTO;
import com.starscreen.auction.entity.AuctionBid;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.enums.BidOutcome;
import com.starscreen.auction.repository.AuctionBidRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.BidderNameResolver;

/**
 * 出价核心规则测试。
 *
 * <p>只测"分支决策"与"是否触发了正确的写动作"，不测 SQL 本身——
 * 条件 UPDATE 的并发语义由数据库保证，用 mock 断言 placeBid 的影响行数即可覆盖调用方逻辑。
 */
@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    private static final Long ITEM_ID = 1L;
    private static final Long BIDDER_ID = 41L;
    private static final Long SELLER_ID = 99L;

    @Mock private AuctionItemRepository itemRepository;
    @Mock private AuctionBidRepository bidRepository;
    @Mock private AuctionAuditWriter auditWriter;
    @Mock private BidderNameResolver bidderNameResolver;
    @Mock private BidRateLimiter rateLimiter;
    @Mock private DepositService depositService;
    @Mock private SettlementService settlementService;

    private AuctionProperties properties;
    private BidService bidService;

    @BeforeEach
    void setUp() {
        properties = new AuctionProperties();
        properties.setMaxExtendTimes(30);
        bidService = new BidService(itemRepository, bidRepository, auditWriter, bidderNameResolver,
                rateLimiter, depositService, settlementService, properties);
    }

    // ==================== 成功路径 ====================

    @Test
    @DisplayName("出价成功：价格被更新为出价额，返回 SUCCESS 并落一条有效流水")
    void bid_success() {
        AuctionItem item = activeItem();
        givenItem(item);
        givenQualified();
        givenRateLimitOk();
        // placeBid 成功时由数据库改价，这里模拟同样的效果，供后续重新读取
        when(itemRepository.placeBid(eq(ITEM_ID), eq(BIDDER_ID), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    item.setCurrentPrice(new BigDecimal("1100.00"));
                    item.setLeaderId(BIDDER_ID);
                    item.setLeaderName("auc_t2");
                    return 1;
                });
        givenBidSaved();

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-1", "1.1.1.1");

        assertEquals(BidOutcome.SUCCESS, result.getOutcome());
        assertTrue(result.isAccepted());
        assertEquals(0, new BigDecimal("1100.00").compareTo(result.getCurrentPrice()));
        assertEquals(BIDDER_ID, result.getLeaderId());
        verify(bidRepository).save(any(AuctionBid.class));
    }

    @Test
    @DisplayName("一口价：出价超过一口价时按一口价成交，并立即关拍")
    void bid_buyout_closesImmediately() {
        AuctionItem item = activeItem();
        item.setBuyoutPrice(new BigDecimal("5000.00"));
        givenItem(item);
        givenQualified();
        givenRateLimitOk();
        when(itemRepository.placeBid(eq(ITEM_ID), eq(BIDDER_ID), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    item.setCurrentPrice(new BigDecimal("5000.00"));
                    return 1;
                });
        givenBidSaved();

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("6000.00"), "key-buy", "1.1.1.1");

        assertEquals(BidOutcome.BUYOUT, result.getOutcome());
        assertTrue(result.isAccepted());
        // 关键：成交价必须被压到一口价，而不是用户输入的 6000
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(itemRepository).placeBid(eq(ITEM_ID), eq(BIDDER_ID), anyString(), amount.capture(), any());
        assertEquals(0, new BigDecimal("5000.00").compareTo(amount.getValue()));
        // 成交与关拍同事务，必须立刻收口
        verify(settlementService).closeOne(ITEM_ID, false);
    }

    // ==================== 拒绝路径 ====================

    @Test
    @DisplayName("低于最低加价：拒绝且不改价，但留下被拒流水")
    void bid_belowMinimum() {
        givenItem(activeItem());
        givenQualified();

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1050.00"), "key-2", "1.1.1.1");

        assertEquals(BidOutcome.BELOW_MINIMUM, result.getOutcome());
        assertFalse(result.isAccepted());
        verify(itemRepository, never()).placeBid(any(), any(), any(), any(), any());
        ArgumentCaptor<AuctionBid> captor = ArgumentCaptor.forClass(AuctionBid.class);
        verify(bidRepository).save(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getAccepted());
        assertEquals("BELOW_MINIMUM", captor.getValue().getRejectedReason());
    }

    @Test
    @DisplayName("未缴保证金：返回 DEPOSIT_REQUIRED（可恢复，引导报名）")
    void bid_depositRequired() {
        givenItem(activeItem());
        when(depositService.required(any(AuctionItem.class))).thenReturn(true);
        when(depositService.hasQualification(BIDDER_ID, ITEM_ID)).thenReturn(false);

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-3", "1.1.1.1");

        assertEquals(BidOutcome.DEPOSIT_REQUIRED, result.getOutcome());
        assertTrue(result.getMessage().contains("保证金"));
        verify(itemRepository, never()).placeBid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非竞拍中：返回 NOT_BIDDABLE")
    void bid_notActive() {
        AuctionItem item = activeItem();
        item.setStatus(AuctionStatus.SETTLED);
        givenItem(item);

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-4", "1.1.1.1");

        assertEquals(BidOutcome.NOT_BIDDABLE, result.getOutcome());
        verify(itemRepository, never()).placeBid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("自买自卖：返回 SELF_BID_FORBIDDEN")
    void bid_selfBidForbidden() {
        givenItem(activeItem());

        BidResultDTO result = bidService.bid(ITEM_ID, SELLER_ID, new BigDecimal("1100.00"), "key-5", "1.1.1.1");

        assertEquals(BidOutcome.SELF_BID_FORBIDDEN, result.getOutcome());
    }

    @Test
    @DisplayName("并发被抢先：placeBid 影响 0 行时返回 BELOW_MINIMUM，不产生有效出价")
    void bid_outpacedByConcurrent() {
        AuctionItem item = activeItem();
        givenItem(item);
        givenQualified();
        givenRateLimitOk();
        when(itemRepository.placeBid(any(), any(), any(), any(), any())).thenReturn(0);

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-6", "1.1.1.1");

        assertEquals(BidOutcome.BELOW_MINIMUM, result.getOutcome());
        assertFalse(result.isAccepted());
    }

    // ==================== 幂等与顺延 ====================

    @Test
    @DisplayName("幂等重放：同 key 命中已有记录时直接返回首次结果，不重复加价")
    void bid_idempotentReplay() {
        AuctionItem item = activeItem();
        item.setCurrentPrice(new BigDecimal("1200.00"));
        item.setLeaderId(7L);
        AuctionBid existing = new AuctionBid();
        existing.setId(88L);
        existing.setItemId(ITEM_ID);
        existing.setBidderId(BIDDER_ID);
        existing.setAmount(new BigDecimal("1100.00"));
        existing.setAccepted(true);
        when(bidRepository.findByIdempotencyKey("key-7")).thenReturn(Optional.of(existing));
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-7", "1.1.1.1");

        assertTrue(result.isAccepted());
        assertTrue(result.getMessage().contains("重复"));
        verify(itemRepository, never()).placeBid(any(), any(), any(), any(), any());
        verify(bidRepository, never()).save(any(AuctionBid.class));
    }

    @Test
    @DisplayName("临近结束出价：剩余不足延时窗口时顺延结束时间")
    void bid_extendsWhenCloseToEnd() {
        AuctionItem item = activeItem();
        item.setAutoExtendSeconds(300);
        item.setExtendTimes(0);
        item.setEndTime(LocalDateTime.now().plusSeconds(60));
        givenItem(item);
        givenQualified();
        givenRateLimitOk();
        when(itemRepository.placeBid(any(), any(), any(), any(), any())).thenReturn(1);
        givenBidSaved();
        when(itemRepository.extendEndTime(any(), any(), any(), any(), any(Integer.class))).thenReturn(1);

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-8", "1.1.1.1");

        assertTrue(result.isExtended());
        verify(itemRepository).extendEndTime(eq(ITEM_ID), any(), any(), any(), eq(30));
    }

    @Test
    @DisplayName("顺延次数达上限：不再延长，但出价仍然成功")
    void bid_noExtendWhenLimitReached() {
        AuctionItem item = activeItem();
        item.setAutoExtendSeconds(300);
        item.setExtendTimes(30);
        item.setEndTime(LocalDateTime.now().plusSeconds(60));
        givenItem(item);
        givenQualified();
        givenRateLimitOk();
        when(itemRepository.placeBid(any(), any(), any(), any(), any())).thenReturn(1);
        givenBidSaved();

        BidResultDTO result = bidService.bid(ITEM_ID, BIDDER_ID, new BigDecimal("1100.00"), "key-9", "1.1.1.1");

        assertTrue(result.isAccepted());
        assertFalse(result.isExtended());
        verify(itemRepository, never()).extendEndTime(any(), any(), any(), any(), any(Integer.class));
    }

    // ==================== 夹具 ====================

    private AuctionItem activeItem() {
        AuctionItem item = new AuctionItem();
        item.setId(ITEM_ID);
        item.setItemNo("AI-TEST");
        item.setTitle("测试拍品");
        item.setSellerId(SELLER_ID);
        item.setStartPrice(new BigDecimal("1000.00"));
        item.setCurrentPrice(new BigDecimal("1000.00"));
        item.setBidIncrement(new BigDecimal("100.00"));
        item.setDeposit(new BigDecimal("500.00"));
        item.setStatus(AuctionStatus.ACTIVE);
        item.setStartTime(LocalDateTime.now().minusHours(1));
        item.setEndTime(LocalDateTime.now().plusHours(1));
        item.setAutoExtendSeconds(0);
        item.setExtendTimes(0);
        return item;
    }

    private void givenItem(AuctionItem item) {
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
    }

    private void givenQualified() {
        when(depositService.required(any(AuctionItem.class))).thenReturn(true);
        when(depositService.hasQualification(BIDDER_ID, ITEM_ID)).thenReturn(true);
        when(bidderNameResolver.resolve(BIDDER_ID)).thenReturn("auc_t2");
    }

    /** 限流只在"价格校验通过之后"才会被调用，因此单独一个夹具，避免多余打桩。 */
    private void givenRateLimitOk() {
        when(rateLimiter.tryAcquire(BIDDER_ID)).thenReturn(true);
    }

    private void givenBidSaved() {
        when(bidRepository.save(any(AuctionBid.class))).thenAnswer(inv -> {
            AuctionBid bid = inv.getArgument(0);
            bid.setId(new AtomicLong(1).get());
            return bid;
        });
    }
}
