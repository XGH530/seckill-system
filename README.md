# 分布式高并发秒杀系统

> 作者：向国豪 | 2027 届 · 计算机科学与技术
> 技术栈：Spring Boot 3.2.5 · MyBatis-Plus 3.5.5 · Redis 5.0.14 · Redisson 3.30.0 · RocketMQ 2.3.0 · Sentinel 2023.0.1.0 · H2/MySQL · JWT · Knife4j
>
> 🔗 **在线演示**：<http://8.133.222.0:8081>

---

## 一、这个项目解决什么问题

秒杀的本质矛盾：**瞬时流量（10 万 QPS）与系统承载能力（数据库约 2000 QPS）之间的数量级差距**。

本项目通过「层层过滤 + 异步削峰 + 多重兜底」把流量逐级削平：

```
                    ┌──────────────────────────────────────────┐
   10w QPS ────────►│  L1 幂等 Token     过滤重复提交          │
                    ├──────────────────────────────────────────┤
                    │  L2 Sentinel      限流 / 热点参数         │
                    ├──────────────────────────────────────────┤
                    │  L3 Redis Lua     原子扣减库存（性能核心）│
                    ├──────────────────────────────────────────┤
                    │  L4 MQ 异步下单   削峰填谷               │
                    ├──────────────────────────────────────────┤
                    │  L5 DB 条件更新   最后防线（可靠性兜底） │
                    └──────────────────────────────────────────┘
                                     │
                                     ▼
                            实际落库 ~200 QPS
```

> 关键理念：**L1~L3 是性能优化，L4 是吞吐优化，L5 是可靠性兜底。性能可以妥协，正确性不能。**

---

## 二、快速开始

### 2.1 环境要求

| 组件 | 版本 | 必需 | 说明 |
|---|---|---|---|
| JDK | 17+ | ✅ | 实测 JDK 22 编译至 17 通过 |
| Maven | 3.8+ | ✅ | 用于构建，`mvn -v` 可验证 |
| Redis | 5.0+ | ✅ | 实测 5.0.14；脚本已做 `GETDEL` 兼容 |
| MySQL | 8.0 | ❌ | **默认不用**，demo 模式走 H2 内存库 |
| RocketMQ | 4.9+ / 5.x | ❌ | 不装自动降级为内存队列 |
| Docker | 任意 | ❌ | 可选 |

### 2.2 三种启动方式

#### 方式 A：Demo 模式（推荐，零依赖）

只需 Redis，数据库用 H2 内存库，启动时自动建表 + 初始化数据。

```bash
# 1. 启动 Redis
redis-server

# 2. 编译打包
#    （Windows 中文用户名路径下若 mvn 报路径错误，见 docs/04 排障）
mvn clean package -DskipTests

# 3. 启动（必须显式指定端口并关闭 Sentinel eager 初始化）
java -jar target/seckill-system-1.0.0.jar \
  --spring.profiles.active=demo \
  --server.port=8080 \
  --spring.cloud.sentinel.eager=false
```

#### 方式 B：IDEA 启动

1. 打开项目，等待 Maven 导入完成
2. 运行配置 → VM options 加 `-Dfile.encoding=UTF-8`
3. Active profiles 填 `demo`
4. 环境变量加 `SENTINEL_EAGER=false`（或启动参数 `--spring.cloud.sentinel.eager=false`）
5. 运行 `SeckillApplication`

#### 方式 C：完整模式（MySQL + RocketMQ）

```bash
mysql -uroot -p < src/main/resources/db/schema.sql
```

编辑 `application.yml` 配置 MySQL/Redis 连接，并设置 `seckill.mq.rocketmq-enabled=true`。

### 2.3 访问入口

| 入口 | 地址 | 说明 |
|---|---|---|
| **演示前端** | http://localhost:8080/ | **推荐**。登录、倒计时、抢购、排队、支付、订单全流程可视化 |
| 接口文档 | http://localhost:8080/doc.html | Knife4j（已配 JWT 全局 Authorize，粘一次 token 全接口生效） |
| **管理后台** | http://localhost:8080/admin.html | **管理控制台**：活动/商品/订单/用户/库存对账/指标监控，管理员账号 `admin` / `123456` |

