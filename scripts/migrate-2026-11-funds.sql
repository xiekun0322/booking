-- =============================================================================
-- 增量迁移（auction 库）：保证金 / 成交单 / 发件箱 / 支付单
-- =============================================================================
-- 【定位】对应决策：一 C（异步写退款触发）、二 B（Outbox 异步）、三 D（定时判悔拍）、
--        四 C（发布时固化保证金）、五 A（拍卖自成交易域）。
--
-- 【执行】由 DBA / root 执行（应用账号 starscreen_app 只有 DML 权限）：
--        mysql -u root -p --default-character-set=utf8mb4 auction < scripts/migrate-2026-11-funds.sql
--
-- 【幂等】全部 CREATE TABLE IF NOT EXISTS，可重复执行。
--        本批只新增表、不修改既有表结构，因此不会触发 1060（Duplicate column）。
--
-- 【顺序】必须在 auction_item / auction_bid 已存在之后执行（本脚本不建它们）。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 发件箱：与业务表同库，业务事务内写入，relay 异步投递
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type  VARCHAR(40)  COMMENT 'AUCTION_ITEM / AUCTION_DEAL',
    aggregate_id    BIGINT       COMMENT '聚合根 ID',
    event_type      VARCHAR(40)  NOT NULL COMMENT 'DEPOSIT_REFUND_TRIGGER / DEPOSIT_FORFEIT',
    routing_key     VARCHAR(80)  NOT NULL COMMENT '入表时定死，重投不重算',
    payload         LONGTEXT     NOT NULL COMMENT '已序列化 JSON，重投原样发送',
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING/SENT/DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     COMMENT '退避后的下次可投递时间',
    locked_until    DATETIME     COMMENT '租约到期时间（替代 SENDING 状态）',
    last_error      VARCHAR(500),
    create_time     DATETIME,
    update_time     DATETIME,
    -- relay 扫描走 (status, next_retry_at)
    KEY idx_outbox_dispatch (status, next_retry_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拍卖发件箱（Outbox）';

-- -----------------------------------------------------------------------------
-- 保证金：用户 × 拍品一条，唯一键锁死扣划幂等
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_deposit (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT        NOT NULL,
    item_id            BIGINT        NOT NULL,
    amount             DECIMAL(18, 2) NOT NULL COMMENT '实缴金额快照，退款只认这个数',
    status             VARCHAR(20)   NOT NULL
        COMMENT 'PENDING_PAY/HELD/PAY_FAILED/CLOSED/REFUNDING/REFUNDED/REFUND_DEAD/FORFEITED',
    pay_no             VARCHAR(40)   COMMENT '对应 auction_pay_order.pay_no，原路退回用',
    pay_time           DATETIME,
    refund_trigger_at  DATETIME      COMMENT '非空即该退；由异步消费者写入',
    refund_time        DATETIME,
    forfeit_time       DATETIME,
    retry_count        INT           NOT NULL DEFAULT 0 COMMENT '退款重试次数',
    next_retry_at      DATETIME      COMMENT '退款退避时间',
    last_error         VARCHAR(500),
    create_time        DATETIME,
    update_time        DATETIME,
    -- 扣划幂等的根：重复报名不会产生第二行
    UNIQUE KEY uk_deposit (user_id, item_id),
    KEY idx_deposit_item_status (item_id, status),
    -- 退款 worker 扫描走 (status, refund_trigger_at)
    KEY idx_deposit_refund (status, refund_trigger_at),
    KEY idx_deposit_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '参拍保证金';

-- -----------------------------------------------------------------------------
-- 成交单：拍卖域自己的交易凭证（决策五 A，不塞 t_order）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_deal (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    deal_no            VARCHAR(40)   NOT NULL COMMENT '成交单号（雪花 AD 前缀）',
    item_id            BIGINT        NOT NULL COMMENT '一拍品一成交单，重拍须新建 item',
    item_no            VARCHAR(40),
    buyer_id           BIGINT        NOT NULL,
    buyer_name         VARCHAR(100),
    deal_price         DECIMAL(18, 2) NOT NULL COMMENT '成交价，18,2 与 auction_item 对齐',
    deposit_amount     DECIMAL(18, 2) DEFAULT 0.00 COMMENT '中标者保证金（仅记录）',
    status             VARCHAR(20)   NOT NULL COMMENT 'PENDING_PAYMENT/PAID/COMPLETED/CANCELLED',
    pay_deadline       DATETIME      NOT NULL COMMENT '到期未付判悔拍',
    pay_time           DATETIME,
    cancel_reason      VARCHAR(40)   COMMENT 'PAY_TIMEOUT/USER_CANCEL/ADMIN_CANCEL',
    cancel_time        DATETIME,
    external_order_no  VARCHAR(64)   COMMENT '预留：将来同步 t_order 时回填，当前恒 NULL',
    version            INT           NOT NULL DEFAULT 0,
    create_time        DATETIME,
    update_time        DATETIME,
    UNIQUE KEY uk_deal_no (deal_no),
    -- 幂等核心：重复结算撞这个键
    UNIQUE KEY uk_deal_item (item_id),
    -- 悔拍扫描走 (status, pay_deadline)
    KEY idx_deal_status_deadline (status, pay_deadline),
    KEY idx_deal_buyer (buyer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拍卖成交单';

-- -----------------------------------------------------------------------------
-- 支付单：模拟支付通道的本地账本（将来替换真实网关的适配层）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auction_pay_order (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    pay_no       VARCHAR(40)   NOT NULL COMMENT '支付单号（雪花 MP 前缀），回调定位用',
    biz_type     VARCHAR(20)   NOT NULL COMMENT 'DEPOSIT / DEAL',
    biz_id       BIGINT        NOT NULL COMMENT 'DEPOSIT → auction_deposit.id；DEAL → auction_deal.id',
    user_id      BIGINT        NOT NULL,
    amount       DECIMAL(18, 2) NOT NULL,
    status       VARCHAR(20)   NOT NULL COMMENT 'CREATED/PAID/FAILED/CANCELLED/REFUNDED',
    channel      VARCHAR(20)   COMMENT '模拟实现固定 MOCK',
    paid_time    DATETIME,
    fail_reason  VARCHAR(200),
    refund_time  DATETIME,
    create_time  DATETIME,
    update_time  DATETIME,
    UNIQUE KEY uk_pay_no (pay_no),
    KEY idx_pay_biz (biz_type, biz_id),
    KEY idx_pay_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拍卖支付单（模拟通道账本）';
