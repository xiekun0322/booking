#!/usr/bin/env bash
# =============================================================================
# 冒烟测试：票务回归 + 拍卖主流程 + 管理端权限 + 通知 + 监控
# =============================================================================
# 【用途】上线前/改动后一次性验证关键链路，不依赖浏览器，全部走 HTTP。
#
# 【前置】
#   1. 应用已启动（默认 http://localhost:8080）
#   2. MySQL 可连接且库里有场次与可用座位
#   3. 脚本需要 root 权限改库（模拟"到点关拍"“支付超时"这类时间推移，
#      否则要等几十分钟；这是测试专用手段，不是生产路径）
#
# 【用法】
#   bash scripts/smoke-test.sh
#   BASE=http://host:8080 MYSQL_BIN=/usr/bin/mysql MYSQL_ARGS="-u root -pPASS" bash scripts/smoke-test.sh
#
# 【说明】
#   - 会创建测试数据（拍品标题带 SMOKE 前缀），不自动清理，便于失败时复查；
#     清理：DELETE FROM auction.auction_item WHERE title LIKE 'SMOKE%';
#   - 会移动一个可用座位到"已售"，跑完不还原（座位状态本就不可回滚）。
# =============================================================================
set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
# MySQL 客户端：路径与参数必须分开存。Windows 下路径含空格（Program Files），
# 若把"路径 + 参数"塞进一个变量再 $VAR 展开，会被拆成多个词，命令根本执行不到，
# 而且因为 2>/dev/null 会静默失败——表现为"所有 DB 断言都取到空值"。
MYSQL_BIN="${MYSQL_BIN:-/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe}"
MYSQL_ARGS="${MYSQL_ARGS:--h 127.0.0.1 -u root -pAbc@1234}"
DB_BIZ="${DB_BIZ:-starscreen}"
DB_AUC="${DB_AUC:-auction}"

PASS=0
FAIL=0
FAILED_CASES=""

# 每次运行的唯一标识：出价幂等键必须带它。
# 幂等键是"item + key"去重的，用固定键会导致第二次运行直接返回上次的结果
# （包括上次"被拒绝"的结果），表现为"明明缴了保证金却一直说重复提交"。
RUN_ID=$(date +%s)

# ---------- 断言 ----------
pass() { PASS=$((PASS + 1)); printf "  \033[32mPASS\033[0m  %s\n" "$1"; }
fail() {
    FAIL=$((FAIL + 1))
    FAILED_CASES="${FAILED_CASES}\n    - $1（实际：${2:-}）"
    printf "  \033[31mFAIL\033[0m  %s\n" "$1"
    [ -n "${2:-}" ] && printf "        实际：%s\n" "$2"
}
section() { printf "\n\033[1m== %s ==\033[0m\n" "$1"; }

# 断言包含：assert_contains <描述> <期望子串> <实际文本>
assert_contains() {
    case "$3" in
        *"$2"*) pass "$1" ;;
        *) fail "$1" "$(echo "$3" | head -c 200)" ;;
    esac
}
# 断言相等
assert_eq() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "$3（期望 $2）"; fi
}