> 线上演示环境用 systemd 守护（`Restart=always`，另加 cgroup 内存上限 `MemoryMax=620M`），
> 进程被 OOM 杀掉或异常退出会自动拉起，服务器重启也会自启；日志：`journalctl -u seckill -n 100 --no-pager`。

## 三、接口说明

| 接口 | 方法 | 说明 |
|---|---|---|
| `/auth/register` | POST | **幂等注册**（压测造数用，已存在则直接返回） |
| `/auth/login` | POST | 登录，返回 JWT。测试账号 `zhangsan` / `123456` |
| `/auth/logout` | POST | 登出（Token 加入黑名单） |
| `/goods/list` | GET | 商品列表 |
| `/goods/activity/list` | GET | **秒杀活动列表**（注意不是 `/seckill/activities`） |
| `/goods/activity/{id}` | GET | 活动详情 |
| `/seckill/token` | GET | **获取幂等 Token（秒杀前必调）** |
| `/seckill/do` | POST | **执行秒杀** |
| `/seckill/result` | GET | **查询秒杀结果（轮询）** |
| `/seckill/orders` | GET | **查询我的订单**（演示页用） |
| `/seckill/pay` | POST | 支付订单（模拟） |
| `/seckill/warmup` | POST | **预热库存到 Redis（压测前必调）** |

### 管理后台接口（需管理员角色）

`/admin/**` 里有一组**管理台接口**，在控制器上用 `@RequireAdmin` 标注，
由 `AdminAuthInterceptor` 统一校验 JWT 里的 `role=ADMIN`：

| 场景 | 返回 |
|---|---|
| 未带 Token | `401 请先登录管理员账号` |
| 普通用户（`role=USER`）的 Token | `403 该账号不是管理员，无权访问管理接口` |
| 管理员（`role=ADMIN`）的 Token | 正常返回 |

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/me` | GET | 当前管理员信息（用于前端确认登录态） |
| `/admin/overview` | GET | 概览：活动/商品/用户/订单总量 + 库存汇总 + 实时业务指标 |
| `/admin/activities` | GET | 活动列表，**同一行给出 DB 库存 / Redis 库存 / 有效订单数并标记是否一致** |
| `/admin/activity/{id}/status` | POST | 上架/下架活动（0-未开始 1-进行中 2-已结束），变更后清活动缓存 |
| `/admin/orders` | GET | 订单分页查询（按状态 / 活动过滤） |
| `/admin/orders/{orderNo}/cancel` | POST | **人工关单**：取消订单 → 回补 DB 库存 → 回补 Redis 库存 → 释放一人一单占位（幂等） |
| `/admin/users` | GET | 用户分页查询（含每人订单数） |
| `/admin/goods` | GET/POST | 商品列表 / 新增商品 |
| `/admin/goods/{id}` | PUT/DELETE | 修改 / 删除商品（逻辑删除；仍有进行中的活动时拒绝删除） |
| `/admin/health` | GET | 数据库 / Redis / JVM 健康检查 |

> 演示页（`static/index.html`）顶部「运维面板」调用的 `/admin/metrics`、`/admin/warmup/all`、
> `/admin/activity/{id}/reset-stock`、`/admin/stats` 是**刻意保持免鉴权**的（方便现场演示与压测脚本直接调用），
> 所以拦截器用注解精确区分：标注了 `@RequireAdmin` 的才校验，未标注的保持原有开放策略。
### 完整调用流程

```bash
# 1. 登录拿 Token
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"123456"}'
# → {"code":200,"data":{"nickname":"...","userId":1,"token":"eyJhbGc..."}}

# 2. 预热库存（压测前必做！）
curl -X POST "http://localhost:8080/seckill/warmup?activityId=1"

# 3. 获取幂等 Token
curl -X GET "http://localhost:8080/seckill/token?activityId=1" \
  -H "Authorization: Bearer {token}"

