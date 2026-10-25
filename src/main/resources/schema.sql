-- =========================================================
-- 1. 清理旧表 (按外键依赖顺序反向删除)
-- =========================================================
DROP TABLE IF EXISTS t_order;
DROP TABLE IF EXISTS seat;
DROP TABLE IF EXISTS schedule;
DROP TABLE IF EXISTS hall;
DROP TABLE IF EXISTS cinema;
DROP TABLE IF EXISTS movie;
DROP TABLE IF EXISTS t_user;
DROP TABLE IF EXISTS daily_box_office;
DROP TABLE IF EXISTS expected_movie;
DROP TABLE IF EXISTS top100_movie;

-- =========================================================
-- 2. 建表语句
-- =========================================================

-- 用户表
CREATE TABLE t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100),
    phone VARCHAR(20) UNIQUE,
    create_time VARCHAR(30),
    role VARCHAR(20),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- 电影表
CREATE TABLE movie (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    poster VARCHAR(255),
    score DOUBLE,
    tag VARCHAR(50),
    release_date VARCHAR(20),
    status VARCHAR(20),
    want_count INT,
    director VARCHAR(100),
    actors VARCHAR(255),
    duration INT,
    plot TEXT,
    genres VARCHAR(100),
    create_time VARCHAR(30)
);

-- 影院表
CREATE TABLE cinema (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    brand VARCHAR(50),
    district VARCHAR(50),
    address VARCHAR(255),
    tags VARCHAR(100),
    distance DOUBLE,
    create_time VARCHAR(30)
);

-- 影厅表
CREATE TABLE hall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    type VARCHAR(50),
    rows INT NOT NULL,
    cols INT NOT NULL
);

-- 排期表
CREATE TABLE schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id BIGINT NOT NULL,
    cinema_id BIGINT NOT NULL,
    hall_id BIGINT NOT NULL,
    cinema_name VARCHAR(100),
    hall_name VARCHAR(50),
    start_time VARCHAR(20),
    end_time VARCHAR(20),
    language VARCHAR(20),
    price DOUBLE,
    date VARCHAR(20)
);

-- 座位表
CREATE TABLE seat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    row_num INT NOT NULL,
    col_num INT NOT NULL,
    status VARCHAR(20) DEFAULT 'available',
    order_id BIGINT
);

-- 订单表 (注意字段名长度和类型与你之前的 Order 实体类匹配)
CREATE TABLE t_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(50) UNIQUE NOT NULL,
    user_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    movie_id BIGINT,
    movie_title VARCHAR(100),
    cinema_name VARCHAR(100),
    hall_name VARCHAR(50),
    show_time VARCHAR(50),
    seats VARCHAR(500),
    total_price DOUBLE,
    status VARCHAR(20),
    create_time VARCHAR(30),
    pay_time VARCHAR(30),
    ticket_code VARCHAR(20)
);

-- 侧边栏榜单表：今日票房
CREATE TABLE daily_box_office (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num INT,
    movie_title VARCHAR(100),
    amount DOUBLE
);

-- 侧边栏榜单表：最受期待
CREATE TABLE expected_movie (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num INT,
    movie_title VARCHAR(100),
    want_count INT
);

-- 侧边栏榜单表：TOP 100
CREATE TABLE top100_movie (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rank_num INT,
    movie_title VARCHAR(100),
    score DOUBLE
);