-- ============================================================
-- 星幕影票 完整建表 + 初始化数据
-- 兼容：MySQL 5.7+ / 8.0
-- 可重复执行：CREATE IF NOT EXISTS + ON DUPLICATE KEY UPDATE
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. 用户表 t_user
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(50)  NOT NULL,
    `password`    VARCHAR(100) DEFAULT NULL,
    `phone`       VARCHAR(20)  DEFAULT NULL,
    `create_time` VARCHAR(30)  DEFAULT NULL,
    `role`        VARCHAR(20)  DEFAULT 'USER',
    `deleted`     BIT(1)       NOT NULL DEFAULT b'0',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone`    (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 2. 电影表 movie
-- ============================================================
CREATE TABLE IF NOT EXISTS `movie` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `title`        VARCHAR(100) DEFAULT NULL,
    `poster`       VARCHAR(500) DEFAULT NULL,
    `score`        DOUBLE       DEFAULT NULL,
    `tag`          VARCHAR(50)  DEFAULT NULL,
    `release_date` VARCHAR(50)  DEFAULT NULL,
    `status`       VARCHAR(20)  DEFAULT NULL,
    `want_count`   INT          DEFAULT NULL,
    `director`     VARCHAR(100) DEFAULT NULL,
    `actors`       VARCHAR(500) DEFAULT NULL,
    `duration`     INT          DEFAULT NULL,
    `plot`         TEXT,
    `genres`       VARCHAR(200) DEFAULT NULL,
    `create_time`  VARCHAR(30)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_title`  (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 3. 影院表 cinema
-- ============================================================
CREATE TABLE IF NOT EXISTS `cinema` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(100) NOT NULL,
    `brand`       VARCHAR(50)  DEFAULT NULL,
    `district`    VARCHAR(50)  DEFAULT NULL,
    `address`     VARCHAR(200) DEFAULT NULL,
    `tags`        VARCHAR(200) DEFAULT NULL,
    `distance`    DOUBLE       DEFAULT NULL,
    `phone`       VARCHAR(20)  DEFAULT NULL,
    `create_time` VARCHAR(30)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_brand`    (`brand`),
    KEY `idx_district` (`district`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 4. 影厅表 hall
-- ============================================================
CREATE TABLE IF NOT EXISTS `hall` (
    `id`        BIGINT      NOT NULL AUTO_INCREMENT,
    `cinema_id` BIGINT      DEFAULT NULL,
    `name`      VARCHAR(50) NOT NULL,
    `type`      VARCHAR(50) DEFAULT NULL,
    `rows`      INT         DEFAULT NULL,
    `cols`      INT         DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_cinema_id` (`cinema_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 5. 场次表 schedule
-- ============================================================
CREATE TABLE IF NOT EXISTS `schedule` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `movie_id`    BIGINT      DEFAULT NULL,
    `cinema_id`   BIGINT      DEFAULT NULL,
    `hall_id`     BIGINT      DEFAULT NULL,
    `cinema_name` VARCHAR(100) DEFAULT NULL,
    `hall_name`   VARCHAR(50)  DEFAULT NULL,
    `start_time`  VARCHAR(20)  DEFAULT NULL,
    `end_time`    VARCHAR(20)  DEFAULT NULL,
    `language`    VARCHAR(50)  DEFAULT NULL,
    `price`       DOUBLE       DEFAULT NULL,
    `date`        VARCHAR(20)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_movie_id`  (`movie_id`),
    KEY `idx_cinema_id` (`cinema_id`),
    KEY `idx_date`      (`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 6. 座位表 seat
-- ============================================================
CREATE TABLE IF NOT EXISTS `seat` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT      DEFAULT NULL,
    `row_num`     INT         DEFAULT NULL,
    `col_num`     INT         DEFAULT NULL,
    `status`      VARCHAR(20) DEFAULT 'available',
    `order_id`    BIGINT      DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_schedule_id` (`schedule_id`),
    KEY `idx_order_id`    (`order_id`),
    KEY `idx_schedule_seat` (`schedule_id`, `row_num`, `col_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 7. 订单表 t_order
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_order` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `order_no`     VARCHAR(50)  DEFAULT NULL,
    `user_id`      BIGINT       DEFAULT NULL,
    `schedule_id`  BIGINT       DEFAULT NULL,
    `movie_id`     BIGINT       DEFAULT NULL,
    `movie_title`  VARCHAR(100) DEFAULT NULL,
    `cinema_name`  VARCHAR(100) DEFAULT NULL,
    `hall_name`    VARCHAR(50)  DEFAULT NULL,
    `show_time`    VARCHAR(50)  DEFAULT NULL,
    `seats`        VARCHAR(500) DEFAULT NULL,
    `total_price`  DOUBLE       DEFAULT NULL,
    `status`       VARCHAR(20)  DEFAULT 'pending',
    `create_time`  VARCHAR(30)  DEFAULT NULL,
    `pay_time`     VARCHAR(30)  DEFAULT NULL,
    `ticket_code`  VARCHAR(20)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id`   (`user_id`),
    KEY `idx_status`    (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 8. 今日票房 daily_box_office
-- ============================================================
CREATE TABLE IF NOT EXISTS `daily_box_office` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `rank_num`    INT          DEFAULT NULL,
    `movie_title` VARCHAR(100) DEFAULT NULL,
    `amount`      DOUBLE       DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 9. 最受期待 expected_movie
-- ============================================================
CREATE TABLE IF NOT EXISTS `expected_movie` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `rank_num`    INT          DEFAULT NULL,
    `movie_title` VARCHAR(100) DEFAULT NULL,
    `want_count`  INT          DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 10. TOP100 榜单 top100_movie
-- ============================================================
CREATE TABLE IF NOT EXISTS `top100_movie` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `rank_num`    INT          DEFAULT NULL,
    `movie_title` VARCHAR(100) DEFAULT NULL,
    `score`       DOUBLE       DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 初始化数据：影院（24 家）
-- 用 name 作为幂等键（先加唯一约束再插）
-- ============================================================

-- 先给 cinema.name 加唯一索引，保证幂等插入
-- 若已存在会报 Duplicate key name，可忽略
ALTER TABLE `cinema` ADD UNIQUE KEY `uk_cinema_name` (`name`);

INSERT INTO `cinema` (`name`, `brand`, `district`, `address`, `tags`, `distance`, `create_time`) VALUES
('厦门万达影城（集美IOI店）',        '万达', '集美区', '集美区杏林湾路398号IOI Mall 4楼',                '退,改签,折扣卡', 1.7,  NOW()),
('厦门万达影城（湖里万达店）',       '万达', '湖里区', '湖里区仙岳路4666号万达广场4楼',                  '退,改签',       5.2,  NOW()),
('厦门金逸影城（SM广场店）',         '金逸', '思明区', '思明区嘉禾路468号SM城市广场1期5楼',              '退,改签,折扣卡', 8.1,  NOW()),
('厦门金逸影城（火车站店）',         '金逸', '思明区', '思明区厦禾路835号罗宾森广场4楼',                 '退',            6.5,  NOW()),
('厦门横店影视城（世茂海峡店）',     '横店', '思明区', '思明区湖滨南路1号世茂海峡大厦4楼',               '退,改签',       7.3,  NOW()),
('厦门CGV影城（万象城店）',          'CGV',  '思明区', '思明区湖滨东路99号华润万象城5楼',                '退,改签,折扣卡', 9.8,  NOW()),
('厦门万象影城（万象城店）',         '万象', '思明区', '思明区湖滨东路99号华润万象城4楼',                '退,改签',       9.8,  NOW()),
('厦门万象影城（SM新生活广场店）',   '万象', '湖里区', '湖里区仙岳路4666号SM新生活广场4楼',              '退,折扣卡',     5.5,  NOW()),
('厦门万达影城（杏林店）',           '万达', '集美区', '集美区杏林湾商务营运中心二期4楼',                '退,改签',       3.2,  NOW()),
('厦门金逸影城（灌口店）',           '金逸', '集美区', '集美区灌口镇双桥路万科里4楼',                    '退',            12.5, NOW()),
('厦门横店影视城（集美世茂店）',     '横店', '集美区', '集美区杏林湾路与集美大道交汇处世茂广场4楼',      '退,改签,折扣卡', 2.1,  NOW()),
('厦门中影国际影城（同安店）',       '其他', '同安区', '同安区银湖路与环城南路交汇处4楼',                '退,改签',       15.7, NOW()),
('厦门中影国际影城（海沧店）',       '其他', '海沧区', '海沧区滨湖路1号阿罗海城市广场4楼',               '退',            18.9, NOW()),
('厦门博纳国际影城（翔安店）',       '其他', '翔安区', '翔安区新店镇翔安大道1号4楼',                     '退,改签',       22.1, NOW()),
('厦门万达影城（翔安店）',           '万达', '翔安区', '翔安区马巷镇翔安南路1号4楼',                     '退,改签',       24.5, NOW()),
('厦门金逸影城（同安银城店）',       '金逸', '同安区', '同安区银城路1号银城广场4楼',                     '退,折扣卡',     14.3, NOW()),
('厦门横店影视城（同安店）',         '横店', '同安区', '同安区大同街道环城北路1号4楼',                   '退,改签',       16.8, NOW()),
('厦门CGV影城（五缘湾店）',          'CGV',  '湖里区', '湖里区五缘湾道1号建发湾悦城4楼',                 '退,改签,折扣卡', 11.2, NOW()),
('厦门万象影城（集美店）',           '万象', '集美区', '集美区杏林湾路1号万科里4楼',                     '退,改签',       1.9,  NOW()),
('厦门博纳国际影城（思明店）',       '其他', '思明区', '思明区莲前西路1号瑞景商业广场4楼',               '退',            10.5, NOW()),
('厦门中影国际影城（湖里店）',       '其他', '湖里区', '湖里区兴隆路1号4楼',                             '退,改签',       6.8,  NOW()),
('厦门金逸影城（海沧店）',           '金逸', '海沧区', '海沧区海沧大道1号4楼',                           '退,改签,折扣卡', 19.5, NOW()),
('厦门万达影城（海沧店）',           '万达', '海沧区', '海沧区海沧大道888号4楼',                         '退,改签',       20.2, NOW()),
('厦门横店影视城（翔安店）',         '横店', '翔安区', '翔安区翔安大道1号4楼',                           '退',            23.4, NOW())
ON DUPLICATE KEY UPDATE `brand` = VALUES(`brand`);

-- ============================================================
-- 初始化数据：电影（16 部）
-- 用 title 作为幂等键
-- ============================================================

ALTER TABLE `movie` ADD UNIQUE KEY `uk_movie_title` (`title`);

INSERT INTO `movie` (`title`, `poster`, `score`, `tag`, `release_date`, `status`,
                     `want_count`, `director`, `actors`, `duration`, `plot`, `genres`, `create_time`) VALUES
('空枪',                      '/images/poster1.jpg',  9.5,  NULL,      '2026-09-22', 'showing',  NULL,      '王小明',           '张三,李四,王五',                           128, '本片讲述了都市青年在一次意外中重拾人生信念的故事。',                    '剧情,犯罪',        NOW()),
('八仙！',                    '/images/poster2.jpg',  9.7,  '2DIMAX',  '2026-07-18', 'showing',  NULL,      '张艺谋',           '何仙姑,吕洞宾,铁拐李,汉钟离',              135, '影片以家喻户晓的传统民间故事为灵感创作，讲述八位仙人各显神通的故事。', '奇幻,喜剧,动作',   NOW()),
('欢迎来龙餐馆',              '/images/poster3.jpg',  9.7,  '2DIMAX',  '2026-09-22', 'showing',  NULL,      '陈可辛',           '葛优,黄渤,沈腾',                           145, '一间藏在城市角落的小餐馆，连接着几代人的悲欢离合。',                  '剧情,喜剧',        NOW()),
('奥德赛',                    '/images/poster4.jpg',  9.5,  '2DIMAX',  '2026-09-22', 'showing',  NULL,      '克里斯托弗·诺兰',  '马特·达蒙,汤姆·霍兰德,安妮·海瑟薇',       172, '一场跨越山海的归乡之旅，命运与意志的较量。',                          '科幻,冒险,剧情',   NOW()),
('复仇者联盟4：终局之战',     '/images/poster5.jpg',  9.2,  '2DIMAX',  '2026-09-22', 'showing',  NULL,      '罗素兄弟',         '小罗伯特·唐尼,克里斯·埃文斯,斯嘉丽·约翰逊', 181, '超级英雄集结，逆转无限战争的最终一战。',                              '动作,科幻,冒险',   NOW()),
('鸳鸯楼底',                  '/images/poster6.jpg',  8.9,  '3D',      '2026-09-22', 'showing',  NULL,      '杜琪峰',           '刘德华,古天乐,张家辉',                     118, '老楼里三个家庭的命运，在时代洪流中交织。',                            '剧情,悬疑',        NOW()),
('痴迷',                      '/images/poster7.jpg',  9.0,  NULL,      '2026-09-22', 'showing',  NULL,      '李安',             '梁朝伟,汤唯,王力宏',                       158, '一段关于执念与救赎的都市爱情故事。',                                  '剧情,爱情',        NOW()),
('蜘蛛侠：崭新之日',          '/images/poster8.jpg',  9.2,  '2DIMAX',  '2026-09-22', 'showing',  NULL,      '乔·沃茨',          '汤姆·赫兰德,赞达亚,雅各布·巴特朗',         142, '蜘蛛侠在新时代的全新冒险，面对前所未有的挑战。',                      '动作,科幻,冒险',   NOW()),
('水东游',                    '/images/poster9.jpg',  NULL, NULL,      '2026-10-23', 'upcoming', 1238,      '宁浩',             '徐峥,黄渤,王宝强',                         128, '一场关于时间与记忆的奇幻漂流。',                                      '剧情,奇幻',        NOW()),
('追光者',                    '/images/poster10.jpg', NULL, NULL,      '2026-10-23', 'upcoming', 345,       '贾樟柯',           '赵涛,廖凡,董子健',                         135, '时代浪潮下小人物的坚韧与希望。',                                      '剧情',             NOW()),
('明月照他乡',                '/images/poster11.jpg', NULL, NULL,      '2026-10-24', 'upcoming', 726,       '李睿珺',           '海清,武仁林,杨光锐',                       133, '异乡人的孤独与温暖，月光下的相聚与离别。',                            '剧情,家庭',        NOW()),
('肆条',                      '/images/poster12.jpg', NULL, NULL,      '2026-10-24', 'upcoming', 639,       '宁浩',             '黄渤,沈腾,徐峥',                           116, '四条命运线交织，一场黑色幽默。',                                      '喜剧,犯罪',        NOW()),
('惊悚的诞生',                '/images/poster13.jpg', NULL, NULL,      '2026-10-24', 'upcoming', 345,       '温子仁',           '帕特里克·威尔森,维拉·法米加',             106, '一部电影的幕后，隐藏着更深的恐怖。',                                  '恐怖,惊悚',        NOW()),
('老江湖',                    '/images/poster15.jpg', NULL, NULL,      '2026-10-25', 'upcoming', 68734,     '杜琪峰',           '古天乐,刘德华,张家辉',                     124, '退隐江湖的老大，被迫再度出山。',                                      '动作,犯罪',        NOW()),
('红孩儿火焰山之王',          '/images/poster16.jpg', NULL, NULL,      '2026-10-25', 'upcoming', 30942,     '饺子',             '吕艳婷,囧森瑟夫,瀚墨',                     110, '神话史诗的全新演绎，红孩儿的成长之路。',                              '动画,奇幻',        NOW())
ON DUPLICATE KEY UPDATE `score` = VALUES(`score`);

-- ============================================================
-- 初始化数据：影厅（每影院 5 个）
-- 用 (cinema_id, name) 作为幂等键
-- ============================================================

ALTER TABLE `hall` ADD UNIQUE KEY `uk_hall_cinema_name` (`cinema_id`, `name`);

-- 用一个存储过程遍历所有影院，批量插入影厅
DROP PROCEDURE IF EXISTS `init_halls`;
DELIMITER $$
CREATE PROCEDURE `init_halls`()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE cid BIGINT;
    DECLARE cur CURSOR FOR SELECT id FROM cinema;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO cid;
        IF done = 1 THEN LEAVE read_loop; END IF;

        INSERT INTO hall (cinema_id, name, type, rows, cols) VALUES
            (cid, '1号厅',   NULL,         6,  10),
            (cid, '2号厅',   NULL,         7,  12),
            (cid, '3号厅',   'RealD厅',    5,  8),
            (cid, 'IMAX厅',  'IMAX厅',     10, 16),
            (cid, 'VIP厅',   '杜比全景声厅', 4, 6)
        ON DUPLICATE KEY UPDATE rows = VALUES(rows);
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;

CALL `init_halls`();
DROP PROCEDURE IF EXISTS `init_halls`;

-- ============================================================
-- 初始化数据：今日票房（5 条）
-- ============================================================
INSERT INTO `daily_box_office` (`rank_num`, `movie_title`, `amount`) VALUES
(1, '奥德赛',         9256.0),
(2, '欢迎来龙餐馆',   4661.0),
(3, '空枪',           3037.0),
(4, '八仙！',         1736.0),
(5, '密档',           1412.0);

-- ============================================================
-- 初始化数据：最受期待（3 条）
-- ============================================================
INSERT INTO `expected_movie` (`rank_num`, `movie_title`, `want_count`) VALUES
(1, '复仇者联盟4：终局之战', 2239942),
(2, '生化危机：爆发夜',      144997),
(3, '神探之痕迹',            91012);

-- ============================================================
-- 初始化数据：TOP100（3 条）
-- ============================================================
INSERT INTO `top100_movie` (`rank_num`, `movie_title`, `score`) VALUES
(1, '我不是药神',     9.6),
(2, '肖申克的救赎',   9.8),
(3, '海上钢琴师',     9.3);

-- ============================================================
-- 初始化数据：默认管理员（可选）
-- 用户名 admin，密码 Admin@123
-- BCrypt hash 已预生成，可直接用于账号密码登录
-- ============================================================
INSERT INTO `t_user` (`username`, `password`, `phone`, `create_time`, `role`, `deleted`)
VALUES ('admin',
        '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa',
        NULL,
        NOW(),
        'ADMIN',
        b'0')
ON DUPLICATE KEY UPDATE `role` = 'ADMIN';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 执行完成
-- ============================================================
SELECT '✅ 数据库初始化完成' AS result;

-- 快速验证
SELECT '用户'   AS table_name, COUNT(*) AS cnt FROM t_user   UNION ALL
SELECT '电影',              COUNT(*)          FROM movie      UNION ALL
SELECT '影院',              COUNT(*)          FROM cinema     UNION ALL
SELECT '影厅',              COUNT(*)          FROM hall       UNION ALL
SELECT '今日票房',          COUNT(*)          FROM daily_box_office;