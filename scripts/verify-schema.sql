-- =============================================================================
-- 星幕影票 —— 表结构与数据一致性自检
-- =============================================================================
-- 执行（用应用账号即可，全部为只读查询）：
--     mysql -u starscreen_app -p --default-character-set=utf8mb4 starscreen < scripts/verify-schema.sql
--
-- 输出中 result 列必须全部为 PASS。任何一项 FAIL 都表示环境与
-- src/main/resources/schema.sql 不一致，应用可能出现 1054/1146 类错误。
-- =============================================================================

SELECT '===== 一、结构校验 =====' AS section;

SELECT '表数量 = 10' AS check_item,
       '10' AS expected,
       CAST(COUNT(*) AS CHAR) AS actual,
       IF(COUNT(*) = 10, 'PASS', 'FAIL') AS result
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('t_user','movie','cinema','hall','schedule','seat','t_order',
                     'daily_box_office','expected_movie','top100_movie')

UNION ALL
-- Cinema 实体有 phone 字段，缺列会导致 findAll() 报 1054
SELECT 'cinema.phone 存在', '1', CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 1, 'PASS', 'FAIL')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cinema' AND COLUMN_NAME = 'phone'

UNION ALL
-- rows / cols 是 MySQL 8 保留字，必须存在且可被反引号引用
SELECT 'hall.rows / hall.cols 存在', '2', CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 2, 'PASS', 'FAIL')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'hall' AND COLUMN_NAME IN ('rows','cols')

UNION ALL
-- 实体是 LocalDateTime，必须是 datetime；VARCHAR 会让排序与 DATE() 统计失真
SELECT 't_order 时间列为 datetime', '3', CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 3, 'PASS', 'FAIL')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order'
  AND COLUMN_NAME IN ('show_time','create_time','pay_time')
  AND DATA_TYPE = 'datetime'

UNION ALL
-- 金额必须用 DECIMAL，DOUBLE 有精度问题
SELECT 't_order.total_price 为 decimal', '1', CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 1, 'PASS', 'FAIL')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order'
  AND COLUMN_NAME = 'total_price' AND DATA_TYPE = 'decimal'

UNION ALL
-- 长文本列不能被截断（海报 URL / 演员 / 类型串）
SELECT '长文本列长度 >= 200（poster/actors/genres/tags/address）', '5',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 5, 'PASS', 'FAIL')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME='movie'  AND COLUMN_NAME IN ('poster','actors','genres'))
    OR (TABLE_NAME='cinema' AND COLUMN_NAME IN ('tags','address')))
  AND CHARACTER_MAXIMUM_LENGTH >= 200

UNION ALL
-- 座位「场次+排+座」唯一键：防重复建座，也是防超卖的兜底
SELECT 'seat 唯一键 uk_seat_schedule_pos 存在', '3', CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 3, 'PASS', 'FAIL')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat' AND INDEX_NAME = 'uk_seat_schedule_pos'

UNION ALL
-- 关键查询索引（CONCURRENCY.md 里声称存在、旧库实际缺失的那批）
SELECT '关键索引齐全（11 个）', '11', CAST(COUNT(DISTINCT INDEX_NAME) AS CHAR),
       IF(COUNT(DISTINCT INDEX_NAME) = 11, 'PASS', 'FAIL')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND INDEX_NAME IN ('idx_movie_status','idx_cinema_brand','idx_cinema_district',
                     'idx_hall_cinema','idx_schedule_movie_date','idx_schedule_cinema',
                     'idx_schedule_hall','idx_seat_order','idx_order_user',
                     'idx_order_schedule','idx_order_status');

SELECT '===== 二、数据一致性 =====' AS section;

SELECT '重复座位数 = 0' AS check_item, '0' AS expected,
       CAST(COUNT(*) AS CHAR) AS actual, IF(COUNT(*) = 0, 'PASS', 'FAIL') AS result
FROM (SELECT 1 FROM seat GROUP BY schedule_id, row_num, col_num HAVING COUNT(*) > 1) d

UNION ALL
-- 每个场次的座位数必须等于影厅 排数 × 列数，否则选座页会缺座 / 错位
SELECT '场次座位数与影厅容量不一致的场次 = 0', '0',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 0, 'PASS', 'FAIL')
FROM schedule s
JOIN hall h ON h.id = s.hall_id
WHERE (SELECT COUNT(*) FROM seat x WHERE x.schedule_id = s.id) <> h.`rows` * h.`cols`

UNION ALL
-- BCrypt 密文必须是 60 字符，否则 matches() 恒为 false，后台无法登录
SELECT 'admin 账号存在且密码为合法 BCrypt（60 字符）', '1',
       CAST(COUNT(*) AS CHAR), IF(COUNT(*) = 1, 'PASS', 'FAIL')
FROM t_user
WHERE username = 'admin' AND CHAR_LENGTH(password) = 60;

SELECT '===== 三、数据量概览 =====' AS section;

SELECT 't_user' AS tbl, COUNT(*) AS rows_cnt FROM t_user
UNION ALL SELECT 'movie',            COUNT(*) FROM movie
UNION ALL SELECT 'cinema',           COUNT(*) FROM cinema
UNION ALL SELECT 'hall',             COUNT(*) FROM hall
UNION ALL SELECT 'schedule',         COUNT(*) FROM schedule
UNION ALL SELECT 'seat',             COUNT(*) FROM seat
UNION ALL SELECT 't_order',          COUNT(*) FROM t_order
UNION ALL SELECT 'daily_box_office', COUNT(*) FROM daily_box_office
UNION ALL SELECT 'expected_movie',   COUNT(*) FROM expected_movie
UNION ALL SELECT 'top100_movie',     COUNT(*) FROM top100_movie;