# 4. 执行秒杀
curl -X POST http://localhost:8080/seckill/do \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {token}" \
  -d '{"activityId":1,"idempotentToken":"a1b2c3d4..."}'
# → {"code":200,"message":"排队中，请稍后查询结果","data":{"status":0}}

# 5. 轮询结果
curl -X GET "http://localhost:8080/seckill/result?activityId=1" \
  -H "Authorization: Bearer {token}"
# → {"code":200,"data":{"status":1,"orderNo":"SK359872638547529728"}}

# 6. 支付
curl -X POST "http://localhost:8080/seckill/pay?orderNo=SK359872638547529728" \
  -H "Authorization: Bearer {token}"
```

---

## 四、核心设计

### 4.1 如何防止超卖？—— 四层防线

| 层级 | 手段 | 代码位置 | 挡住什么 |
|---|---|---|---|
| L1 | 幂等 Token | `SeckillServiceImpl#validateIdempotentToken` | 重复提交、脚本刷单 |
| L2 | Sentinel 限流 | `application.yml` + 注解 | 超出系统承载能力的流量 |
| L3 | **Redis Lua 原子扣减** | `lua/seckill_stock.lua` | 99.9% 的并发请求（性能核心） |
| L4 | **DB 条件更新** | `RocketMqConsumer`（消费者事务内调用 `deductStock`） | 极端情况（Redis 挂了/数据不一致） |

**为什么 L3 必须用 Lua？**

Redis 单命令原子，但"判断库存 > 0"+"扣减"是两步：

```java
// ❌ 错误写法：存在竞态窗口
Integer stock = redis.get(stockKey);
if (stock > 0) {              // 1000 个线程可能同时通过这里
    redis.decr(stockKey);      // → stock 变成 -999，严重超卖
}

// ✅ 正确写法：Lua 脚本在 Redis 中单线程原子执行
// lua/seckill_stock.lua
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock < num then return -1 end
return redis.call('DECRBY', stockKey, num)
```

**为什么 L4 还要留一道数据库防线？**

这叫「**不信任任何单点**」。Redis 可能因主从切换丢数据、运维误操作清库、网络分区导致脚本未执行。数据库的 `UPDATE ... WHERE seckill_stock >= 1` 由 InnoDB 行锁保证，是绝对的可靠性底线。

> ⚠️ **本项目的真实教训**（曾踩坑后修复）：L4 必须在 **MQ 消费者创建订单的同一事务内真实执行**。
> 早期版本 `deductStock` 只是定义了但从未被调用——"注释里的第 4 层"实际是死代码，
> 超时回补的守卫条件还把问题掩盖了。修复后通过对账接口验证：
> 下单后 `dbStock`、`redisStock`、`validOrderCount` 三者立刻一致（详见 `docs/06-实战踩坑记录.md` 坑 10）。

> 四层防线的定位：前三层是性能优化，第四层是可靠性兜底——**性能可以妥协，正确性不能**。

### 4.1 附：限流规则怎么配、怎么验证

第 1 层防线的规则由代码加载（`config/SentinelRuleConfig`），而不是依赖控制台：

```yaml
seckill:
  limit:
    qps: 2000      # 单机 QPS 上限；压测时调小即可观察限流
```

- 入口方法 `SeckillServiceImpl#doSeckill` 上标注 `@SentinelResource(value="seckill:do", blockHandler="doSeckillBlocked")`；
- 超过阈值时由 `doSeckillBlocked` 兜底，返回业务码 **606「当前人数过多，请稍后重试」**（限流是预期内的保护行为，不该给用户 500）；
- **快速失败**而不是排队等待：排队会拖长 RT 并占住 Tomcat 线程；
- 规则用代码管理的好处：随仓库评审/版本化、每个实例启动即生效、演示环境没有控制台也能工作。

验证方式（实测有效）：把 `qps` 改成 1 重启，连续发 6 次抢购，可看到其中 3 次返回 606，其余按正常业务逻辑返回（200 / 603 重复下单）。

