# 星幕影票 - 安全测试报告

## 一、测试概述

### 1.1 测试目标

对星幕影票系统进行全面的安全性评估，覆盖：

- Web 应用层常见漏洞（OWASP Top 10）
- 认证 / 授权 / 越权
- 响应头配置
- 代码级安全缺陷

### 1.2 测试时间

2026-10-09

### 1.3 测试范围

| 项目 | 内容 |
|---|---|
| **目标地址** | `http://localhost:8080` |
| **接口范围** | 所有 REST API + 页面路由（ZAP 共发现 113 个端点） |
| **扫描方式** | 被动扫描（Passive Scan） |
| **认证方式** | 登录态 + 未登录态双跑 |

### 1.4 测试工具

| 工具 | 类型 | 说明 |
|---|---|---|
| SonarLint | 静态扫描 | IDE 插件，代码级扫描 |
| OWASP ZAP 2.17.0 | 动态扫描 | 模拟黑客攻击 |
| Postman | 手工测试 | 接口安全测试（越权、参数篡改） |

---

## 二、测试结果汇总

### 2.1 OWASP ZAP 扫描结果

**本轮结果**：

| 严重性 | 数量 |
|---|---|
| High | 0 |
| Medium | 3 |
| Low | 0 |
| Info | 2 |
| **合计** | **5** |

**与上一轮对比**：

| 严重性 | 修复前 | 上一轮 | 本轮 |
|---|---|---|---|
| High | 0 | 0 | 0 |
| Medium | 2 | 0 | **3** ⚠️ |
| Low | 1 | 0 | 0 |
| Info | 4 | 4 | 2 |

> **说明**：上一轮修复的 3 个告警（CSP Header Not Set、Missing Anti-clickjacking、X-Content-Type-Options Missing）已确认为 0。本轮 Medium 回升，原因是**引入 CSP 响应头后，CSP 指令本身不够严格**，触发 ZAP 新告警（详见第三章）。

### 2.2 端点统计

| 指标 | 值 |
|---|---|
| 总端点数 | 113 |
| 返回 2xx 的响应占比 | 95% |
| 返回 4xx 的响应占比 | 4% |
| `text/html` 占比 | 96% |
| GET 方法占比 | 100% |
| 慢响应占比 | 34% |

---

## 三、告警详情

### 3.1 Medium（3 条，全部针对 CSP 指令）

#### 3.1.1 CSP: Failure to Define Directive with No Fallback

| 项目 | 内容 |
|---|---|
| **严重性** | Medium |
| **Confidence** | High |
| **出现位置** | `GET /login`（5 次） |
| **CWE** | CWE-693 |
| **OWASP Top 10** | A05:2021 Security Misconfiguration |

**说明**：CSP 中缺少 `form-action` 指令。该指令**没有 fallback 到 `default-src`**，不写等于允许任意来源提交表单，攻击者可构造钓鱼表单窃取用户输入。

**当前响应头**：

```
Content-Security-Policy: default-src 'self'; img-src 'self' data:;
  style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline';
  font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'
```

**缺失的指令**：

| 指令 | 风险 |
|---|---|
| `form-action` | 表单可提交到任意站点（钓鱼） |
| `base-uri` | 攻击者可注入 `<base>` 标签劫持相对路径 |
| `object-src` | 允许嵌入 Flash / Java Applet（已过时，但推荐声明） |

**修复方案**：

```java
response.setHeader("Content-Security-Policy",
    "default-src 'self'; " +
    "img-src 'self' data:; " +
    "style-src 'self' 'unsafe-inline'; " +
    "script-src 'self' 'unsafe-inline'; " +
    "font-src 'self' data:; " +
    "connect-src 'self'; " +
    "frame-ancestors 'none'; " +
    "form-action 'self'; " +      // ← 新增
    "base-uri 'self'; " +         // ← 新增
    "object-src 'none'");         // ← 新增
```

---

#### 3.1.2 CSP: script-src 不安全-内联

| 项目 | 内容 |
|---|---|
| **严重性** | Medium |
| **Confidence** | High |
| **出现位置** | `GET /login`（5 次） |
| **CWE** | CWE-693 |

**说明**：`script-src` 包含 `'unsafe-inline'`，等于允许页面执行任意内联脚本。一旦攻击者找到注入点（比如某个字段未转义），CSP 就形同虚设。

**问题根源**：项目大量使用内联 `<script>`，例如 `login.html` 的登录逻辑、`seat.html` 的选座逻辑，都是内联脚本。

**修复方案（三选一）**：

