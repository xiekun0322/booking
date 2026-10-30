-- =============================================================================
-- 星幕影票 —— 数据库 DDL（唯一权威来源 / Single Source of Truth）
-- =============================================================================
-- 【定位】本文件是表结构的唯一权威定义。Hibernate 的 ddl-auto 已固定为 none，
--        不再由实体注解反向生成表；实体注解只负责 ORM 映射。
--
-- 【执行方式】由 DBA / root 等具备 CREATE 权限的账号执行一次：
--        mysql -u root -p starscreen < src/main/resources/schema.sql
--        应用运行账号 starscreen_app 只有 DML 权限，不需要也不应该拿到 DDL 权限。
--
-- 【幂等】全部使用 CREATE TABLE IF NOT EXISTS，可重复执行不报错。
--        注意：IF NOT EXISTS 只保证「不重复建表」，不会修正已存在表的差异；
--        表结构调整请写增量脚本（见 scripts/migrate-*.sql）。
--
-- 【MySQL 8.0 注意】rows / cols 是保留字，作为列名必须加反引号。
--        实体 Hall.java 里已用 @Column(name = "`rows`") 对应，两处需保持一致。
-- =============================================================================

-- 【字符集】必须显式声明，不能依赖客户端默认值。
--   mysql 客户端默认 default-character-set=auto，会跟随系统 locale；
--   容器/Linux 无 LANG 时 locale=C，客户端按 latin1 发送，
--   本文件里的中文注释与标识符会被双重编码（UTF-8 字节被当 latin1 再转一次）。
--   显式 SET NAMES 后，无论客户端默认是什么，服务端都按 utf8mb4 解释输入。
SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 用户表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(100),
    phone       VARCHAR(20)  UNIQUE,
    create_time VARCHAR(30),
    role        VARCHAR(20),
    -- Boolean 在 MySQL 下由 Hibernate 映射为 BIT(1)，此处保持同一物理类型，
    -- 避免将来启用 ddl-auto=validate 时校验失败。
    deleted     BIT(1)       NOT NULL DEFAULT b'0'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 电影表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS movie (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(100) NOT NULL,
    poster       VARCHAR(500),
    score        DOUBLE,
    tag          VARCHAR(50),
    release_date VARCHAR(20),
    status       VARCHAR(20),
    want_count   INT,
    director     VARCHAR(100),
    actors       VARCHAR(500),
    duration     INT,
    plot         TEXT,
    genres       VARCHAR(200),
    create_time  VARCHAR(30),
    KEY idx_movie_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 影院表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cinema (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    brand       VARCHAR(50),
    district    VARCHAR(50),
    address     VARCHAR(255),
    tags        VARCHAR(200),
    distance    DOUBLE,
    -- 实体 Cinema.java 有此字段，旧版脚本漏建，会导致
    -- "Unknown column 'c1_0.phone'" (MySQL 1054)
    phone       VARCHAR(20),
    create_time VARCHAR(30),
    KEY idx_cinema_brand (brand),
    KEY idx_cinema_district (district)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 影厅表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hall (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_id BIGINT      NOT NULL,
    name      VARCHAR(50) NOT NULL,
    type      VARCHAR(50),
    `rows`    INT         NOT NULL,
    `cols`    INT         NOT NULL,
    KEY idx_hall_cinema (cinema_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 排期表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedule (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id    BIGINT NOT NULL,
    cinema_id   BIGINT NOT NULL,
    hall_id     BIGINT NOT NULL,
    cinema_name VARCHAR(100),
    hall_name   VARCHAR(50),
    start_time  VARCHAR(20),
    end_time    VARCHAR(20),
    language    VARCHAR(50),
    price       DOUBLE,
    -- date 在 MySQL 8 中为非保留关键字，可作列名；实体 Schedule.date 直接映射
    `date`      VARCHAR(20),
    KEY idx_schedule_movie_date (movie_id, `date`),
    KEY idx_schedule_cinema (cinema_id),
    KEY idx_schedule_hall (hall_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 座位表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seat (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    row_num     INT    NOT NULL,
    col_num     INT    NOT NULL,
    status      VARCHAR(20) DEFAULT 'available',
    order_id    BIGINT,
    -- 座位在「场次 + 排 + 座」维度唯一：既防重复建座，也是防超卖的最后一道闸门
    UNIQUE KEY uk_seat_schedule_pos (schedule_id, row_num, col_num),
    KEY idx_seat_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 订单表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(50) NOT NULL UNIQUE,
    user_id     BIGINT      NOT NULL,
    schedule_id BIGINT      NOT NULL,
    movie_id    BIGINT,
    movie_title VARCHAR(100),
    cinema_name VARCHAR(100),
    hall_name   VARCHAR(50),
    -- 实体 Order 中这三个字段是 LocalDateTime，必须用 DATETIME。
    -- 旧版脚本写成 VARCHAR，靠 MySQL 隐式转换才没报错，但排序 / DATE() 统计会失真。
    show_time   DATETIME,
    seats       VARCHAR(500),
    -- 金额用 DECIMAL，不能用 DOUBLE（存在 0.1 + 0.2 精度问题）
    total_price DECIMAL(10, 2),
    status      VARCHAR(20),
    create_time DATETIME,
    pay_time    DATETIME,
    ticket_code VARCHAR(20),
    KEY idx_order_user (user_id),
    KEY idx_order_schedule (schedule_id),
    KEY idx_order_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 侧边栏榜单：今日票房
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS daily_box_office (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num    INT,
    movie_title VARCHAR(100),
    amount      DOUBLE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 侧边栏榜单：最受期待
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expected_movie (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num    INT,
    movie_title VARCHAR(100),
    want_count  INT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- -----------------------------------------------------------------------------
-- 侧边栏榜单：TOP 100
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS top100_movie (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num    INT,
    movie_title VARCHAR(100),
    score       DOUBLE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
