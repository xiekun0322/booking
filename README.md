# 星幕影票 StarScreen Ticketing

> 基于 Spring Boot 3 + Thymeleaf + MySQL 的电影票购买系统

## 📖 项目简介

星幕影票是一个完整的在线电影购票系统，支持用户浏览电影、选择场次、在线选座、下单支付、生成取票码的完整购票流程。系统还提供后台管理功能，管理员可以查看票房统计、管理电影和场次。

## ✨ 功能特性

### 用户端

- 🎬 **电影浏览**：正在热映、即将上映
- 🔍 **电影搜索**：按标题模糊搜索
- 🎭 **电影详情**：导演、主演、时长、类型
- 🏢 **选影院场次**：多影院、多场次，自动过滤过期场次
- 💺 **可视化选座**：不同影厅不同布局（IMAX / 1号厅 / 2号厅 / 3号厅 / VIP厅）
- 🛒 **锁座下单**：10 分钟超时自动释放
- 💳 **模拟支付**：生成取票码
- 📋 **订单管理**：按状态筛选（全部 / 待支付 / 已支付 / 已取消）
- 👤 **用户系统**：手机号验证码登录/注册、账号密码登录、绑定账号、改密、注销

### 管理端

- 📊 **数据概览**：订单总数、票房、热销 TOP5
- 📈 **票房趋势图**：ECharts 折线图 + 饼图
- 🎬 **电影管理**：增删改查
- 📅 **场次管理**：增删改查

## 🛠 技术栈

| 分类 | 技术 |
|---|---|
| **后端框架** | Spring Boot 3.5.17 |
| **持久层** | Spring Data JPA + Hibernate |
| **模板引擎** | Thymeleaf |
| **数据库** | MySQL 8.x |
| **前端** | 原生 HTML / CSS / JavaScript |
| **图表库** | ECharts 5.4.3 |
| **构建工具** | Maven |
| **JDK 版本** | Java 21 |

## 🏗 系统架构

```mermaid
flowchart TB
    User([用户浏览器]) -->|HTTP| App

    subgraph App[Spring Boot 应用 :8080]
        direction TB

        subgraph Filter[Servlet Filter]
            F1[SecurityHeadersFilter 安全响应头]
        end

        subgraph Interceptor[拦截器]
            I1[LoginInterceptor 登录校验]
            I2[AdminInterceptor 管理员校验]
        end

        subgraph Controller[Controller 层]
            C1[REST API]
            C2[页面路由 PageController]
        end

        subgraph Service[Service 层]
            S1[业务逻辑 + 事务]
        end

        subgraph Repository[Repository 层]
            R1[Spring Data JPA]
        end

        subgraph Common[公共组件]
            M1[Result 统一响应]
            M2[GlobalExceptionHandler 全局异常]
        end

        Filter --> Interceptor --> Controller
        Controller --> Service
        Service --> Repository
    end

    App -->|JDBC| DB[(MySQL 数据库)]

    DB --> T1[movie]
    DB --> T2[schedule]
    DB --> T3[seat]
    DB --> T4[t_order]
    DB --> T5[t_user]
    DB --> T6[cinema / hall]
    DB --> T7[daily_box_office 等侧边栏表]
```

## 🗄 数据库设计

```mermaid
erDiagram
    CINEMA ||--o{ HALL : "1:N"
    MOVIE ||--o{ SCHEDULE : "1:N"
    SCHEDULE ||--o{ SEAT : "1:N"
    T_USER ||--o{ T_ORDER : "1:N"
    T_ORDER ||--o{ SEAT : "1:N"

    MOVIE {
        bigint id PK
        varchar title
        varchar poster
        double score
        varchar tag
        varchar release_date
        varchar status
        int want_count
        varchar director
        varchar actors
        int duration
        text plot
        varchar genres
    }

    CINEMA {
        bigint id PK
        varchar name
        varchar brand
        varchar district
        varchar address
        varchar tags
        double distance
    }

    HALL {
        bigint id PK
        bigint cinema_id FK
        varchar name
        varchar type
        int rows
        int cols
    }

    SCHEDULE {
        bigint id PK
        bigint movie_id FK
        bigint cinema_id FK
        bigint hall_id FK
        varchar cinema_name
        varchar hall_name
        varchar start_time
        varchar end_time
        varchar language
        double price
        varchar date
    }

    SEAT {
        bigint id PK
        bigint schedule_id FK
        int row_num
        int col_num
        varchar status
        bigint order_id FK
    }

    T_ORDER {
        bigint id PK
        varchar order_no
        bigint user_id FK
        bigint schedule_id FK
        bigint movie_id
        varchar movie_title
        varchar cinema_name
        varchar hall_name
        varchar show_time
        varchar seats
        double total_price
        varchar status
        varchar create_time
        varchar pay_time
        varchar ticket_code
    }

    T_USER {
        bigint id PK
        varchar username
        varchar password
        varchar phone
        varchar create_time
        varchar role
        boolean deleted
    }
```

