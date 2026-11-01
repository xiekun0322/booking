-- =============================================================================
-- 拍卖库（auction）全量结构
--
-- 与票务库（starscreen）分开：拍卖是热点写（每秒几十次出价），票务是低频写，
-- 混在一个库里，一次出价风暴会把选座下单的连接一起拖慢。
--
-- 执行方式（应用账号没有 DDL 权限，必须用 root）：
--   mysql -u root -p --default-character-set=utf8mb4 < scripts/auction-schema.sql
--
-- 说明：
--   * 全部 CREATE TABLE IF NOT EXISTS，可重复执行；
--   * 所有金额统一 DECIMAL(18,2)，与 auction_deal.deal_price 精度一致，
--     不与 t_order.total_price(10,2) 混用——两域金额不互通，不需要对齐；
--   * 状态列一律 VARCHAR 存枚举名（不用 MySQL ENUM）：加状态时不用 ALTER TABLE。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS auction
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE auction;

-- -----------------------------------------------------------------------------
-- 1. 拍品
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_item (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    item_no             VARCHAR(40)   NOT NULL COMMENT '业务编号（雪花，不暴露自增 ID）',
    title               VARCHAR(200)  NOT NULL,
    description         VARCHAR(2000) DEFAULT NULL,
    category            VARCHAR(40)   DEFAULT NULL COMMENT 'MOVIE_PROP / POSTER / TICKET_PACKAGE ...',
    seller_id           BIGINT        DEFAULT NULL,
    seller_name         VARCHAR(100)  DEFAULT NULL,
    start_price         DECIMAL(18,2) NOT NULL,
    current_price       DECIMAL(18,2) NOT NULL COMMENT '当前价，出价用条件 UPDATE 改它',
    buyout_price        DECIMAL(18,2) DEFAULT NULL COMMENT '一口价，NULL 表示不设',
    bid_increment       DECIMAL(18,2) NOT NULL COMMENT '最小加价幅度',
    deposit             DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '发布时固化的保证金快照',
    status              VARCHAR(20)   NOT NULL COMMENT 'DRAFT/SCHEDULED/ACTIVE/SETTLED/UNSOLD/DEFAULTED/CANCELLED',
    start_time          DATETIME      DEFAULT NULL,
    end_time            DATETIME      DEFAULT NULL,
    auto_extend_seconds INT           NOT NULL DEFAULT 0 COMMENT '0 表示不自动延时',
    extend_times        INT           NOT NULL DEFAULT 0 COMMENT '已顺延次数，受 app.auction.max-extend-times 限制',
    leader_id           BIGINT        DEFAULT NULL,
    leader_name         VARCHAR(100)  DEFAULT NULL,
    final_price         DECIMAL(18,2) DEFAULT NULL,
    settle_time         DATETIME      DEFAULT NULL,
    version             INT           NOT NULL DEFAULT 0,
    create_time         DATETIME      DEFAULT NULL,
    update_time         DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_no (item_no),
    KEY idx_item_status_end (status, end_time),
    KEY idx_item_status_start (status, start_time),
    KEY idx_item_seller (seller_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍卖拍品';

-- -----------------------------------------------------------------------------
-- 2. 出价流水（只追加；被拒绝的出价也要留）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_bid (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    item_id          BIGINT        NOT NULL,
    bidder_id        BIGINT        NOT NULL,
    bidder_name      VARCHAR(100)  DEFAULT NULL,
    amount           DECIMAL(18,2) NOT NULL,
    idempotency_key  VARCHAR(64)   NOT NULL COMMENT '客户端生成，幂等键',
    accepted         BIT(1)        NOT NULL DEFAULT b'1',
    rejected_reason  VARCHAR(40)   DEFAULT NULL COMMENT 'BidOutcome 枚举名',
    chain_tx_hash    VARCHAR(128)  DEFAULT NULL COMMENT '存证哈希',
    bid_time         DATETIME      NOT NULL,
    client_ip        VARCHAR(64)   DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bid_idem (idempotency_key),
    KEY idx_bid_item (item_id, bid_time),
    KEY idx_bid_bidder (bidder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出价流水';

-- -----------------------------------------------------------------------------
-- 3. 审计日志（只追加，用于事后追责）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_audit_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    biz_type    VARCHAR(40) NOT NULL COMMENT 'BID / SETTLE / ITEM / DEPOSIT / DEAL',
    biz_id      BIGINT      DEFAULT NULL,
    actor_id    BIGINT      NOT NULL DEFAULT 0,
    actor_type  VARCHAR(20) DEFAULT NULL COMMENT 'USER / ADMIN / SYSTEM',
    action      VARCHAR(60) DEFAULT NULL,
    detail      LONGTEXT,
    client_ip   VARCHAR(64) DEFAULT NULL,
    create_time DATETIME    DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_biz (biz_type, biz_id),
    KEY idx_audit_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍卖审计日志';

-- -----------------------------------------------------------------------------
-- 4. 发件箱（事务内写，relay 异步投递）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(40)  DEFAULT NULL COMMENT 'AUCTION_ITEM / AUCTION_DEAL',
    aggregate_id   BIGINT       DEFAULT NULL,
    event_type     VARCHAR(40)  NOT NULL COMMENT 'DEPOSIT_REFUND_TRIGGER / DEPOSIT_FORFEIT',
    routing_key    VARCHAR(80)  NOT NULL COMMENT '入表时定死，重投不重算',
    payload        LONGTEXT     NOT NULL COMMENT '入表时序列化，重投原样发',
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING/SENT/DEAD',
    retry_count    INT          NOT NULL DEFAULT 0,
    next_retry_at  DATETIME     DEFAULT NULL COMMENT '退避后的下次可投递时间',
    locked_until   DATETIME     DEFAULT NULL COMMENT '租约到期时间（不设 SENDING 状态）',
    last_error     VARCHAR(500) DEFAULT NULL,
    create_time    DATETIME     DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_dispatch (status, next_retry_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍卖发件箱（Outbox）';

-- -----------------------------------------------------------------------------
-- 5. 保证金（一人一标的一条）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_deposit (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    item_id           BIGINT        NOT NULL,
    amount            DECIMAL(18,2) NOT NULL COMMENT '实缴金额快照，退款只按这个数',
    status            VARCHAR(20)   NOT NULL COMMENT 'PENDING_PAY/HELD/PAY_FAILED/CLOSED/REFUNDING/REFUNDED/REFUND_DEAD/FORFEITED',
    pay_no            VARCHAR(40)   DEFAULT NULL COMMENT '对应 auction_pay_order.pay_no，原路退回用',
    pay_time          DATETIME      DEFAULT NULL,
    refund_trigger_at DATETIME      DEFAULT NULL COMMENT '非空即该退（由异步消费者写入）',
    refund_time       DATETIME      DEFAULT NULL,
    forfeit_time      DATETIME      DEFAULT NULL,
    retry_count       INT           NOT NULL DEFAULT 0,
    next_retry_at     DATETIME      DEFAULT NULL,
    last_error        VARCHAR(500)  DEFAULT NULL,
    create_time       DATETIME      DEFAULT NULL,
    update_time       DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deposit (user_id, item_id),
    KEY idx_deposit_item_status (item_id, status),
    KEY idx_deposit_refund (status, refund_trigger_at),
    KEY idx_deposit_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍品保证金';

-- -----------------------------------------------------------------------------
-- 6. 成交单（与拍卖同库，关拍时同事务生成）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_deal (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    deal_no           VARCHAR(40)   NOT NULL COMMENT '成交单号（雪花，AD 前缀）',
    item_id           BIGINT        NOT NULL COMMENT '一拍品一成交单；重拍是新建 item',
    item_no           VARCHAR(40)   DEFAULT NULL,
    buyer_id          BIGINT        NOT NULL,
    buyer_name        VARCHAR(100)  DEFAULT NULL,
    deal_price        DECIMAL(18,2) NOT NULL,
    deposit_amount    DECIMAL(18,2) DEFAULT 0.00 COMMENT '中标者保证金（仅记录）',
    status            VARCHAR(20)   NOT NULL COMMENT 'PENDING_PAYMENT/PAID/COMPLETED/CANCELLED',
    pay_deadline      DATETIME      NOT NULL COMMENT '到期未付即悔拍',
    pay_time          DATETIME      DEFAULT NULL,
    cancel_reason     VARCHAR(40)   DEFAULT NULL COMMENT 'PAY_TIMEOUT/USER_CANCEL/ADMIN_CANCEL',
    cancel_time       DATETIME      DEFAULT NULL,
    external_order_no VARCHAR(64)   DEFAULT NULL COMMENT '预留：同步 t_order 时启用，当前恒 NULL',
    version           INT           NOT NULL DEFAULT 0,
    create_time       DATETIME      DEFAULT NULL,
    update_time       DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deal_no (deal_no),
    UNIQUE KEY uk_deal_item (item_id),
    KEY idx_deal_status_deadline (status, pay_deadline),
    KEY idx_deal_buyer (buyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍卖成交单';

-- -----------------------------------------------------------------------------
-- 7. 支付单（模拟通道的流水账；接真实网关时替换适配层，本表结构可保留）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_pay_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    pay_no      VARCHAR(40)   NOT NULL COMMENT '支付单号（雪花，MP 前缀）',
    biz_type    VARCHAR(20)   NOT NULL COMMENT 'DEPOSIT / DEAL',
    biz_id      BIGINT        NOT NULL COMMENT 'DEPOSIT → auction_deposit.id；DEAL → auction_deal.id',
    user_id     BIGINT        NOT NULL,
    amount      DECIMAL(18,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL COMMENT 'CREATED/PAID/FAILED/CANCELLED/REFUNDED',
    channel     VARCHAR(20)   DEFAULT NULL COMMENT '模拟实现固定 MOCK',
    paid_time   DATETIME      DEFAULT NULL,
    fail_reason VARCHAR(200)  DEFAULT NULL,
    refund_time DATETIME      DEFAULT NULL,
    create_time DATETIME      DEFAULT NULL,
    update_time DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_pay_biz (biz_type, biz_id),
    KEY idx_pay_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拍卖支付单（模拟通道路账本）';