> 另一个容易踩的点：`warmUpStock` 用的是 `setIfAbsent`（**故意不覆盖**已扣减的库存，否则预热一次就把卖出去的库存加回来了），
> 所以 DB 与 Redis 已经漂移时**预热修不好**，必须用 `/admin/activity/{id}/reset-stock` 做「DB + Redis + 用户占位」三方一起重置。
### 4.2 如何防止少卖？

少卖 = 库存扣了但订单没生成。本项目两条路径：

**路径 1：异常时同步回滚**
```
扣库存成功 → MQ 发送失败 → 回滚库存 + 清除用户标记 → 抛异常
```
> ⚠️ 注意执行顺序：本项目把「一人一单校验」放在「扣库存」**之前**。这样一人一单失败时库存根本没动，**无需补偿，直接减少一个回滚面**。这是实测后重构的改进点。

**路径 2：超时未支付异步回滚**
```
下单成功 → 发送 15 分钟延迟消息
        → 15 分钟后检查：订单仍是"待支付"？
            是 → 取消订单 + 回滚 DB 库存 + 回滚 Redis 库存 + 清除用户标记
            否 → 什么都不做（已支付）
```

**双保险**：`OrderTimeoutJob` 每 30 秒扫表兜底（防延迟消息丢失）。

### 4.3 如何保证一人一单？

| 层级 | 手段 | 可靠性 |
|---|---|---|
| 1 | Redis Lua `SET NX` 原子占位 | 高（但 Redis 可能丢数据） |
| 2 | **数据库唯一索引 `uk_user_activity`** | **绝对可靠** |
| 3 | 消费端 `selectByUserAndActivity` 幂等校验 | 防 MQ 重复投递 |

**为什么数据库唯一索引是必须的？** 它是唯一不可能"被绕过"的保证。即使 Redis 被清空、MQ 重复投递、代码有 bug，只要索引存在，重复插入必然抛 `DuplicateKeyException`。

> ⚠️ **追问点**：分库分表后 `uk_user_activity` 会失效（跨表无法保证唯一），此时必须依赖分布式锁。要提前想好答案。

### 4.4 为什么用 MQ 异步下单？

| | 同步链路 | 异步链路（本项目） |
|---|---|---|
| 流程 | 校验→扣库存→**写订单**→返回 | 校验→扣库存→**发消息**→立即返回 |
| 接口耗时 | ~50ms（含 DB 写） | **~5ms** |
| Tomcat 线程占用 | 全程占用 | 快速释放 |
| 吞吐量 | 受 DB 写能力限制 | 受 Redis 能力限制（高 10 倍） |
| 用户体验 | 立即知道结果 | 返回"排队中"，需轮询 |

**这就是"削峰填谷"**：把瞬时 10 万 QPS 的写请求转成 MQ 消息，消费者按数据库能承受的节奏（~200 QPS）慢慢写。

### 4.5 消息不丢、不重、有序

| 维度 | 方案 |
|---|---|
| **不丢** | 生产端同步发送 + 重试；Broker 刷盘；消费端手动 ACK；**兜底：定时任务扫表** |
| **不重** | Redis `messageId` 幂等标记 + 数据库唯一索引双重校验 |
| **有序** | 秒杀订单之间无顺序依赖，用并发消费提吞吐；若需有序，用 `MessageQueueSelector` 按 userId 取模 |

### 4.6 缓存三大问题

| 问题 | 成因 | 解决方案 | 代码位置 |
|---|---|---|---|
| **穿透** | 查不存在的数据，缓存永不命中 | 空值缓存（60s）+ 布隆过滤器 | `getActivityWithCache` |
| **击穿** | 热点 key 过期瞬间，大量请求打到 DB | 互斥锁重建 / 逻辑过期 | 可扩展 |
| **雪崩** | 大量 key 同时过期 | TTL 加随机抖动 | `ACTIVITY_CACHE_RANDOM_BOUND` |

**空值缓存为什么设 60 秒？** 若数据库后来真的新增了该活动，空值缓存会挡住真实数据。60 秒是"够挡攻击、又不影响业务"的平衡点。

**TTL 抖动怎么做的？**
```java
long baseMinutes = 30;
long jitter = (long) (Math.random() * 10);  // 0-10 分钟随机
redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(baseMinutes + jitter));
```

