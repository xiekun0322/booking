-- =============================================================================
-- 星幕影票 —— 种子数据（幂等 / 可重复执行）
-- =============================================================================
-- 【设计原则】
--   1. 幂等：每条 INSERT 都带 WHERE NOT EXISTS 守卫，重复执行不会产生重复数据，
--      也不会因唯一键冲突中断（旧版的裸 INSERT 在第二次执行时会报 1062）。
--   2. 不写死自增 ID：跨表引用统一用「名称 / 标题」子查询取 ID，
--      避免在已有数据的库上插错影院、错影厅。
--   3. 座位由影厅的行列数自动展开，保证每个场次的座位数与影厅一致；
--      旧版只硬编码了 schedule 1/2 的部分座位，其余场次选座页是空的。
--
-- 【执行方式】由具备 INSERT 权限的账号执行：
--        mysql -u root -p starscreen < src/main/resources/data.sql
--
-- 【说明】本文件不参与应用启动（spring.sql.init.mode=never），
--        仅在初始化环境或换库时手动执行一次。
-- =============================================================================

-- 【字符集】必须显式声明：mysql 客户端在无 LANG 的环境（容器）里会以 latin1 发送，
--   导致「空枪」这类中文入库后变成「ç©ºæžª」的双重编码。见 schema.sql 同位置说明。
SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 用户：admin（后台）/ testuser（前台），密码均为 123456
-- -----------------------------------------------------------------------------
-- 注意：BCrypt 密文必须是 60 字符。旧脚本里的 52 字符占位串不是合法哈希，
--       BCryptPasswordEncoder.matches() 会直接返回 false，导致 admin 永远登录失败。
--       当前密文已用 BCrypt(10) 真实生成并校验通过。
INSERT INTO t_user (username, password, phone, create_time, role, deleted)
SELECT 'admin', '$2a$10$ABcyMe6vp/FWDdFJhXVrW.R4MMr1cGMjn.fUYm.Ps2Q9QdFr1qp2u',
       '13800000000', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'), 'ADMIN', b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'admin');

INSERT INTO t_user (username, password, phone, create_time, role, deleted)
SELECT 'testuser', '$2a$10$ABcyMe6vp/FWDdFJhXVrW.R4MMr1cGMjn.fUYm.Ps2Q9QdFr1qp2u',
       '13900000000', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'), 'USER', b'0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE username = 'testuser');

-- -----------------------------------------------------------------------------
-- 2. 影院
-- -----------------------------------------------------------------------------
INSERT INTO cinema (name, brand, district, address, tags, distance, phone, create_time)
SELECT '厦门万达影城（集美IOI店）', '万达', '集美区', '集美区杏林湾路398号IOI Mall 4楼',
       '退,改签,折扣卡', 1.7, '0592-6000001', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cinema WHERE name = '厦门万达影城（集美IOI店）');

INSERT INTO cinema (name, brand, district, address, tags, distance, phone, create_time)
SELECT '厦门金逸影城（SM广场店）', '金逸', '思明区', '思明区嘉禾路468号SM城市广场1期5楼',
       '退,改签,折扣卡', 8.1, '0592-6000002', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cinema WHERE name = '厦门金逸影城（SM广场店）');

-- -----------------------------------------------------------------------------
-- 3. 影厅（按影院名 + 影厅名定位，rows / cols 为保留字需加反引号）
-- -----------------------------------------------------------------------------
INSERT INTO hall (cinema_id, name, type, `rows`, `cols`)
SELECT c.id, '1号厅', NULL, 6, 10
FROM cinema c
WHERE c.name = '厦门万达影城（集美IOI店）'
  AND NOT EXISTS (SELECT 1 FROM hall h WHERE h.cinema_id = c.id AND h.name = '1号厅');

INSERT INTO hall (cinema_id, name, type, `rows`, `cols`)
SELECT c.id, 'IMAX厅', 'IMAX厅', 10, 16
FROM cinema c
WHERE c.name = '厦门万达影城（集美IOI店）'
  AND NOT EXISTS (SELECT 1 FROM hall h WHERE h.cinema_id = c.id AND h.name = 'IMAX厅');

INSERT INTO hall (cinema_id, name, type, `rows`, `cols`)
SELECT c.id, '1号厅', NULL, 6, 10
FROM cinema c
WHERE c.name = '厦门金逸影城（SM广场店）'
  AND NOT EXISTS (SELECT 1 FROM hall h WHERE h.cinema_id = c.id AND h.name = '1号厅');

-- -----------------------------------------------------------------------------
-- 4. 电影
-- -----------------------------------------------------------------------------
INSERT INTO movie (title, poster, score, tag, release_date, status, want_count,
                   director, actors, duration, plot, genres, create_time)
SELECT '空枪', '/images/poster1.jpg', 9.5, NULL, '2026-09-22', 'showing', NULL,
       '王小明', '张三,李四,王五', 128,
       '本片讲述了都市青年在一次意外中重拾人生信念的故事。', '剧情,犯罪',
       DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM movie WHERE title = '空枪');

INSERT INTO movie (title, poster, score, tag, release_date, status, want_count,
                   director, actors, duration, plot, genres, create_time)
SELECT '八仙！', '/images/poster2.jpg', 9.7, '2DIMAX', '2026-07-18', 'showing', NULL,
       '张艺谋', '何仙姑,吕洞宾,铁拐李,汉钟离', 135,
       '影片以家喻户晓的传统民间故事为灵感创作。', '奇幻,喜剧,动作',
       DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM movie WHERE title = '八仙！');