| 方案 | 说明 | 工作量 |
|---|---|---|
| **a) Nonce**（推荐） | 每次请求生成随机 nonce，写入 CSP 头和 `<script nonce="...">` | 中 |
| **b) Hash** | 对每个内联脚本计算 SHA-256，写入 CSP | 大（每次改脚本都要重算） |
| **c) 外链** | 把内联脚本拆到独立 `.js` 文件 | 大（要改所有模板） |

**Nonce 实现示例**：

```java
String nonce = Base64.getEncoder().encodeToString(
    SecureRandom.getInstanceStrong().generateSeed(16));
request.setAttribute("cspNonce", nonce);
response.setHeader("Content-Security-Policy",
    "script-src 'self' 'nonce-" + nonce + "'; ...");
```

模板里：

```html
<script th:attr="nonce=${cspNonce}"> ... </script>
```

---

#### 3.1.3 CSP: style-src 不安全-内联

| 项目 | 内容 |
|---|---|
| **严重性** | Medium |
| **Confidence** | High |
| **出现位置** | `GET /login`（5 次） |
| **CWE** | CWE-693 |

**说明**：`style-src` 包含 `'unsafe-inline'`，允许页面执行任意内联样式。XSS 攻击者可利用内联样式做 UI 欺骗或 CSS 注入。

**问题根源**：项目所有模板都用 `<style>` 块内联样式。

**修复方案**：同 3.1.2，用 nonce 或外链 CSS。

---

### 3.2 Info（2 条）

#### 3.2.1 Information Disclosure - Suspicious Comments

| 项目 | 内容 |
|---|---|
| **严重性** | Info |
| **Confidence** | Medium |
| **出现位置** | `GET /movie/detail/3`（17 次） |
| **CWE** | CWE-615 |

**说明**：`movie-detail.html` 顶部的模板说明注释包含 `USER`、`⚠️ 已知问题` 等关键词，被 ZAP 判定为"可疑注释"。这些注释**不会造成实际漏洞**，但会泄露开发信息（数据库字段、内部 API 路径、已知缺陷清单）。

**修复方案**：

- 用 Thymeleaf 注释替换：`<!--/* ... */-->`（渲染时自动删除）
- 或把开发文档移到 `.md` 文件

---

#### 3.2.2 现代 Web 应用程序

| 项目 | 内容 |
|---|---|
| **严重性** | Info |
| **出现位置** | `GET /login` |

**说明**：页面无 `<a>` 链接但有 `<script>`，ZAP 建议用 AJAX Spider 扫描。**这是提示性信息，不是漏洞，无需修改。**

---

## 四、核心安全性评估

| 漏洞类型 | 状态 | 防护措施 |
|---|---|---|
| **SQL 注入** | ✅ 免疫 | JPA 参数化查询，无字符串拼接 |
| **XSS** | ⚠️ 部分缓解 | Thymeleaf 自动转义 + CSP；`'unsafe-inline'` 削弱防护 |
| **CSRF** | ⚠️ 待改进 | 未引入 Spring Security，无 CSRF Token |
| **越权访问** | ✅ 已防 | 订单查询 / 支付 / 取消校验 `session.userId` |
| **越权支付** | ✅ 已防 | `OrderService.pay` 校验 `userId.equals(order.getUserId())` |
| **后台保护** | ✅ 已防 | `AdminInterceptor` 校验 `role == "ADMIN"` |
| **密码存储** | ✅ 已防 | BCrypt 加密（`BCryptPasswordEncoder`） |
| **软删除** | ✅ 已防 | 用户注销后 `deleted=true`，查询自动过滤 |
| **并发抢座** | ✅ 已防 | 条件 UPDATE（`WHERE status='available'`）保证原子性 |
| **接口限流** | ⚠️ 待改进 | 无 Redis 限流，登录接口可被爆破 |
| **CORS** | ✅ 已防 | 全局白名单 |
| **点击劫持** | ✅ 已防 | `X-Frame-Options: DENY` |
| **MIME 嗅探** | ✅ 已防 | `X-Content-Type-Options: nosniff` |
| **敏感信息泄露** | ⚠️ 部分缓解 | 异常不返回堆栈；但模板注释需清理 |

---

## 五、修复优先级

### 🔴 立即修复（本周内）

#### 5.1 补全 CSP 指令

在 `SecurityHeadersConfig` 的 CSP 头中增加：

```java
"form-action 'self'; " +
"base-uri 'self'; " +
"object-src 'none'"
```

改完可消除 "CSP: Failure to Define Directive with No Fallback"。

#### 5.2 消除 `'unsafe-inline'`