### 4.7 分布式锁：Redisson 的两个关键机制

**机制 1：看门狗（WatchDog）自动续期**

```java
// leaseTime = -1 时，看门狗生效
RLock lock = redissonClient.getLock(key);
lock.tryLock(3, TimeUnit.SECONDS);  // 默认锁 30s，后台每 10s 续期一次
```

没有看门狗：业务执行 15 秒、锁 TTL 10 秒 → 锁提前释放 → 第二个线程进入临界区 → **超卖**。
有了看门狗：业务线程活着锁就一直续期；业务宕机则停止续期，锁自然过期，不会死锁。

**机制 2：Lua 校验持有者，防止误删**

```
T1: 线程 A 加锁成功（TTL 10s）
T2: 线程 A 卡顿 15s
T3: 锁过期，线程 B 加锁成功
T4: 线程 A 执行 DEL lock → 把 B 的锁删了！
T5: 线程 C 也能加锁 → 临界区出现两个线程
```

所以必须用 `lua/unlock.lua` 做"比较 value 再删除"的原子操作。

**Redisson 仍未解决的问题（主动提，显深度）**：

Redis 主从架构下，主节点加锁成功后宕机、从节点尚未同步 → 锁丢失。解决方案：
- RedLock 算法（向多数派节点加锁），但有争议（Martin Kleppmann 与 antirez 的经典辩论）
- 改用 ZooKeeper（CP 模型，但性能低）
- **本项目的态度**：不依赖分布式锁保证正确性，正确性由数据库唯一索引兜底，锁只是性能优化。

### 4.8 雪花算法与时钟回拨

```
64 bit = 1(符号) + 41(时间戳) + 10(机器ID) + 12(序列号)
          ↑            ↑              ↑            ↑
        恒为0    可用 69 年      1024 台机器   每毫秒 4096 个
```

**为什么用它？** 本地生成无网络开销、趋势递增（对 B+ 树索引友好，避免页分裂）。

**时钟回拨怎么处理？** 见 `SnowflakeIdGenerator#nextId`：
- 回拨 ≤ 5ms：自旋等待
- 回拨 > 5ms：抛异常告警（宁可失败也不生成重复 ID）

### 4.9 最终一致性：对账机制

`OrderTimeoutJob#reconcileStock` 每 5 分钟执行：

```sql
SELECT a.id, a.initial_stock, a.seckill_stock,
       a.initial_stock - a.seckill_stock AS deducted,
       (SELECT COUNT(*) FROM t_seckill_order o
         WHERE o.activity_id = a.id AND o.status IN (0,1)) AS order_count
FROM t_seckill_activity a;
```

**不相等就告警。** 这是所有异步方案的最终保障——允许中间状态短暂不一致，但系统必须能发现并收敛。

> 对账接口把 DB 库存 / Redis 库存 / 有效订单数放在一起对比，可在线发现数据不一致。

---

## 五、压测与实测数据

### 5.1 已实测的结果

本仓库自带两个**纯 Java 压测程序**（不依赖 JMeter），位于 `pressure-test/`：

| 程序 | 目的 | 用法 |
|---|---|---|
| `PressureTest.java` | 单用户幂等性：同一用户狂点，验证只成功 1 次 | `java pressure-test/PressureTest.java http://127.0.0.1:8080 1` |
| `MultiUserPressureTest.java` | 多用户超卖验证：N 个用户抢 M 个库存 | `java pressure-test/MultiUserPressureTest.java http://127.0.0.1:8080 1 200` |

参数说明：
- `PressureTest <baseUrl> <activityId> [并发线程数=200] [每线程请求数=3]`
- `MultiUserPressureTest <baseUrl> <activityId> [并发用户数=200]`

跑之前先调 `/seckill/warmup` 预热库存。JDK 11+ 支持单文件源码直接运行，无需先 javac。

