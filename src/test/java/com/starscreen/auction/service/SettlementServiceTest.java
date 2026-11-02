package com.starscreen.auction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.mq.OutboxWriter;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.ChainNotarizeDispatcher;

/**
 * 关拍结算规则测试。
 *
 * <p>重点不是"能不能落库"，而是三条容易写错的业务判断：
 * <ol>
 *   <li>成交时必须<b>排除中标者</b>发退款事件（他的保证金要等付款后才退，D4）；</li>
 *   <li>流拍时给<b>全员</b>发退款事件（没人中标，谁都该退，D5）；</li>
 *   <li>条件 UPDATE 影响 0 行时<b>不能</b>继续生成成交单（否则重复结算）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final Long ITEM_ID = 7L;
    private static final Long LEADER_ID = 41L;

    @Mock private AuctionItemRepository itemRepository;
    @Mock private DealService dealService;
    @Mock private OutboxWriter outboxWriter;
    @Mock private AuctionAuditWriter auditWriter;
    @Mock private ChainNotarizeDispatcher chainNotarizeDispatcher;

    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(itemRepository, dealService, outboxWriter,
                auditWriter, chainNotarizeDispatcher);
    }

    @Test
    @DisplayName("成交关拍：置 SETTLED、生成成交单、只给未中标者发退款事件")
    void closeOne_sold() {
        AuctionItem item = activeItem(LEADER_ID);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(itemRepository.settle(eq(ITEM_ID), eq(AuctionStatus.SETTLED), any(), any(), eq(1))).thenReturn(1);
        when(dealService.createForItem(any(AuctionItem.class), any()))
                .thenReturn(dealWith("AD1"));

        assertTrue(settlementService.closeOne(ITEM_ID, true));

        verify(dealService).createForItem(any(AuctionItem.class), any());
        AuctionDepositEvent event = captureRefundEvent();
        assertEquals("DEPOSIT_REFUND_TRIGGER", event.getEventType());
        assertEquals(ITEM_ID, event.getItemId());
        assertEquals(LEADER_ID, event.getExcludeUserId(), "中标者必须被排除");
        assertNull(event.getIncludeUserId());
        assertEquals("SOLD_UNPAID", event.getReason());
    }

    @Test
    @DisplayName("流拍：置 UNSOLD，给全部参拍者发退款事件")
    void closeOne_unsold() {
        AuctionItem item = activeItem(null);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(itemRepository.transitStatus(eq(ITEM_ID), eq(AuctionStatus.ACTIVE),
                eq(AuctionStatus.UNSOLD), any())).thenReturn(1);

        assertTrue(settlementService.closeOne(ITEM_ID, true));

        AuctionDepositEvent event = captureRefundEvent();
        assertNull(event.getExcludeUserId());
        assertNull(event.getIncludeUserId(), "流拍应覆盖全部参拍者");
        assertEquals("UNSOLD", event.getReason());
        verify(dealService, never()).createForItem(any(), any());
    }

    @Test
    @DisplayName("重复结算：settle 影响 0 行时不生成成交单，直接返回 false")
    void closeOne_alreadySettled() {
        AuctionItem item = activeItem(LEADER_ID);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(itemRepository.settle(anyLong(), any(), any(), any(), anyInt())).thenReturn(0);

        assertFalse(settlementService.closeOne(ITEM_ID, true));

        verify(dealService, never()).createForItem(any(), any());
        verify(outboxWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非竞拍中状态：不处理")
    void closeOne_notActive() {
        AuctionItem item = activeItem(LEADER_ID);
        item.setStatus(AuctionStatus.CANCELLED);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

        assertFalse(settlementService.closeOne(ITEM_ID, true));
        verify(itemRepository, never()).settle(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("滞留修复：未开拍但已过期 → 置流拍并触发退款")
    void expireScheduled() {
        AuctionItem item = activeItem(null);
        when(itemRepository.transitStatus(eq(ITEM_ID), eq(AuctionStatus.SCHEDULED),
                eq(AuctionStatus.UNSOLD), any())).thenReturn(1);
        when(itemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

        assertTrue(settlementService.expireScheduled(ITEM_ID));

        AuctionDepositEvent event = captureRefundEvent();
        assertEquals("EXPIRED", event.getReason());
        assertNull(event.getExcludeUserId());
    }

    // ==================== 夹具 ====================

    private AuctionItem activeItem(Long leaderId) {
        AuctionItem item = new AuctionItem();
        item.setId(ITEM_ID);
        item.setItemNo("AI-TEST-7");
        item.setTitle("测试拍品");
        item.setSellerId(99L);
        item.setCurrentPrice(new BigDecimal("10200.00"));
        item.setBidIncrement(new BigDecimal("100.00"));
        item.setDeposit(new BigDecimal("500.00"));
        item.setStatus(AuctionStatus.ACTIVE);
        item.setStartTime(LocalDateTime.now().minusHours(2));
        item.setEndTime(LocalDateTime.now().minusSeconds(1));
        item.setAutoExtendSeconds(0);
        item.setExtendTimes(0);
        item.setLeaderId(leaderId);
        item.setLeaderName(leaderId == null ? null : "auc_t2");
        return item;
    }

    private AuctionDeal dealWith(String dealNo) {
        AuctionDeal deal = new AuctionDeal();
        deal.setId(1L);
        deal.setDealNo(dealNo);
        return deal;
    }

    /** 抓取发件箱写入的载荷，断言"谁该退、谁不该退"。 */
    private AuctionDepositEvent captureRefundEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).write(eq("AUCTION_ITEM"), eq(ITEM_ID), eq("DEPOSIT_REFUND_TRIGGER"),
                any(String.class), payload.capture());
        return (AuctionDepositEvent) payload.getValue();
    }
}