## 🔄 座位状态机

```mermaid
stateDiagram-v2
    [*] --> available
    available --> locked : 下单锁座（条件 UPDATE）
    locked --> sold : 支付成功
    locked --> available : 超时 / 取消
    sold --> [*]
```

## 📋 下单流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as OrderController
    participant S as OrderService
    participant R as Repository
    participant DB as MySQL

    U->>C: POST /api/orders {scheduleId, seats}
    Note over C: 从 session 拿 userId
    C->>S: createOrder(userId, scheduleId, seats)
    S->>R: 查场次
    R->>DB: SELECT schedule
    S->>R: 查座位（校验 available）
    R->>DB: SELECT seat
    S->>R: 创建订单
    R->>DB: INSERT t_order
    S->>R: 原子锁座
    R->>DB: UPDATE seat SET status='locked' WHERE status='available'
    Note over S: 影响行数=0 → 抛异常 → 整体回滚
    S-->>C: Order
    C-->>U: JSON 响应
    U->>C: POST /api/orders/{id}/pay
    C->>S: pay(userId, orderId)
    S->>R: 更新订单状态
    R->>DB: UPDATE t_order SET status='paid', ticket_code=...
    S->>R: 座位 locked → sold
    S-->>C: Order（含取票码）
```

## 📦 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.0+

### 2. 建数据库

```sql
CREATE DATABASE movie_ticketing DEFAULT CHARACTER SET utf8mb4;
```

### 3. 修改配置

编辑 `src/main/resources/application.properties`：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/movie_ticketing?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=你的密码
```

### 4. 启动

```bash
mvn spring-boot:run
```

**或**：

```bash
mvn clean package
java -jar target/starscreen-ticketing-1.0.0.jar
```

### 5. 访问

浏览器打开 `http://localhost:8080/`

首次启动会自动建表 + 初始化 16 部电影、24 家影院、场次、座位数据。

### 6. 测试账号

| 用户名 | 密码 | 角色 |
|---|---|---|
| alice | 123456 | USER |
| bob | 123456 | USER |

**或使用手机号验证码登录**：任意合法手机号，点击"发送验证码"，演示环境会弹窗显示验证码并自动填入。

## 📁 项目结构

```
src/main/java/com/starscreen/
├── common/                     公共类
│   ├── Result.java             统一响应
│   ├── BusinessException       业务异常
│   └── GlobalExceptionHandler  全局异常处理
├── config/                     配置
│   ├── DataInitializer         数据初始化
│   ├── CorsConfig              CORS 白名单
│   ├── SecurityHeadersConfig   安全响应头过滤器
│   ├── LoginInterceptor        登录拦截器
│   ├── AdminInterceptor        管理员拦截器
│   └── WebConfig               拦截器注册
├── controller/                 控制器
│   ├── MovieController         电影 API
│   ├── OrderController         订单 API
│   ├── SeatController          座位 API
│   ├── UserController          用户 API
│   ├── CinemaController        影院 API
│   ├── ScheduleController      场次 API
│   ├── TestController          侧边栏测试 API
│   ├── AdminMovieController    电影管理 API
│   ├── AdminScheduleController 场次管理 API
│   └── PageController          页面路由
├── dto/                        数据传输对象
│   ├── CreateOrderRequest      下单请求
│   ├── SeatVO                  座位 VO
│   ├── UserVO                  用户 VO
│   ├── LoginRequest            登录请求
│   ├── RegisterRequest         注册请求
│   ├── SmsLoginRequest         短信登录请求
│   ├── SendSmsRequest          发送验证码请求
│   ├── BindAccountRequest      绑定账号请求
│   ├── ChangePasswordRequest   改密请求
│   ├── ResetPasswordRequest    重置密码请求
│   ├── DeleteAccountRequest    注销请求
│   ├── AdminStats              后台统计 VO
│   └── SidebarData             侧边栏聚合 VO
├── entity/                     JPA 实体
│   ├── Movie
│   ├── Schedule
│   ├── Seat
│   ├── Order
│   ├── User
│   ├── Cinema
│   ├── Hall
│   ├── DailyBoxOffice
│   ├── ExpectedMovie
│   └── Top100Movie
├── repository/                 数据访问
│   ├── MovieRepository
│   ├── ScheduleRepository
│   ├── SeatRepository
│   ├── OrderRepository
│   ├── UserRepository
│   ├── CinemaRepository
│   ├── HallRepository
│   ├── DailyBoxOfficeRepository
│   ├── ExpectedMovieRepository
│   └── Top100MovieRepository
└── service/                    业务逻辑
    ├── MovieService
    ├── OrderService
    ├── SeatService
    ├── UserService
    ├── ScheduleService
    ├── CinemaService
    ├── SmsService
    ├── SidebarService
    └── AdminService
```