```bash
# 单用户幂等测试
java pressure-test/PressureTest.java http://localhost:8080 1
# → 200 并发 × 3 轮 = 600 请求 → 1 成功 / 599 业务失败(603 重复下单) / 0 系统异常

# 多用户超卖测试
java pressure-test/MultiUserPressureTest.java http://localhost:8080 1 200
# → 200 用户抢 99 库存 → 99 成功 / 100 库存不足(602) / 1 重复(603) / 0 系统异常
# → Redis 库存 99 → 0，零超卖。99 + 100 + 1 = 200，账对得上
```

### 5.2 JMeter 压测（可选）

**Step 1：安装 JMeter 5.6**，解压即用，需 JDK 8+。

**Step 2：创建测试计划**

| 组件 | 配置 |
|---|---|
| Thread Group | 线程数 2000，Ramp-up 1s，循环 1 次 |
| HTTP Request（注册） | POST `/auth/register`，Body: `{"username":"user${__threadNum}","password":"123456"}` |
| HTTP Request（登录） | POST `/auth/login` |
| JSON Extractor | 提取 `$.data.token` → 变量 `token` |
| HTTP Request（预热） | POST `/seckill/warmup?activityId=1` |
| HTTP Request（秒杀） | POST `/seckill/do`，Header `Authorization: Bearer ${token}` |
| 聚合报告 | 查看 QPS / P99 / 错误率 |

**Step 3：必须用命令行模式（GUI 模式会严重失真）**

```bash
jmeter -n -t seckill.jmx -l result.jtl -e -o ./report
```

> ⚠️ **大坑**：GUI 模式下 JMeter 会渲染每一条结果、CPU 吃满，测不出真实性能。命令行模式是必须的，不是可选项。

**Step 4：加速模式配置（`user.properties`）**

```properties
httpclient4.time_to_live=2000
```

### 5.3 压测必踩的坑

| 坑 | 后果 | 正确做法 |
|---|---|---|
| 压测机与被测机同一台 | JMeter 和 Tomcat 抢 CPU，数据不可信 | 至少分离，或用云压测 |
| 用 GUI 模式 | 性能数据严重偏低 | 必须 `-n` 命令行模式 |
| 用同一个用户压 | 全被"一人一单"挡掉，看不到库存效果 | 多用户压测 |
| 不预热 Redis | 首轮请求全打到 DB | 压测前调 `/seckill/warmup` |
| 不清理数据 | 上一轮订单占满"一人一单"名额 | 每轮压测前重置 |
| 只看 TPS 不看业务错误码 | 把"库存不足"当成失败，误判系统异常 | 区分 HTTP 错误 / 业务码 / 系统异常 |

### 5.4 压测数据记录模板

| 场景 | 并发 | QPS | 平均RT | P95 | P99 | 错误率 | 超卖 |
|---|---|---|---|---|---|---|---|
| 单用户幂等 | 200 | | | | | | |
| 多用户抢购 | 200 | | | | | | |
| +Sentinel 限流 | 200 | | | | | | |

**超卖验证 SQL**：

```sql
-- 1. 库存不能为负
SELECT id, seckill_stock FROM t_seckill_activity WHERE seckill_stock < 0;

-- 2. 订单数不能超过初始库存
SELECT a.id, a.initial_stock,
       (SELECT COUNT(*) FROM t_seckill_order o
         WHERE o.activity_id = a.id AND o.status IN (0,1)) AS cnt
FROM t_seckill_activity a
HAVING cnt > a.initial_stock;
```

---

## 六、项目结构

