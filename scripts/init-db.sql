-- =============================================================================
-- 星幕影票 —— 数据库与应用账号初始化（用 root 执行，一次即可）
-- =============================================================================
-- 执行：
--     mysql -u root -p --default-character-set=utf8mb4 < scripts/init-db.sql
--
-- 本脚本只做三件事：建库、建应用账号、授予「最小必要权限」。
-- 不建表 —— 建表由 src/main/resources/schema.sql 负责，两者职责分离。
--
-- 【为什么应用账号不给 DDL 权限】
--   线上应用只需要读写数据。一旦给它 CREATE/ALTER/DROP，
--   任何一处 SQL 注入或误操作都能改表结构，损失不可逆。
--   所以：DDL 由 DBA / root 执行，应用账号只有 DML。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 数据库
-- -----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS starscreen
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- -----------------------------------------------------------------------------
-- 2. 应用账号
--    ★ 把下面的 CHANGE_ME_STRONG_PASSWORD 改成你自己的强密码，
--      然后通过环境变量 DB_PASSWORD 传给应用（不要写进配置文件）：
--        Windows:  setx DB_PASSWORD "你的密码"
--        Linux/macOS: export DB_PASSWORD="你的密码"
-- -----------------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'starscreen_app'@'localhost'
    IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- 若账号已存在，重置密码（首次执行时该语句也安全）
ALTER USER 'starscreen_app'@'localhost'
    IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- -----------------------------------------------------------------------------
-- 3. 最小权限：只给 DML，不给 CREATE / ALTER / DROP / GRANT
-- -----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON starscreen.* TO 'starscreen_app'@'localhost';

FLUSH PRIVILEGES;

-- -----------------------------------------------------------------------------
-- 4. 自检：应看到 4 条 DML 权限，且没有 CREATE/ALTER/DROP
-- -----------------------------------------------------------------------------
SHOW GRANTS FOR 'starscreen_app'@'localhost';