## 🔐 安全性

| 防护 | 实现 |
|---|---|
| **SQL 注入** | JPA 参数化查询，无字符串拼接 SQL |
| **XSS** | Thymeleaf 自动转义 + CSP 响应头 |
| **越权** | Session userId 校验（订单、支付、取消） |
| **CORS** | 全局白名单（localhost:8080 / 127.0.0.1:8080） |
| **安全头** | CSP / X-Frame-Options / X-Content-Type-Options / Referrer-Policy |
| **异常处理** | 全局异常统一返回，不泄露堆栈 |
| **参数校验** | @Valid + @NotNull / @NotBlank / @Size / @Pattern |
| **密码存储** | BCrypt 加密（BCryptPasswordEncoder） |
| **并发抢座** | 条件 UPDATE（`WHERE status='available'`）保证原子性 |
| **管理员隔离** | AdminInterceptor 校验 role == "ADMIN" |

## 📊 性能

JMeter 压力测试结果（1000 并发 × 10 循环 = 10000 请求）：

| 指标 | 值 |
|---|---|
| **QPS** | 953 |
| **平均响应** | 723ms |
| **99% 百分位** | 1087ms |
| **错误率** | 0.00% |

## 🎯 核心设计

### 1. 事务保证数据一致性

```java
@Transactional
public Order createOrder(Long userId, Long scheduleId, List<String> seatLabels) {
    // 1. 校验场次、座位
    // 2. 创建订单
    // 3. 条件 UPDATE 原子锁座
    // 任何一步失败 → 整体回滚
}
```

### 2. 并发抢座安全

```java
@Modifying
@Query("UPDATE Seat s SET s.status = 'locked', s.orderId = :orderId " +
       "WHERE s.id = :id AND s.status = 'available'")
int lockSeat(@Param("id") Long id, @Param("orderId") Long orderId);
```

**为什么不用 `save()`**：`save()` 是"先查后改"，两个并发请求可能同时查到 `available` 然后都改成 `locked`。条件 UPDATE 在数据库层面保证原子性，只有第一个请求能成功。

### 3. 定时任务释放超时订单

```java
@Scheduled(fixedRate = 30_000)
public void cancelExpiredOrders() {
    // 每 30 秒扫描一次
    // 超过 10 分钟的 pending 订单 → cancelled
    // 释放座位
}
```

依赖启动类的 `@EnableScheduling`。

### 4. 用户隔离

- **下单**：从 session 拿 userId，用户无法伪造
- **查订单 / 支付 / 取消**：校验 userId 匹配
- **管理员接口**：AdminInterceptor 校验 session.role

### 5. 软删除

用户注销采用软删除（`deleted = true`），历史订单保留，账号无法再登录。所有查询方法使用 `...AndDeletedFalse` 后缀自动过滤。

## 📸 项目截图

> 截图放在 `docs/images/` 目录

### 首页
![首页](docs/images/01-home.png)

### 电影详情
![电影详情](docs/images/02-movie-detail.png)

### 选座页
![选座](docs/images/04-seat.png)

### 支付页
![支付](docs/images/05-payment.png)

### 取票码
![取票码](docs/images/06-ticket.png)

### 我的订单
![订单](docs/images/07-orders.png)

### 后台统计
![后台](docs/images/08-admin.png)

### ECharts 图表
![图表](docs/images/09-charts.png)

## 📄 License

MIT License

---

**Made with ❤️ by XieKun**