```
seckill-system/
├── src/main/java/com/xgh/seckill/
│   ├── SeckillApplication.java          启动类
│   ├── annotation/DistributedLock.java  @DistributedLock 注解
│   ├── aspect/DistributedLockAspect     SpEL 解析 + 加解锁
│   ├── common/
│   │   ├── constant/SeckillConstants    常量与 Redis Key 定义
│   │   ├── exception/                   业务异常 + 全局异常处理
│   │   ├── result/                      统一响应
│   │   └── util/                        雪花算法、JWT
│   ├── config/
│   │   ├── RedisConfig                  RedisTemplate + Lua 脚本
│   │   ├── RedissonConfig               Redisson 客户端
│   │   └── MybatisPlusConfig            分页 + 乐观锁插件
│   ├── controller/                      REST 接口
│   ├── dto / vo                         入参 / 出参
│   ├── entity / mapper                  实体与 Mapper
│   ├── job/OrderTimeoutJob              超时兜底 + 对账
│   ├── mq/
│   │   ├── MessageProducer              生产者抽象
│   │   ├── RocketMqProducer             RocketMQ 实现
│   │   ├── InMemoryMessageProducer      内存队列实现（降级）
│   │   ├── MessageConsumer              消费者统一入口
│   │   └── RocketMqConsumer             RocketMQ 消费者
│   └── service/impl/SeckillServiceImpl  ★ 核心业务逻辑
├── src/main/resources/
│   ├── lua/                             ★ 5 个 Lua 脚本
│   │   ├── seckill_stock.lua            原子扣库存
│   │   ├── rollback_stock.lua           回滚库存（防超回滚）
│   │   ├── user_order_limit.lua         一人一单原子占位
│   │   ├── get_and_delete.lua           GETDEL 兼容（Redis < 6.2）
│   │   └── unlock.lua                   安全释放锁
│   ├── db/schema.sql                    建表 + 初始化数据 + 对账 SQL
│   ├── db/h2-schema.sql                 H2 demo 建表
│   ├── application.yml
│   └── application-demo.yml             H2 + 内存队列配置
├── pressure-test/                       ★ 自研压测程序
├── docs/                                技术文档（4 篇，见下）
└── verify.sh                            一键验证脚本
```

---

## 七、文档导航

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| `docs/02-JMeter压测指南.md` | JMeter 压测全套操作 | 需要压测时 |
| `docs/04-快速启动指南.md` | 排障式启动手册 | 启动报错时 |
| `docs/06-实战踩坑记录.md` | 真实踩坑记录（含完整排查过程） | **最有价值的文档** |
| `docs/07-压测报告-实测数据.md` | 实测压测报告原文 | 需要复核压测数据时 |

---

## 八、已知限制与生产化改造方向

| 限制 | 现状 | 生产方案 |
|---|---|---|
| 单机部署 | 单节点 | Nacos 注册中心 + Gateway 网关 + 多实例 |
| 内存队列降级 | 重启丢消息 | 用真实 RocketMQ（`seckill.mq.rocketmq-enabled=true`） |
| 未分库分表 | 单表 | ShardingSphere 按 userId 分表 |
| 定时任务无分布式锁 | 多节点会重复执行 | ShedLock / Redisson 锁 |
| 密码未加密 | Demo 简化 | BCrypt |
| 无 Sentinel 规则持久化 | 规则在代码/内存 | 接入 Nacos 持久化 |
| 压测未做**前期无优化基线** | 只有优化后数据 | 补齐改造前的基线数据，才能量化优化收益 |

---

## 九、关键正确性检查清单

上线或代码评审前应逐项确认：

- [ ] 画出完整链路图（从请求进来到订单落库）
- [ ] 说清超卖的成因，以及为什么"先查后改"会超卖
- [ ] 说清 Redis Lua 为什么能解决超卖（而不是简单地"用了 Lua"）
- [ ] 说清为什么还要数据库兜底（Redis 不可靠的三个理由）
- [ ] 说清"少卖"怎么产生、怎么回滚
- [ ] 说清一人一单的三层保证，以及分表后为什么唯一索引失效
- [ ] 说清为什么用 MQ、不用会怎样、用了有什么代价
- [ ] 说清消息不丢/不重/有序的完整方案
- [ ] 说清缓存穿透/击穿/雪崩的区别与各自方案
- [ ] 说清 Redisson 看门狗原理，以及设了 leaseTime 后为什么失效
- [ ] 说清锁误删问题及 Lua 解决方案
- [ ] 说清红锁（RedLock）的争议
- [ ] 说清雪花算法 64 位组成与时钟回拨处理
- [ ] 说清你的压测怎么做的、瓶颈在哪、怎么定位、优化了多少
- [ ] 记录已知问题与踩坑过程（见 `docs/06`）