引入 Nonce 机制（见 3.1.2），可同时消除：

- "CSP: script-src 不安全-内联"
- "CSP: style-src 不安全-内联"

修复后 Medium 可从 3 降到 0。

### 🟡 中期改进（上线前）

#### 5.3 清理模板开发注释

`movie-detail.html` 等模板顶部的说明注释改为 Thymeleaf 注释或外移。

#### 5.4 加 HSTS 头（生产环境 HTTPS 时）

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
```

#### 5.5 加 Permissions-Policy

```
Permissions-Policy: geolocation=(), camera=(), microphone=()
```

#### 5.6 加 COOP / COEP / CORP

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: same-origin
```

### 🟢 长期规划

#### 5.7 引入 Spring Security

一次性解决 CSRF、Session 固定、密码编码、权限控制。

#### 5.8 接口限流

登录 / 发送验证码接口加 Redis 限流，防止：

- 短信轰炸
- 暴力破解密码

#### 5.9 CSP 违规上报

```
report-uri /csp-report
report-to csp-endpoint
```

收集真实环境的 CSP 违规，用于持续收紧策略。

---

## 六、结论

**项目已具备基本安全防护，无高危漏洞，但 CSP 策略需要收紧。**

### 6.1 总体评价

| 维度 | 评价 |
|---|---|
| SQL 注入 | ✅ 天然免疫（JPA） |
| XSS | ⚠️ 有防护但 CSP 不够严 |
| 越权 | ✅ 已防 |
| 认证 / 授权 | ✅ 已防 |
| 密码存储 | ✅ BCrypt |
| 并发安全 | ✅ 条件 UPDATE |
| 安全响应头 | ⚠️ 基本齐备，CSP 待收紧 |
| CSRF | ⚠️ 待补 |
| 限流 | ⚠️ 待补 |

### 6.2 与上一轮对比

- 上一轮 3 条告警**全部已修复** ✅
- 本轮新增 3 条 Medium，**全部是 CSP 指令不够严格引起**
- 属于"修完旧问题、暴露新问题"的正常演进
- 修复这 3 条后，Medium 可降为 0

### 6.3 生产环境待改进

| 优先级 | 项目 | 时间节点 |
|---|---|---|
| 1 | CSP 收紧（Nonce + 补全指令） | 本周内 |
| 2 | CSRF Token（Spring Security） | 上线前 |
| 3 | IP 限流（Redis + 拦截器） | 上线前 |
| 4 | HTTPS + HSTS | 部署时 |
| 5 | 清理模板开发注释 | 上线前 |

### 6.4 安全评级

| 项 | 结果 |
|---|---|
| 高危漏洞 | 0 |
| 中危漏洞 | 3（CSP 配置） |
| 低危漏洞 | 0 |
| **综合评级** | **B+**（可用，待加固） |

---

## 附录 A：CSP 修复前后对比

| 指令 | 修复前 | 修复后（目标） |
|---|---|---|
| `default-src` | `'self'` | `'self'` |
| `script-src` | `'self' 'unsafe-inline'` | `'self' 'nonce-xxx'` |
| `style-src` | `'self' 'unsafe-inline'` | `'self' 'nonce-xxx'` |
| `img-src` | `'self' data:` | `'self' data:` |
| `font-src` | `'self' data:` | `'self' data:` |
| `connect-src` | `'self'` | `'self'` |
| `frame-ancestors` | `'none'` | `'none'` |
| `form-action` | ❌ 缺失 | `'self'` |
| `base-uri` | ❌ 缺失 | `'self'` |
| `object-src` | ❌ 缺失 | `'none'` |

## 附录 B：安全响应头清单

| 响应头 | 当前状态 | 目标状态 |
|---|---|---|
| `Content-Security-Policy` | ⚠️ 待收紧 | ✅ 完整 + nonce |
| `X-Frame-Options` | ✅ `DENY` | ✅ 保持 |
| `X-Content-Type-Options` | ✅ `nosniff` | ✅ 保持 |
| `X-XSS-Protection` | ⚠️ `1; mode=block` | 建议移除（现代浏览器已废弃） |
| `Referrer-Policy` | ✅ `strict-origin-when-cross-origin` | ✅ 保持 |
| `Strict-Transport-Security` | ❌ 未设置 | HTTPS 时加 |
| `Permissions-Policy` | ❌ 未设置 | 建议加 |

---

**报告生成时间**：2026-10-09 13:59:47
**报告生成工具**：OWASP ZAP 2.17.0 by Checkmarx
**报告编写**：XieKun