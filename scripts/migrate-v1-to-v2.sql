-- =============================================================================
-- 星幕影票 —— 存量库结构对齐脚本（v1 → v2）
-- =============================================================================
-- 适用对象：已经用旧的 ddl-auto=update 跑起来的库
--          （表已存在，但结构与 schema.sql 有偏差）。
--          全新库不需要执行本脚本，直接用 schema.sql 建表即可。
--
-- 执行（用 root / 具备 ALTER、INDEX 权限的账号）：
--     mysql -u root -p --default-character-set=utf8mb4 starscreen < scripts/migrate-v1-to-v2.sql
--
-- 设计：全部幂等，重复执行安全；只依赖 ALTER / INDEX 权限，不创建存储过程。
--
-- ⚠️ 执行前请先备份：
--     mysqldump -u root -p --default-character-set=utf8mb4 starscreen > starscreen_backup.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. 前置检查（只读，不修改任何数据）
--    如果下面两条查询有结果，说明存在无法直接转换的脏数据，先人工处理再继续。
-- -----------------------------------------------------------------------------
SELECT '【检查1】t_order 中时间字段无法解析为日期的行' AS check_item;
SELECT id, show_time, create_time, pay_time
FROM t_order
WHERE (show_time   IS NOT NULL AND DATE(show_time)   IS NULL)
   OR (create_time IS NOT NULL AND DATE(create_time) IS NULL)
   OR (pay_time    IS NOT NULL AND DATE(pay_time)    IS NULL);

SELECT '【检查2】seat 中重复的 (schedule_id, row_num, col_num)' AS check_item;
SELECT schedule_id, row_num, col_num, COUNT(*) AS dup_count
FROM seat
GROUP BY schedule_id, row_num, col_num
HAVING COUNT(*) > 1;

-- -----------------------------------------------------------------------------
-- 1. t_order：时间字段 VARCHAR → DATETIME，金额 DOUBLE → DECIMAL(10,2)
--    原因：实体 Order 中这三个字段是 LocalDateTime、totalPrice 是 BigDecimal，
--          旧表用 VARCHAR/DOUBLE 只能靠 MySQL 隐式转换苟活，
--          DATE() 统计、排序、金额累加都会失真。
-- -----------------------------------------------------------------------------
SET @need_alter := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_order'
      AND ((COLUMN_NAME IN ('show_time', 'create_time', 'pay_time') AND DATA_TYPE <> 'datetime')
        OR (COLUMN_NAME = 'total_price' AND DATA_TYPE <> 'decimal'))
);

SET @ddl := IF(@need_alter > 0,
    'ALTER TABLE t_order
        MODIFY COLUMN show_time   DATETIME NULL,
        MODIFY COLUMN create_time DATETIME NULL,
        MODIFY COLUMN pay_time    DATETIME NULL,
        MODIFY COLUMN total_price DECIMAL(10,2) NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2. 索引补齐
--    旧库只有主键与唯一键，seat.schedule_id、t_order.user_id 全是全表扫描。
--    CONCURRENCY.md 里写的 idx_schedule_row_col 在库里实际并不存在。
--    写法：先查 information_schema，缺了才 ALTER，因此可重复执行。
-- -----------------------------------------------------------------------------

-- movie.status
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='movie' AND INDEX_NAME='idx_movie_status') = 0,
               'ALTER TABLE movie ADD INDEX idx_movie_status (status)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cinema.brand
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='cinema' AND INDEX_NAME='idx_cinema_brand') = 0,
               'ALTER TABLE cinema ADD INDEX idx_cinema_brand (brand)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cinema.district
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='cinema' AND INDEX_NAME='idx_cinema_district') = 0,
               'ALTER TABLE cinema ADD INDEX idx_cinema_district (district)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- hall.cinema_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='hall' AND INDEX_NAME='idx_hall_cinema') = 0,
               'ALTER TABLE hall ADD INDEX idx_hall_cinema (cinema_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- schedule.(movie_id, date)
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='schedule' AND INDEX_NAME='idx_schedule_movie_date') = 0,
               'ALTER TABLE schedule ADD INDEX idx_schedule_movie_date (movie_id, `date`)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- schedule.cinema_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='schedule' AND INDEX_NAME='idx_schedule_cinema') = 0,
               'ALTER TABLE schedule ADD INDEX idx_schedule_cinema (cinema_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- schedule.hall_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='schedule' AND INDEX_NAME='idx_schedule_hall') = 0,
               'ALTER TABLE schedule ADD INDEX idx_schedule_hall (hall_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- seat.order_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='seat' AND INDEX_NAME='idx_seat_order') = 0,
               'ALTER TABLE seat ADD INDEX idx_seat_order (order_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_order.user_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='t_order' AND INDEX_NAME='idx_order_user') = 0,
               'ALTER TABLE t_order ADD INDEX idx_order_user (user_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_order.schedule_id
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='t_order' AND INDEX_NAME='idx_order_schedule') = 0,
               'ALTER TABLE t_order ADD INDEX idx_order_schedule (schedule_id)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_order.status
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='t_order' AND INDEX_NAME='idx_order_status') = 0,
               'ALTER TABLE t_order ADD INDEX idx_order_status (status)', 'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3. seat 唯一键（防重复座位 / 防超卖的最后一道闸门）