INSERT INTO movie (title, poster, score, tag, release_date, status, want_count,
                   director, actors, duration, plot, genres, create_time)
SELECT '水东游', '/images/poster9.jpg', NULL, NULL, '2026-10-23', 'upcoming', 1238,
       '宁浩', '徐峥,黄渤,王宝强', 128,
       '一场关于时间与记忆的奇幻漂流。', '剧情,奇幻',
       DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s')
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM movie WHERE title = '水东游');

-- -----------------------------------------------------------------------------
-- 5. 场次（日期取当天，保证前端按「今天」能查到）
-- -----------------------------------------------------------------------------
INSERT INTO schedule (movie_id, cinema_id, hall_id, cinema_name, hall_name,
                      start_time, end_time, language, price, `date`)
SELECT m.id, c.id, h.id, c.name, h.name, '14:00', '16:08', '国语 2D', 35.0,
       DATE_FORMAT(NOW(), '%Y-%m-%d')
FROM movie m
JOIN cinema c ON c.name = '厦门万达影城（集美IOI店）'
JOIN hall h ON h.cinema_id = c.id AND h.name = '1号厅'
WHERE m.title = '空枪'
  AND NOT EXISTS (
      SELECT 1 FROM schedule s
      WHERE s.movie_id = m.id AND s.hall_id = h.id
        AND s.`date` = DATE_FORMAT(NOW(), '%Y-%m-%d')
        AND s.start_time = '14:00');

INSERT INTO schedule (movie_id, cinema_id, hall_id, cinema_name, hall_name,
                      start_time, end_time, language, price, `date`)
SELECT m.id, c.id, h.id, c.name, h.name, '19:00', '21:08', '国语 2D', 45.0,
       DATE_FORMAT(NOW(), '%Y-%m-%d')
FROM movie m
JOIN cinema c ON c.name = '厦门万达影城（集美IOI店）'
JOIN hall h ON h.cinema_id = c.id AND h.name = 'IMAX厅'
WHERE m.title = '空枪'
  AND NOT EXISTS (
      SELECT 1 FROM schedule s
      WHERE s.movie_id = m.id AND s.hall_id = h.id
        AND s.`date` = DATE_FORMAT(NOW(), '%Y-%m-%d')
        AND s.start_time = '19:00');

INSERT INTO schedule (movie_id, cinema_id, hall_id, cinema_name, hall_name,
                      start_time, end_time, language, price, `date`)
SELECT m.id, c.id, h.id, c.name, h.name, '20:00', '22:15', '国语 2D', 35.0,
       DATE_FORMAT(NOW(), '%Y-%m-%d')
FROM movie m
JOIN cinema c ON c.name = '厦门万达影城（集美IOI店）'
JOIN hall h ON h.cinema_id = c.id AND h.name = '1号厅'
WHERE m.title = '八仙！'
  AND NOT EXISTS (
      SELECT 1 FROM schedule s
      WHERE s.movie_id = m.id AND s.hall_id = h.id
        AND s.`date` = DATE_FORMAT(NOW(), '%Y-%m-%d')
        AND s.start_time = '20:00');

-- -----------------------------------------------------------------------------
-- 6. 座位：按影厅行列数自动展开，只为「还没有座位」的场次生成
--    1 号厅 6×10 = 60 座，IMAX 厅 10×16 = 160 座
-- -----------------------------------------------------------------------------
INSERT INTO seat (schedule_id, row_num, col_num, status)
WITH RECURSIVE seq (n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 50
)
SELECT s.id, r.n, c.n, 'available'
FROM schedule s
JOIN hall h ON h.id = s.hall_id
JOIN seq r ON r.n <= h.`rows`
JOIN seq c ON c.n <= h.`cols`
WHERE NOT EXISTS (SELECT 1 FROM seat x WHERE x.schedule_id = s.id);

-- -----------------------------------------------------------------------------
-- 7. 侧边栏榜单
-- -----------------------------------------------------------------------------
INSERT INTO daily_box_office (rank_num, movie_title, amount)
SELECT 1, '奥德赛', 9256.0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM daily_box_office WHERE rank_num = 1 AND movie_title = '奥德赛');
INSERT INTO daily_box_office (rank_num, movie_title, amount)
SELECT 2, '欢迎来龙餐馆', 4661.0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM daily_box_office WHERE rank_num = 2 AND movie_title = '欢迎来龙餐馆');
INSERT INTO daily_box_office (rank_num, movie_title, amount)
SELECT 3, '空枪', 3037.0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM daily_box_office WHERE rank_num = 3 AND movie_title = '空枪');

INSERT INTO expected_movie (rank_num, movie_title, want_count)
SELECT 1, '复仇者联盟4：终局之战', 2239942 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM expected_movie WHERE rank_num = 1 AND movie_title = '复仇者联盟4：终局之战');
INSERT INTO expected_movie (rank_num, movie_title, want_count)
SELECT 2, '生化危机：爆发夜', 144997 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM expected_movie WHERE rank_num = 2 AND movie_title = '生化危机：爆发夜');

INSERT INTO top100_movie (rank_num, movie_title, score)
SELECT 1, '我不是药神', 9.6 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM top100_movie WHERE rank_num = 1 AND movie_title = '我不是药神');
INSERT INTO top100_movie (rank_num, movie_title, score)
SELECT 2, '肖申克的救赎', 9.8 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM top100_movie WHERE rank_num = 2 AND movie_title = '肖申克的救赎');
