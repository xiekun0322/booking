# 拍卖模块上线运行手册

> 角色：Ops。适用于把本次拍卖改动部署到测试/生产环境，并处理常见故障与回滚。
> 相关代码：`com.starscreen.auction`、`scripts/auction-schema.sql`、`scripts/migrate-2026-11-funds.sql`。

## 1. 部署前置

| 项 | 要求 | 说明 |
|---|---|---|
| 数据库 | MySQL 8，两个库：`starscreen`（业务）、`auction`（拍卖） | 分库是为了隔离出价热点与票务下单 |
| 账号权限 | 应用账号只需 DML；DDL 用 root/DBA 执行 | 应用账号无 CREATE，启动时不会自动建表 |
| 中间件 | RabbitMQ 可写；Redis 非拍卖必需 | Redis 仅票务短信链路使用 |
| JDK | 与项目一致（Spring Boot 3.4 / Java 21） | — |

## 2. 部署顺序（不可颠倒）

```bash
# ① 建表（root 执行，可重复执行）
mysql -u root -p --default-character-set=utf8mb4 < scripts/auction-schema.sql

# ② 存量库补资金四表（仅当 auction 库已存在旧结构时）
mysql -u root -p --default-character-set=utf8mb4 auction < scripts/migrate-2026-11-funds.sql

# ③ 删除参数已变更的队列（关键，否则应用启动报 406 直接失败）
curl -u <user>:<pass> -X DELETE http://<rabbit-host>:15672/api/queues/%2F/auction.deposit.queue
curl -u <user>:<pass> -X DELETE http://<rabbit-host>:15672/api/queues/%2F/auction.dead.queue

# ④ 启动应用
java -jar app.jar --server.port=8080
```

> 队列为什么必须删：RabbitMQ 在声明队列时逐项比对参数（`x-max-length`、`x-overflow`、DLX），
> 任一项不同就返回 `PRECONDITION_FAILED - inequivalent arg`，应用启动即失败。
> 队列里若有存量消息，删除前先确认可丢弃（拍卖事件都是可重放的幂等指令）。

## 3. 配置检查（启动前逐项确认）

| 配置 | 生产值 | 不设的后果 |
|---|---|---|
| `app.auction.datasource.url/username/password` | 指向 auction 库，密码走环境变量 `DB_PASSWORD` | 拍卖接口全部 500 |
| `app.auction.mock-pay-enabled` | **false** | 任何人都能把支付单标成已支付（等于免费提货） |
| `app.auction.deposit.ratio/min/cap` | 按业务定，默认 5% / 500 / 50000 | 保证金定价不符预期 |
| `app.auction.deal.pay-deadline-hours` | 默认 24 | 悔拍判定时点偏移 |
| `app.auction.outbox.*` | 默认即可 | relay 间隔过长会拖慢退款 |
| `spring.task.scheduling.pool.size` | ≥4 | 关拍任务阻塞会饿死开拍扫描 |

## 4. 启动验证

**日志关键字**（正常应看到）：

```
Started MovieTicketingApplication
【拍卖】N 个拍品已开拍          ← 有到点拍品时
【资金链路】发件箱待投递 0 条，DEAD 0 条
```

**接口冒烟**（匿名可执行）：

```bash
curl -s http://<host>:8080/api/auction/items?status=ACTIVE&page=1&size=5   # 期望 code=200
curl -s -o /dev/null -w '%{http_code}\n' http://<host>:8080/auction       # 期望 200
```

**数据核对**：`SELECT COUNT(*) FROM auction.auction_outbox WHERE status='PENDING';`
正常应接近 0；持续增长说明 broker 不可达。

## 5. 常见故障对照

| 现象 | 首查 | 处置 |
|---|---|---|
| 启动报 `PRECONDITION_FAILED - inequivalent arg 'x-max-length'` | 队列参数与代码不一致 | 按第 2 步 ③ 删队列后重启 |
| 启动报 `Unknown column 'o1_0.cinemaName'` | EntityManagerFactory 命名策略缺失 | 确认 `BusinessDataSourceConfig` 里有 `CamelCaseToUnderscoresNamingStrategy` |
| 日志刷 `no transaction is in progress`（发件箱） | relay 事务传播方式 | 应为 `REQUIRES_NEW`（`OutboxRelay` 构造器） |
| 拍品到点不开拍 | `SELECT COUNT(*) FROM auction_item WHERE status='SCHEDULED' AND start_time<=NOW()` | 有积压看调度线程栈与拍卖库连接池 |
| 未中标者钱不退 | `auction_deposit` 的 `status` 与 `refund_trigger_at` | 若 `refund_trigger_at` 为空查 outbox 与消费者；若 `REFUND_DEAD` 走人工 |
| 成交后没有成交单 | `auction_deal` 是否唯一键冲突 | 撞 `uk_deal_item` 属幂等，查审计日志 `DEAL_CREATED` |

## 6. 监控建议（当前靠日志，后续补指标）

| 指标 | 阈值 | 含义 |
|---|---|---|
| `auction_outbox` status=PENDING | >200 持续 5 分钟 | broker 或消费者异常 |
| `auction_outbox` status=DEAD | >0 | 需人工重放（payload 保留） |
| `auction_deposit` status=REFUND_DEAD | >0 | 退款失败需人工 |
| `SCHEDULED` 且 `start_time<=NOW()` | >0 持续 1 分钟 | 开拍调度卡住 |

## 7. 回滚方案

| 层级 | 动作 | 影响 |
|---|---|---|
| 配置层 | 关闭 `app.auction.enabled=false` | 定时任务/消费者/模拟支付停止，已写数据不动；拍卖接口仍可读 |
| 应用层 | 回滚到上一版本 jar | 拍卖表为新增，旧版本不识别但不受影响（票务无外键关联） |
| 数据层 | 不删表、不删数据 | 拍卖与票务无外键，回滚票务无需清拍卖数据 |

> 不建议回滚后立即重建同名表：`auction_deal` 的唯一键承担幂等职责，重建会丢失成交历史，导致重复成交风险。

## 8. 上线检查单

- [ ] DDL 已在目标库执行（`SHOW TABLES` 能看到 7 张 auction_* 表）
- [ ] 旧队列已删除
- [ ] `app.auction.mock-pay-enabled=false`
- [ ] 拍卖库账号可读写（`SELECT 1` 通过）
- [ ] 启动日志无 ERROR，出现 `Started`
- [ ] 冒烟接口返回 200
- [ ] 发件箱 PENDING 计数正常
- [ ] 已通知业务方：保证金走支付通道，退款为异步（秒级到分钟级）
