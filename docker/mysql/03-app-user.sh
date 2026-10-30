#!/bin/sh
# =============================================================================
# 容器首次初始化：创建「仅有 DML 权限」的应用账号
# =============================================================================
# 由 mysql 官方镜像的 entrypoint 在数据目录为空时执行，此时 mysqld 以
# 临时实例运行（--skip-networking），root 可免密走 socket 登录。
#
# 为什么不直接用镜像的 MYSQL_USER / MYSQL_PASSWORD？
#   那两个变量会执行 GRANT ALL ON <db>.* —— 包含 CREATE/ALTER/DROP/INDEX，
#   等于把 DDL 权限交给了应用。应用只要有一处 SQL 注入就能改表结构。
#   这里显式只给 SELECT/INSERT/UPDATE/DELETE，与 scripts/init-db.sql 的
#   最小权限策略保持一致。
#
# 注意：本文件可能被 entrypoint 用 `source` 方式加载（文件无可执行位时），
#       所以不要写 `set -e` —— 它会污染 entrypoint 所在 shell 的选项。
# =============================================================================

DB_NAME="${MYSQL_DATABASE:-starscreen}"
APP_USER="${APP_DB_USER:-starscreen_app}"
APP_PASS="${APP_DB_PASSWORD:-}"

if [ -z "$APP_PASS" ]; then
    echo "[03-app-user] 错误：APP_DB_PASSWORD 为空，拒绝创建空密码账号" >&2
    exit 1
fi

# 密码会被拼进单引号字符串，含引号或反斜杠会破坏 SQL 甚至造成注入
case "$APP_PASS" in
    *"'"* | *"\\"*)
        echo "[03-app-user] 错误：DB_PASSWORD 不能包含单引号或反斜杠" >&2
        exit 1
        ;;
esac

# 应用从另一个容器连进来，host 必须是 '%' 而不是 localhost
SQL="
CREATE USER IF NOT EXISTS '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASS}';
ALTER USER '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASS}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${DB_NAME}\`.* TO '${APP_USER}'@'%';
FLUSH PRIVILEGES;
"

# entrypoint 提供了 docker_process_sql（走 socket、自动带 root 凭据）；
# 若脚本被独立执行，则回退到普通 mysql 客户端。
if command -v docker_process_sql >/dev/null 2>&1; then
    printf '%s\n' "$SQL" | docker_process_sql
else
    printf '%s\n' "$SQL" | mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}"
fi

if [ $? -ne 0 ]; then
    echo "[03-app-user] 错误：应用账号创建失败" >&2
    exit 1
fi

echo "[03-app-user] 已创建 ${APP_USER}@'%'，仅授予 ${DB_NAME}.* 的 SELECT/INSERT/UPDATE/DELETE"
