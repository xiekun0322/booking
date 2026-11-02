-- =============================================================================
-- 增量迁移：站内信 + 通知外发（决策 D9）
-- =============================================================================
-- 【定位】对应决策 D9：站内信 + 邮件起步，异步发，失败进重试队列，不阻塞主流程。
--
-- 【执行】由 DBA / root 执行（应用账号只有 DML 权限）：
--         mysql -u root -p --default-character-set=utf8mb4 auction < scripts/migrate-2026-11-notify.sql
--         mysql -u root -p --default-character-set=utf8mb4 starscreen < scripts/migrate-2026-11-notify.sql
--
--         脚本对两个库都安全：每段都先判断对象是否存在，不存在才建/才改。
--
-- 【幂等】CREATE TABLE IF NOT EXISTS + information_schema 判断列是否存在，
--         可重复执行（第二次执行不会报 1050 / 1060）。
--
-- 【依赖】auction 库需已存在（本脚本不建库）。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 拍卖库：通知表
-- -----------------------------------------------------------------------------
-- 一条记录 = 一条站内信 + 一次外发投递任务。
-- 不拆两张表：站内信插入即送达，只有邮件需要异步投递，拆开只会多一次 join。
CREATE TABLE IF NOT EXISTS auction_notification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    user_id         BIGINT       NOT NULL COMMENT '收件人（裸 ID，不建跨库外键）',
    type            VARCHAR(40)  NOT NULL COMMENT 'DEAL_WON / DEAL_PAID / DEPOSIT_REFUNDED / DEPOSIT_FORFEITED / DEPOSIT_REFUND_FAILED',
    title           VARCHAR(120) NOT NULL,
    content         VARCHAR(500) NOT NULL,

    biz_type        VARCHAR(20)  NULL COMMENT 'DEAL / DEPOSIT / ITEM',
    biz_id          BIGINT       NULL COMMENT '业务主键，便于按业务反查',

    read_flag       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未读 / 1 已读',

    email           VARCHAR(120) NULL COMMENT '外发收件地址，为空则投递置 SKIPPED',
    delivery_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / SKIPPED / FAILED / DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NULL COMMENT '下次可发送时间（退避），NULL 表示立即可发',
    last_error      VARCHAR(500) NULL,
    sent_at         DATETIME     NULL,

    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,

    -- 未读数统计：user_id + read_flag 走这个索引，不必回表
    KEY idx_notify_user_read (user_id, read_flag, id),
    -- 投递扫描：只看待发/失败的，且按 next_retry_at 过滤
    KEY idx_notify_delivery (delivery_status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '拍卖通知（站内信 + 外发投递）';

-- -----------------------------------------------------------------------------
-- 业务库：t_user.email（注意表名是 t_user，不是 users）
-- -----------------------------------------------------------------------------
-- 通知需要有收件地址。加可空列、不加唯一约束：
--   1）存量用户没有邮箱，加 NOT NULL 会直接迁移失败；
--   2）同一邮箱可能被多人共用（前台代收），加唯一键会挡住真实数据。
--
-- 本脚本会被分别执行到 auction / starscreen 两个库，因此先判断表是否存在：
-- auction 库里没有 t_user，此时整段跳过，不会报 1146。
SET @db := DATABASE();
SET @has_table := (
    SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_user'
);
SET @has_email := IF(@has_table = 0, 1, (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'email'
));
SET @sql := IF(@has_email = 0,
    'ALTER TABLE t_user ADD COLUMN email VARCHAR(120) NULL COMMENT ''通知邮箱（可空）'' AFTER phone',
    'SELECT ''t_user.email 无需变更（已存在或本库无该表）'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