# 取 JSON 字段（不依赖 jq：只处理本项目返回的扁平字符串/数字字段）
jget() { echo "$1" | grep -o "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

# 必须带 --default-character-set=utf8mb4：Windows 下 mysql 客户端默认可能按 GBK 输出，
# 座位标签"1排1座"会变成乱码再发给服务端，报"座位格式错误"。
q() { "$MYSQL_BIN" $MYSQL_ARGS --default-character-set=utf8mb4 -N -e "USE $1; $2" 2>/dev/null; }
qauc() { q "$DB_AUC" "$1"; }
qbiz() { q "$DB_BIZ" "$1"; }

ts() { date +%s%3N; }
nonce() { echo "smoke-$$-$RANDOM-$(date +%s%N)"; }

# 带防重放头的 POST（/api/orders/** 强制要求 X-Timestamp + X-Nonce）
post_guard() {
    local url="$1" body="$2" jar="$3"
    curl -s -b "$jar" -X POST "$url" \
        -H "Content-Type: application/json" \
        -H "X-Timestamp: $(ts)" -H "X-Nonce: $(nonce)" \
        ${body:+-d "$body"}
}
post() {
    local url="$1" body="$2" jar="$3"
    curl -s -b "$jar" -X POST "$url" -H "Content-Type: application/json" \
        ${body:+-d "$body"}
}
get() { curl -s -b "$3" "$1"; }

login() {
    local user="$1" jar="$2"
    curl -s -c "$jar" -X POST "$BASE/api/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$user\",\"password\":\"Test@1234\"}" > /dev/null
}

# =============================================================================
section "0. 环境检查"
# =============================================================================
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
assert_eq "应用可访问（首页 200）" 200 "$code"

# 先自检数据库：本脚本大量断言靠查库（模拟时间推移、校验落库结果），
# 库不通会让所有 DB 断言静默取到空值，看起来像"业务全挂了"，极易误判。
assert_eq "拍卖库可连接" "1" "$(q "$DB_AUC" "SELECT 1;")"
assert_eq "业务库可连接" "1" "$(q "$DB_BIZ" "SELECT 1;")"

SCHEDULE_ID=$(qbiz "SELECT id FROM schedule ORDER BY id DESC LIMIT 1;")
if [ -z "$SCHEDULE_ID" ]; then
    echo "  无可用场次，票务流程跳过"; SCHEDULE_ID=""
else
    pass "存在场次 scheduleId=$SCHEDULE_ID"
fi

login auc_t1 /tmp/smoke_t1.txt
login auc_t2 /tmp/smoke_t2.txt
login auc_admin /tmp/smoke_admin.txt
assert_contains "测试账号可登录" "auc_t2" "$(get "$BASE/api/user/current" "" /tmp/smoke_t2.txt)"

# =============================================================================
section "1. 票务回归（验证 OrderService.pay 修复）"
# =============================================================================
if [ -n "$SCHEDULE_ID" ]; then
    SEAT=$(qbiz "SELECT CONCAT(row_num,'排',col_num,'座') FROM seat
                 WHERE schedule_id=$SCHEDULE_ID AND status='available' LIMIT 1;")
    if [ -z "$SEAT" ]; then
        echo "  无可用座位，票务流程跳过"
    else
        add="票务流程 1/2（座位 $SEAT）"

        R=$(post_guard "$BASE/api/orders" "{\"scheduleId\":$SCHEDULE_ID,\"seats\":[\"$SEAT\"],\"captchaTicket\":\"test\"}" /tmp/smoke_t2.txt)
        OID=$(jget "$R" id)
        assert_contains "$add 下单成功" '"status":"pending"' "$R"

        post_guard "$BASE/api/orders/$OID/pay" "" /tmp/smoke_t2.txt > /dev/null
        D=$(get "$BASE/api/orders/$OID" "" /tmp/smoke_t2.txt)
        assert_contains "$add 支付后状态为 paid" '"status":"paid"' "$D"
        TC=$(jget "$D" ticketCode)
        if echo "$TC" | grep -Eq '^[A-Z0-9]{4}-[A-Z0-9]{4}$'; then
            pass "$add 取票码格式 $TC"
        else
            fail "$add 取票码格式" "$TC"
        fi

        SEAT_ST=$(qbiz "SELECT status FROM seat WHERE schedule_id=$SCHEDULE_ID
                        AND CONCAT(row_num,'排',col_num,'座')='$SEAT';")
        assert_eq "$add 座位已变 sold" "sold" "$SEAT_ST"

        R=$(post_guard "$BASE/api/orders/$OID/pay" "" /tmp/smoke_t2.txt)
        assert_contains "$add 重复支付被拒" "已支付" "$R"

        # --- 取消流程：另起一单，取消后座位应释放 ---
        SEAT2=$(qbiz "SELECT CONCAT(row_num,'排',col_num,'座') FROM seat
                      WHERE schedule_id=$SCHEDULE_ID AND status='available' LIMIT 1;")
        if [ -n "$SEAT2" ]; then
            add2="票务流程 2/2（座位 $SEAT2）"
            R=$(post_guard "$BASE/api/orders" "{\"scheduleId\":$SCHEDULE_ID,\"seats\":[\"$SEAT2\"],\"captchaTicket\":\"test\"}" /tmp/smoke_t2.txt)
            OID2=$(jget "$R" id)
            R=$(post_guard "$BASE/api/orders/$OID2/cancel" "" /tmp/smoke_t2.txt)
            assert_contains "$add2 取消后状态 cancelled" '"status":"cancelled"' "$R"
            ST2=$(qbiz "SELECT status FROM seat WHERE schedule_id=$SCHEDULE_ID
                        AND CONCAT(row_num,'排',col_num,'座')='$SEAT2';")
            assert_eq "$add2 座位已释放为 available" "available" "$ST2"
        fi
    fi
fi

# =============================================================================
section "2. 拍卖：发布权限收口"
# =============================================================================
TITLE="SMOKE-$(date +%s)"
START=$(date -d "+8 seconds" "+%Y-%m-%d %H:%M:%S")
END=$(date -d "+1800 seconds" "+%Y-%m-%d %H:%M:%S")
BODY="{\"title\":\"$TITLE\",\"category\":\"POSTER\",\"startPrice\":8000.00,\"bidIncrement\":100.00,\"startTime\":\"$START\",\"endTime\":\"$END\",\"autoExtendSeconds\":0}"

R=$(post "$BASE/api/auction/items" "$BODY" /tmp/smoke_t2.txt)
assert_contains "普通用户发布被拒（403）" '"code":403' "$R"

R=$(post "$BASE/api/auction/items" "$BODY" /tmp/smoke_admin.txt)
ITEM=$(jget "$R" id)
assert_contains "管理员发布成功" '"status":"SCHEDULED"' "$R"
# 保证金 = clamp(8000 × 5%, 500, 50000) = 500（下限生效）
assert_eq "保证金按 clamp 定价（8000 → 500）" "500.00" "$(qauc "SELECT deposit FROM auction_item WHERE id=$ITEM;")"

# =============================================================================
section "3. 拍卖：报名 → 出价 → 关拍 → 成交 → 付款"
# =============================================================================
sleep 15
assert_eq "到点自动开拍" "ACTIVE" "$(qauc "SELECT status FROM auction_item WHERE id=$ITEM;")"

R=$(post "$BASE/api/auction/items/$ITEM/bids" '{"amount":8100.00,"idempotencyKey":"smoke-nodep-'"$RUN_ID"'"}' /tmp/smoke_t1.txt)
assert_contains "未缴保证金不能出价" "DEPOSIT_REQUIRED" "$R"

R=$(post "$BASE/api/auction/items/$ITEM/enroll" "" /tmp/smoke_t2.txt)
PAYNO=$(jget "$R" payNo)
assert_contains "报名生成支付单" "MP" "$PAYNO"

post "$BASE/api/auction/mock-pay/$PAYNO/success" "" "" > /dev/null
assert_eq "保证金支付后 HELD" "HELD" "$(qauc "SELECT status FROM auction_deposit WHERE item_id=$ITEM LIMIT 1;")"

R=$(post "$BASE/api/auction/items/$ITEM/bids" '{"amount":8100.00,"idempotencyKey":"smoke-bid-1-'"$RUN_ID"'"}' /tmp/smoke_t2.txt)
assert_contains "出价成功" '"accepted":true' "$R"
R=$(post "$BASE/api/auction/items/$ITEM/bids" '{"amount":8100.00,"idempotencyKey":"smoke-bid-1-'"$RUN_ID"'"}' /tmp/smoke_t2.txt)
assert_contains "同幂等键重放不重复加价" "8100" "$R"
R=$(post "$BASE/api/auction/items/$ITEM/bids" '{"amount":8150.00,"idempotencyKey":"smoke-bid-2-'"$RUN_ID"'"}' /tmp/smoke_t2.txt)
assert_contains "低于最低加价被拒" '"accepted":false' "$R"

# 把结束时间推到过去，模拟关拍（否则要等 30 分钟）
qauc "UPDATE auction_item SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=$ITEM;" > /dev/null
sleep 14
assert_eq "关拍后拍品 SETTLED" "SETTLED" "$(qauc "SELECT status FROM auction_item WHERE id=$ITEM;")"
DEAL=$(qauc "SELECT id FROM auction_deal WHERE item_id=$ITEM;")
assert_eq "生成成交单" "PENDING_PAYMENT" "$(qauc "SELECT status FROM auction_deal WHERE item_id=$ITEM;")"
assert_eq "成交后通知 DEAL_WON 已写入" "1" "$(qauc "SELECT COUNT(*) FROM auction_notification WHERE type='DEAL_WON' AND biz_id=$DEAL;")"

R=$(post "$BASE/api/auction/deals/$DEAL/pay" "" /tmp/smoke_t2.txt)
DPAY=$(jget "$R" payNo)
assert_contains "成交款生成支付单" "MP" "$DPAY"
post "$BASE/api/auction/mock-pay/$DPAY/success" "" "" > /dev/null
assert_eq "成交款支付后 PAID" "PAID" "$(qauc "SELECT status FROM auction_deal WHERE id=$DEAL;")"

sleep 16
assert_eq "中标者保证金已退回（D4）" "REFUNDED" "$(qauc "SELECT status FROM auction_deposit WHERE item_id=$ITEM LIMIT 1;")"

# 通知数量用 ">=1" 而不是 "=1"：库里保留历次测试数据，
# 断言具体条数会在第二次运行就失败，与功能无关。
REFCNT=$(qauc "SELECT COUNT(*) FROM auction_notification WHERE type='DEPOSIT_REFUNDED';")
if [ "${REFCNT:-0}" -ge 1 ]; then
    pass "退款通知已写入（累计 $REFCNT 条）"
else
    fail "退款通知已写入" "$REFCNT"
fi

# =============================================================================
section "4. 拍卖：悔拍（保证金不退，D6）"
# =============================================================================
TITLE2="SMOKE-FORFEIT-$(date +%s)"
START2=$(date -d "+6 seconds" "+%Y-%m-%d %H:%M:%S")
END2=$(date -d "+1200 seconds" "+%Y-%m-%d %H:%M:%S")
R=$(post "$BASE/api/auction/items" "{\"title\":\"$TITLE2\",\"category\":\"POSTER\",\"startPrice\":2000.00,\"bidIncrement\":50.00,\"startTime\":\"$START2\",\"endTime\":\"$END2\",\"autoExtendSeconds\":0}" /tmp/smoke_admin.txt)
ITEM2=$(jget "$R" id)
sleep 12
R=$(post "$BASE/api/auction/items/$ITEM2/enroll" "" /tmp/smoke_t2.txt)
PAYNO2=$(jget "$R" payNo)
post "$BASE/api/auction/mock-pay/$PAYNO2/success" "" "" > /dev/null
post "$BASE/api/auction/items/$ITEM2/bids" '{"amount":2050.00,"idempotencyKey":"smoke-forfeit-1-'"$RUN_ID"'"}' /tmp/smoke_t2.txt > /dev/null

qauc "UPDATE auction_item SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=$ITEM2;" > /dev/null
sleep 14
DEAL2=$(qauc "SELECT id FROM auction_deal WHERE item_id=$ITEM2;")
qauc "UPDATE auction_deal SET pay_deadline=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=$DEAL2;" > /dev/null
sleep 26
assert_eq "超时未付判 CANCELLED" "CANCELLED" "$(qauc "SELECT status FROM auction_deal WHERE id=$DEAL2;")"
assert_eq "悔拍保证金 FORFEITED" "FORFEITED" "$(qauc "SELECT status FROM auction_deposit WHERE item_id=$ITEM2 LIMIT 1;")"
FECNT=$(qauc "SELECT COUNT(*) FROM auction_notification WHERE type='DEPOSIT_FORFEITED';")
if [ "${FECNT:-0}" -ge 1 ]; then
    pass "悔拍通知已写入（累计 $FECNT 条）"
else
    fail "悔拍通知已写入" "$FECNT"
fi

# =============================================================================
section "5. 通知接口"
# =============================================================================
R=$(get "$BASE/api/auction/notifications/unread-count" "" /tmp/smoke_t2.txt)
assert_contains "未读数接口可用" '"code":200' "$R"
R=$(get "$BASE/api/auction/notifications/mine?page=1&size=5" "" /tmp/smoke_t2.txt)
assert_contains "我的消息列表可用" '"typeText"' "$R"
NID=$(jget "$R" id)
R=$(post "$BASE/api/auction/notifications/$NID/read" "" /tmp/smoke_t2.txt)
assert_contains "标记已读成功" '"code":200' "$R"

# =============================================================================
section "6. 监控与探活"
# =============================================================================
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/metrics")
assert_eq "匿名访问 /actuator/metrics 被拒" 403 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health/liveness")
assert_eq "liveness 探活 UP" 200 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health/readiness")
assert_eq "readiness 探活 UP" 200 "$code"
R=$(get "$BASE/actuator/metrics/auction.outbox.pending" "" /tmp/smoke_admin.txt)
assert_contains "业务指标可读" '"value"' "$R"
R=$(get "$BASE/actuator/metrics/auction.notify.dead" "" /tmp/smoke_admin.txt)
assert_contains "通知死信指标可读" '"value"' "$R"

# =============================================================================
printf "\n\033[1m== 汇总 ==\033[0m\n"
printf "  通过 %d，失败 %d\n" "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf "  失败明细：%b\n" "$FAILED_CASES"
    exit 1
fi
printf "  \033[32m全部通过\033[0m\n"
