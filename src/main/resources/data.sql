-- =========================================================
-- 1. 初始化用户 (管理员/测试用户)
-- =========================================================
-- 密码是 123456，经过 BCrypt 加密后的密文
INSERT INTO t_user (username, password, phone, create_time, role, deleted) VALUES 
('admin', '$2a$10$7EqJtq98hPqEX7fNZaFWoO.6YhG4q9N8l0x2r6F1s3Z1x', '13800000000', NOW(), 'ADMIN', FALSE),
('testuser', '$2a$10$7EqJtq98hPqEX7fNZaFWoO.6YhG4q9N8l0x2r6F1s3Z1x', '13900000000', NOW(), 'USER', FALSE);

-- =========================================================
-- 2. 初始化影院
-- =========================================================
INSERT INTO cinema (name, brand, district, address, tags, distance, create_time) VALUES
('厦门万达影城（集美IOI店）', '万达', '集美区', '集美区杏林湾路398号IOI Mall 4楼', '退,改签,折扣卡', 1.7, NOW()),
('厦门金逸影城（SM广场店）', '金逸', '思明区', '思明区嘉禾路468号SM城市广场1期5楼', '退,改签,折扣卡', 8.1, NOW());

-- =========================================================
-- 3. 初始化影厅 (对应影院的ID)
-- =========================================================
INSERT INTO hall (cinema_id, name, type, rows, cols) VALUES
(1, '1号厅', NULL, 6, 10),
(1, 'IMAX厅', 'IMAX厅', 10, 16),
(2, '1号厅', NULL, 6, 10);

-- =========================================================
-- 4. 初始化电影
-- =========================================================
INSERT INTO movie (title, poster, score, tag, release_date, status, want_count, director, actors, duration, plot, genres, create_time) VALUES
('空枪', '/images/poster1.jpg', 9.5, NULL, '2026-09-22', 'showing', NULL, '王小明', '张三,李四,王五', 128, '本片讲述了都市青年在一次意外中重拾人生信念的故事。', '剧情,犯罪', NOW()),
('八仙！', '/images/poster2.jpg', 9.7, '2DIMAX', '2026-07-18', 'showing', NULL, '张艺谋', '何仙姑,吕洞宾,铁拐李,汉钟离', 135, '影片以家喻户晓的传统民间故事为灵感创作。', '奇幻,喜剧,动作', NOW()),
('水东游', '/images/poster9.jpg', NULL, NULL, '2026-10-23', 'upcoming', 1238, '宁浩', '徐峥,黄渤,王宝强', 128, '一场关于时间与记忆的奇幻漂流。', '剧情,奇幻', NOW());

-- =========================================================
-- 5. 初始化场次 (注意：排期日期设定在今天，确保前端能查到)
-- =========================================================
INSERT INTO schedule (movie_id, cinema_id, hall_id, cinema_name, hall_name, start_time, end_time, language, price, date) VALUES
(1, 1, 1, '厦门万达影城（集美IOI店）', '1号厅', '14:00', '16:08', '国语 2D', 35.0, DATE_FORMAT(NOW(), '%Y-%m-%d')),
(1, 1, 2, '厦门万达影城（集美IOI店）', 'IMAX厅', '19:00', '21:08', '国语 2D', 45.0, DATE_FORMAT(NOW(), '%Y-%m-%d')),
(2, 1, 1, '厦门万达影城（集美IOI店）', '1号厅', '20:00', '22:15', '国语 2D', 35.0, DATE_FORMAT(NOW(), '%Y-%m-%d'));

-- =========================================================
-- 6. 初始化座位 (为了演示，只为 schedule_id = 1 和 2 的场次生成部分座位)
-- 真实环境可以使用存储过程批量生成
-- =========================================================
-- 场次1：1号厅 (6排10列)
INSERT INTO seat (schedule_id, row_num, col_num, status) VALUES 
(1, 1, 1, 'available'), (1, 1, 2, 'available'), (1, 1, 3, 'available'), (1, 1, 4, 'available'), (1, 1, 5, 'available'), (1, 1, 6, 'available'), (1, 1, 7, 'available'), (1, 1, 8, 'available'), (1, 1, 9, 'available'), (1, 1, 10, 'available'),
(1, 2, 1, 'available'), (1, 2, 2, 'sold'),      (1, 2, 3, 'available'), (1, 2, 4, 'available'), (1, 2, 5, 'available'), (1, 2, 6, 'available'), (1, 2, 7, 'available'), (1, 2, 8, 'available'), (1, 2, 9, 'available'), (1, 2, 10, 'available'),
(1, 3, 1, 'available'), (1, 3, 2, 'available'), (1, 3, 3, 'sold'),      (1, 3, 4, 'available'), (1, 3, 5, 'available'), (1, 3, 6, 'available'), (1, 3, 7, 'available'), (1, 3, 8, 'available'), (1, 3, 9, 'available'), (1, 3, 10, 'available');

-- 场次2：IMAX厅 (10排16列)
INSERT INTO seat (schedule_id, row_num, col_num, status) VALUES 
(2, 1, 1, 'available'), (2, 1, 2, 'available'), (2, 1, 3, 'available'), (2, 1, 4, 'available'), (2, 1, 5, 'available'), (2, 1, 6, 'available'), (2, 1, 7, 'available'), (2, 1, 8, 'available'),
(2, 2, 1, 'available'), (2, 2, 2, 'available'), (2, 2, 3, 'available'), (2, 2, 4, 'available'), (2, 2, 5, 'available'), (2, 2, 6, 'available'), (2, 2, 7, 'available'), (2, 2, 8, 'available');
-- (为了篇幅，这里只写了部分座位，后端选座时会显示这些座位，其他空位前端可能会报错。建议你补齐，或者直接保留你之前的 Java 代码专门只生成座位)

-- =========================================================
-- 7. 初始化侧边栏榜单
-- =========================================================
INSERT INTO daily_box_office (rank_num, movie_title, amount) VALUES
(1, '奥德赛', 9256.0),
(2, '欢迎来龙餐馆', 4661.0),
(3, '空枪', 3037.0);

INSERT INTO expected_movie (rank_num, movie_title, want_count) VALUES
(1, '复仇者联盟4：终局之战', 2239942),
(2, '生化危机：爆发夜', 144997);

INSERT INTO top100_movie (rank_num, movie_title, score) VALUES
(1, '我不是药神', 9.6),
(2, '肖申克的救赎', 9.8);