--    只有在「没有重复数据」时才添加，否则跳过并给出提示。
--    如需清理重复，先确认再执行（会删除多余行，保留 id 最小的那条）：
--        DELETE s1 FROM seat s1
--        JOIN seat s2
--          ON s1.schedule_id = s2.schedule_id
--         AND s1.row_num     = s2.row_num
--         AND s1.col_num     = s2.col_num
--         AND s1.id > s2.id;
-- -----------------------------------------------------------------------------
SET @dup_cnt := (
    SELECT COUNT(*) FROM (
        SELECT 1 FROM seat
        GROUP BY schedule_id, row_num, col_num
        HAVING COUNT(*) > 1
    ) d
);

SET @ddl := IF(@dup_cnt = 0
               AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='seat'
                      AND INDEX_NAME='uk_seat_schedule_pos') = 0,
               'ALTER TABLE seat ADD UNIQUE KEY uk_seat_schedule_pos (schedule_id, row_num, col_num)',
               'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT IF(@dup_cnt = 0,
          '座位唯一键：已确保存在',
          CONCAT('座位唯一键：已跳过 —— 存在 ', @dup_cnt, ' 组重复座位，请先按上面的 DELETE 语句清理'))
       AS seat_unique_key;

-- -----------------------------------------------------------------------------
-- 4. 建议项（默认不执行）：给核心外键列补 NOT NULL
--    先确认下面统计为 0，再手动放开末尾两句 ALTER。
-- -----------------------------------------------------------------------------
SELECT '建议项：以下外键列的 NULL 行数，补 NOT NULL 前需先处理' AS check_item;
SELECT 't_order.order_no/user_id/schedule_id' AS cols, COUNT(*) AS null_rows
FROM t_order WHERE order_no IS NULL OR user_id IS NULL OR schedule_id IS NULL
UNION ALL
SELECT 'seat.schedule_id/row_num/col_num', COUNT(*)
FROM seat WHERE schedule_id IS NULL OR row_num IS NULL OR col_num IS NULL;

-- ALTER TABLE t_order MODIFY order_no VARCHAR(50) NOT NULL,
--                     MODIFY user_id BIGINT NOT NULL,
--                     MODIFY schedule_id BIGINT NOT NULL;
-- ALTER TABLE seat MODIFY schedule_id BIGINT NOT NULL,
--                  MODIFY row_num INT NOT NULL,
--                  MODIFY col_num INT NOT NULL;

-- -----------------------------------------------------------------------------
-- 5. 收尾：执行 scripts/verify-schema.sql 复核
-- -----------------------------------------------------------------------------
SELECT '迁移完成，请执行 scripts/verify-schema.sql 复核' AS next_step;
