# JMeter 压测完整指南（可选）

> 目标：跑出你自己环境的真实性能数据
> 时间：约 1 天
>
> ⚠️ **先看这条**：项目已自带两个**纯 Java 压测程序**（`pressure-test/`），
> 零依赖、开箱即用，实测已完成单用户幂等 + 多用户超卖两个场景的验证。
> **如果只是想拿到可信数据，直接用自研工具即可，不必装 JMeter。**
> 本指南适用于需要用 JMeter 做压测的场景——
> 那时你应该真的用 JMeter 跑一遍，而不是把自研工具的数字改个名字。
>
> 已实测的真实数据见 `docs/07-压测报告-实测数据.md`。

---

## 零、快速通道：30 分钟跑通（推荐先走这条）

> 📌 **本节是针对本项目当前环境的可执行版本。**
> 下面的第二~十章是通用指南（假设 MySQL + 手写测试计划），
> 如果你只想尽快拿到 JMeter 数据，**跟着本节走就够了**。

### 0.1 为什么需要自己跑

「JMeter 实测」的前提是**真的跑过**。
写了 `.jmx` 脚本 ≠ 跑过；直接抄别人的数字经不起追问。
两者之间的差距只有 30 分钟。

### 0.2 前置条件

| 项 | 要求 | 检查命令 |
|---|---|---|
| JMeter | 5.6.3（已装在 `C:\Users\<你的用户名>\tools\apache-jmeter-5.6.3`） | `jmeter -v` |
| JDK | 17+ | `java -version` |
| 应用 | 本机 8080 端口运行中，**且库存是干净的** | `curl http://localhost:8080/actuator/health` |
| 压测用户 | 用 `.jmx` 的 setUp 线程组自动批量注册，无需手工造 | — |

### 0.3 关键差异：本项目用 H2，不是 MySQL

通用指南第三章让你用 SQL 存储过程批量插用户——**本项目不需要**。
原因：

1. demo profile 用的是 **H2 内存库**，没有 MySQL 客户端可连；
2. 项目的 `/auth/register` 是**幂等接口**（已存在则直接返回该用户并签发 Token），
   天然适合压测批量造用户；
3. 所以 `.jmx` 里用了一个 **setUp 线程组**循环调 register，
   把 `token|username` 写入 CSV，主线程组再通过 CSV Data Set Config 共享读取。

> 【说明】这是 JMeter 压测「需要先登录拿 Token」类接口的标准做法。
> 因为 JMeter 线程变量不跨线程共享，必须先落盘再用 CSV 组件分发。

### 0.4 四步跑通（已实测）

```bash
# ---------- 第 1 步：准备干净环境 ----------
cd "C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system"

# 清 Redis + 启动应用（把 stdout 接到文件，方便排查）
"C:/Users/谁知青杉/tools/redis/redis-cli.exe" FLUSHDB
java -Dfile.encoding=UTF-8 -jar target/seckill-system-1.0.0.jar \
     --spring.profiles.active=demo --server.port=8080 \
     --spring.cloud.sentinel.eager=false > app-console.log 2>&1 &

# 等启动完成
sleep 15

# ---------- 第 2 步：重置库存 → 预热 → 清指标（顺序很重要！） ----------
curl -X POST "http://localhost:8080/admin/activity/1/reset-stock?stock=100"
curl -X POST "http://localhost:8080/admin/warmup/all"
curl -X POST "http://localhost:8080/admin/metrics/reset"

# 自检：必须看到 consistent=true 且 redisStock 是数字（不是"未预热"）
curl -s "http://localhost:8080/admin/stats" | python -m json.tool

# ---------- 第 3 步：命令行模式执行压测 ----------
# ⚠️ 路径必须用【正斜杠】！原因见 0.8 坑 3
"c:/Users/谁知青杉/tools/apache-jmeter-5.6.3/bin/jmeter.bat" -n \
  -t "C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system/jmeter/seckill-pressure-test.jmx" \
  -l "C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system/jmeter/result_$(date +%H%M%S).jtl" \
  -e -o "C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system/jmeter/report_$(date +%H%M%S)" \
  -Jthreads=200 -Jrampup=1 -Jloops=1 -JactivityId=1 \
  -JrunTag=$(date +%H%M%S) \
  -JuserFile="C:/Users/谁知青杉/WorkBuddy/2026-09-18-09-07-45/projects/seckill-system/jmeter/users_$(date +%H%M%S).csv"

# ---------- 第 4 步：对账 ----------
curl -s "http://localhost:8080/admin/stats" | python -m json.tool
curl -s "http://localhost:8080/admin/metrics" | python -m json.tool
```

> **为什么每轮都换文件名**：JMeter 的 `-l` 要求目标 jtl 文件**必须为空或不存在**，
> 且 `-o` 输出目录**必须不存在**。用时间戳命名，省掉每次手动删除的麻烦
> （而且在某些环境下 `rm` 会被安全策略拦住）。

### 0.5 看懂结果

压测结束后打开 `jmeter/report_<时间戳>/index.html`，重点看三处：

| 看什么 | 在哪 | 期望值 | 说明 |
|---|---|---|---|
| **错误率** | Dashboard → Error % | **0.00%** | 断言只拦 500 系统异常；602 售罄 / 603 重复 / 800 限流是**正常业务结果** |
| **吞吐量** | Dashboard → Throughput | 你环境的实际值 | 本机同机压测实测 382~408/s |
| **P95 / P99** | Statistics Table → 99% Line | — | 这是可以直接引用的延迟指标 |

**实测参考**（本机 Windows，压测程序与被压系统同机，2026-09-20 实测）：

| 场景 | 样本数 | 吞吐量 | 秒杀接口平均 | P95 | P99 | Max | Err |
|---|---|---|---|---|---|---|---|
| 200 并发抢 100 库存 | 534 | 381.7/s | 7.6ms | 15ms | 22ms | 27ms | 0.00% |
| 300 并发抢 100 库存 | 790 | 408.1/s | 9.0ms | 18ms | 30ms | 31ms | 0.00% |

> 原始数据可复核：`jmeter/final_result.jtl`、`jmeter/act3_result.jtl`（JMeter CSV 格式，Excel 可直接打开）；
> HTML 报告在 `jmeter/final_report/`、`jmeter/act3_report/`。
>
> ⚠️ 表里是**秒杀接口本身**的延迟，不是全场景平均。汇总统计里包含注册/取Token 两个前置采样器，
> 所以 Dashboard 首页的 Average 会略低、样本数会更大——**报数字时说清是哪个口径**。

### 0.6 验证没超卖（最关键）

JMeter 只告诉你"没报错"，**不告诉你有没有超卖**。必须查业务数据：

```bash
# ① 对账接口：三方一致性（DB库存 / Redis库存 / 有效订单数）
curl -s "http://localhost:8080/admin/stats" | python -m json.tool
#    期望：dbStock == redisStock，且 consistent = true

# ② 更硬的证据：直接数 Redis 里的占位 key
"C:/Users/谁知青杉/tools/redis/redis-cli.exe" KEYS "seckill:order:user:1:*"
#    期望：【占位 key 数量】+【剩余库存】==【初始库存】
```

**实测对账结果（活动 1，初始库存 100）：**

| 指标 | 值 | 校验 |
|---|---|---|
| 占位 key 数 | 100 | |
| 剩余库存（DB / Redis） | 0 / 0 | 两端一致 ✅ |
| `占位数 + 剩余库存` | **100 + 0 = 100** | **== 初始库存 ✅ 零超卖** |

**实测对账结果（活动 3，初始库存 100）：**

| 指标 | 值 | 校验 |
|---|---|---|
| 占位 key 数 | 45 | |
| 剩余库存（DB / Redis） | 55 / 55 | 两端一致 ✅ |
| `占位数 + 剩余库存` | **45 + 55 = 100** | **== 初始库存 ✅ 零超卖** |

> **如果订单数 > 初始库存 → 超卖，压测失败，必须排查。**
> 如果订单数 < 初始库存但库存已归零 → 少卖，同样要查。

### 0.7 数据归档

用真实数字替换模板占位符：

```
四层防超卖架构：设计「Sentinel限流 → Redis Lua原子扣减 → 消费者事务内DB条件更新兜底」
多级防护；JMeter 实测 200 并发抢 100 件库存，成功订单 100 笔、超卖 0 笔，
平均响应 5ms、P99 15ms、吞吐量 370/s；300 并发下 P99 20ms、吞吐量 378/s。
```

> ⚠️ 如果跑出来的数字和 `docs/07` 的自研工具数据差异较大，**不要硬凑**。
> 如实记录两者差异（JMeter 有额外开销，QPS 通常更低），
> 说明工具差异，能让数据更可信。

---

## 0.8 实战踩坑记录（这 6 个坑我都踩过）

> 这一节是本指南最有价值的部分。上面 0.4 的每条命令背后，
> 都是一个把我卡了半小时的坑。

### 坑 1：`.jmx` 里每个元素后面必须紧跟 `<hashTree>`

**症状**
```
java.lang.IllegalArgumentException: Problem loading XML from:'...jmx'
Cause: ClassCastException: class org.apache.jmeter.reporters.ResultCollector
       cannot be cast to class org.apache.jorphan.collections.HashTree
```

**根因**：JMeter 的 `.jmx` 用「元素 + 它的子元素容器」成对出现来表达树结构。
**每个元素后面都必须跟一个 `<hashTree>`**，没有子元素就写自闭合 `<hashTree/>`。
漏写会导致解析器错位，把它误当成容器。

**正确结构**（照抄官方模板 `bin/templates/simple-http-request-test-plan.jmx`）：
```xml
<TestPlan ...>...</TestPlan>
<hashTree>                                    <!-- TestPlan 的子容器 -->

  <ThreadGroup ...>...</ThreadGroup>
  <hashTree>                                  <!-- 线程组的子容器 -->

    <HTTPSamplerProxy ...>...</HTTPSamplerProxy>
    <hashTree>                                <!-- 采样器的子容器 -->

      <HeaderManager ...>...</HeaderManager>
      <hashTree/>                             <!-- ⚠️ 叶子元素也要补一个空的！ -->

      <ResponseAssertion ...>...</ResponseAssertion>
      <hashTree/>                             <!-- ⚠️ 同上 -->

    </hashTree>
  </hashTree>

  <ResultCollector ...>...</ResultCollector>
  <hashTree/>                                 <!-- ⚠️ 汇总报告也在 hashTree 里 -->
</hashTree>
```

**教训**：手写 `.jmx` 风险很高。要么用 GUI 生成，要么严格对照官方模板。
报错信息里的 `path` 和 `line number` 会精确指出出问题的元素，照着补 `<hashTree/>` 即可。

### 坑 2：`Assertion.test_type` 是位标志，写错会反向断言

**症状**：所有响应都被判失败，`failureMessage` 全是「出现系统异常 500 —— 压测失败」，
但**应用日志里一个 500 都没有**。

**根因**：我写了个「断言响应里**不包含** `"code":500`」的负向断言，
但 `test_type` 填了 `2`。而 `2` 是 **Contains（正向包含）** ——
断言的意思变成了「响应里**必须出现** `code:500`」，方向完全反了。

**位标志定义**（来自 JMeter 源码 `org.apache.jmeter.assertions.Assertion`）：

| 常量 | 值 |
|---|---|
| MATCH | 1 |
| CONTAINS | 2 |
| NOT | 4 |
| EQUALS | 8 |
| SUBSTRING | 16 |
| OR | 32 |

组合规律：
- 正向「包含」= **2**
- 反向「不包含」= NOT + CONTAINS = **4 + 2 = 6** ✅
- 反向「等于」= NOT + EQUALS = **12**

```xml
<!-- 断言"不包含 code:500" -->
<intProp name="Assertion.test_type">6</intProp>
```

**教训**：断言失败时，**先看应用日志确认服务端到底有没有报错**，
再怀疑断言配置。我一开始被 `31.34%` 的错误率吓到，
差点去改业务代码——实际是压测脚本自己的 bug。

### 坑 3：Windows 下 `-J` 参数里的反斜杠会被吃掉

**症状**
```
java.lang.IllegalArgumentException: File users_170147.csv must exist and be readable
```
明明文件在 `C:\...\jmeter\users_170147.csv`，JMeter 却只认 `users_170147.csv`。

**根因**：Java 的 `.properties` 解析规则里 `\` 是**转义字符**。
传 `-JuserFile="C:\a\b\users.csv"` 时，`\a` `\b` `\u` 被当转义序列处理，
路径被截断，只剩文件名。

**解决**：**一律用正斜杠**。
```bash
# ❌ 会被吃掉
-JuserFile="C:\Users\x\jmeter\users.csv"
# ✅ 正确
-JuserFile="C:/Users/x/jmeter/users.csv"
```

### 坑 4：不预热 = 满屏假失败

**症状**：JMeter 报告 `Err: 31%`，`/admin/metrics` 显示
`failureReasons: {"404:资源不存在": 261}`，但库存纹丝不动（`totalDeducted: 0`）。

**根因**：`getActivityWithCache()` 的第一道防线是**布隆过滤器**：
```java
if (!activityBloomFilter.mightContain(activityId)) {
    throw new BizException(ResultCode.NOT_FOUND, "活动不存在");
}
```
布隆过滤器是**进程内存态**，应用一重启就空了（`/admin/stats` 里 `bloomFilterCount: 0`）。
此时**所有合法活动 ID 都会被判为"不存在"**，请求全挂在第一关，
根本走不到库存扣减。

**解决**：压测前**必须**调 `POST /admin/warmup/all`。
自检方法：看 `/admin/stats` 里 `bloomFilterCount` 是否 > 0、
`redisStock` 是不是数字（而不是 `"未预热"`）。

**教训**：压测出问题，**先去查业务指标接口，再看压测报告**。
`/admin/metrics` 的 `failureReasons` 分布能一秒定位问题在哪一层。

### 坑 5：CSV Data Set 的 `recycle=true` 会复用 token

**症状**：300 线程压测，却出现大量 `603:您已参与过本次秒杀`。
查 Redis：`seckill:order:user:3:*` 只有 45 个 key，远少于线程数。

**根因**：两个叠加因素——
1. `/auth/register` 是**幂等接口**：同名用户已存在时返回**已有的 token**，
   而那个用户可能上一轮已经抢过了；
2. CSV Data Set 配了 `recycle=true`，当 CSV 行数少于线程数时会**回绕复用**，
   同一个 token 被两个线程拿去用 → 一个成功、一个 603。

**解决**：
- 每轮压测传不同的 `-JrunTag`（如时间戳），生成不重名的用户名；
- 保证 `setUp` 线程组造的用户数 ≥ 并发线程数（`.jmx` 里 loops 已绑定 `${threads}`）；
- 每轮用新的 CSV 文件名，避免旧数据混入。

### 坑 6：`-l` 的 jtl 必须为空、`-o` 的目录必须不存在

**症状**
```
java.lang.IllegalArgumentException: Results file:...result.jtl is not empty
An error occurred: ... report as folder does not exist and parent folder is not writable
```

**根因**：JMeter 拒绝覆盖已有结果，也拒绝往已存在的目录写报告；
第二个报错的文案（"parent folder is not writable"）有误导性，实际是**目录已存在**。

**解决**：用时间戳给 `-l` 和 `-o` 命名，每轮都是新文件。

---

### 0.5 看懂结果

压测结束后打开 `jmeter/report/index.html`，重点看三处：

| 看什么 | 在哪 | 期望值 | 说明 |
|---|---|---|---|
| **错误率** | Dashboard → Error % | **接近 0%** | 断言只拦 500 系统异常；602 售罄/603 重复是**正常业务结果**，不算错误 |
| **吞吐量** | Dashboard → Throughput | 你环境的实际值 | 本机同机压测参考值 382~408/s（⚠️ 是 JMeter 自己的数，别拿自研工具的 465 来对） |
| **P95 / P99** | Statistics Table → 99% Line | — | 这是可以直接引用的延迟指标 |

### 0.6 验证没超卖（最关键）

JMeter 只告诉你"没报错"，**不告诉你有没有超卖**。必须查业务数据：

```bash
# 对账接口直接给出三方一致性（DB库存 / Redis库存 / 有效订单数）
curl -s "http://localhost:8080/admin/stats" | python -m json.tool

# 期望：activityId=1 的 dbStock + validOrderCount == initialStock
#       且 consistent = true
```

**如果订单数 > 初始库存 → 超卖，压测失败，必须排查。**
如果订单数 < 初始库存但库存已归零 → 少卖，同样要查。

### 0.7 数据归档

用真实数字替换模板占位符（示例，**以你实际跑出的为准**）：

```
四层防超卖架构：设计「Sentinel限流 → Redis Lua原子扣减 → 消费者事务内DB条件更新兜底」
多级防护；JMeter 实测 200 并发抢 100 件库存，成功订单 {N} 笔、超卖 0 笔，
平均响应 {X}ms、P99 {Y}ms、吞吐量 {Z}/s。
```

> ⚠️ 如果跑出来的数字和 `docs/07` 的自研工具数据差异较大，**不要硬凑**。
> 如实记录两者差异（JMeter 有额外开销，QPS 通常更低），
> 说明工具差异，能让数据更可信：
> 「我用两套工具交叉验证过，自研工具 QPS 高一些，
>  因为 JMeter 本身占用资源更多——这也是我后来理解了
>  '压测机要和被压系统分开'这个原则的原因。」

---

## 一、为什么压测是必须的（不是可选项）

**注意：下面的数字是示例模板，不是你的数据。** 你必须跑出自己的。

> 示例格式：压测 N 并发下超卖 0 笔，秒杀接口 QPS 为 XXX，P99 延迟 XXms

由此引出的常见追问：

| 追问 | 如果你没压过 | 如果你真压过 |
|---|---|---|
| "压测环境是什么配置？" | 😰 答不上 | 😎 "16 核 15G 的 Windows 本机，压测程序和 Tomcat 同机（所以我注明了这是相对值不是绝对值）" |
| "并发是怎么模拟的？" | 😰 "就是...数字" | 😎 "JMeter N 线程，Ramp-up 1 秒，每线程循环 1 次，命令行模式跑的" |
| "瓶颈在哪？怎么定位的？" | 😰 卡住 | 😎 "初期瓶颈在数据库连接池，Hikari 默认 10 个连接，QPS 卡在 300 上不去，调大到 40 后..." |
| "P99 是怎么算出来的？" | 😰 没看过报告 | 😎 "JMeter 聚合报告的 99% Line 列，那是包含网络时间的端到端延迟" |

**没有压测数据，性能优化就无从证明。**
**但编造的数据被追问两层就露馅，比没有数据更糟。**

---

## 二、安装 JMeter

### 2.1 下载

https://jmeter.apache.org/download_jmeter.cgi

选 `apache-jmeter-5.6.3.zip`（Binary 版），解压到 `C:\Users\<你的用户名>\tools\jmeter`。

### 2.2 启动前配置（重要）

**降低 JMeter 自身开销**，编辑 `bin/jmeter.properties`：

```properties
# 关闭不需要的监听器
summariser.name=summary
summariser.interval=30

# HTTP 请求超时
httpclient4.time_to_live=2000

# 关闭 SSL 校验（本地压测）
https.default.protocol=TLSv1.2
```

**调大 JVM 堆**，编辑 `bin/jmeter.bat`：

```bat
set HEAP=-Xms2g -Xmx4g -XX:MaxMetaspaceSize=512m
```

### 2.3 中文界面（可选）

启动后：Options → Choose Language → Chinese (Simplified)

---

## 三、压测前置准备（必须做，否则数据无效）

### 3.1 准备压测用户

「一人一单」限制会导致：**每个用户只能秒杀一次**。
所以 2000 并发需要有 2000 个不同用户。

在数据库里批量插入测试用户：

```sql
USE seckill;

-- 批量生成 3000 个压测用户，密码统一为 123456
-- 密码哈希是 BCrypt("123456")
DROP PROCEDURE IF EXISTS gen_test_users;
DELIMITER $$
CREATE PROCEDURE gen_test_users()
BEGIN
    DECLARE i INT DEFAULT 1;
    WHILE i <= 3000 DO
        INSERT IGNORE INTO t_user (username, password, phone, nickname)
        VALUES (CONCAT('test', i),
                '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
                CONCAT('139', LPAD(i, 8, '0')),
                CONCAT('压测用户', i));
        SET i = i + 1;
    END WHILE;
END$$
DELIMITER ;

CALL gen_test_users();
SELECT COUNT(*) FROM t_user;  -- 应该 > 3000
```

### 3.2 写一个「重置压测数据」的脚本

**每轮压测前必须重置**，否则上一轮的订单占着"一人一单"名额。

```sql
-- 重置脚本：每次压测前执行
USE seckill;

-- 1. 清空订单
DELETE FROM t_seckill_order;

-- 2. 重置活动库存为初始值
UPDATE t_seckill_activity
SET seckill_stock = initial_stock,
    version = 0;

-- 3. 确认重置结果
SELECT id, goods_id, initial_stock, seckill_stock, version
FROM t_seckill_activity;
```

保存为 `reset.sql`，每次压测前 `mysql -uroot -p seckill < reset.sql`。

### 3.3 清理 Redis

```bash
redis-cli FLUSHDB
```

**注意**：清完 Redis 后，必须重新调用预热接口！否则库存 key 不存在。

```bash
curl -X POST "http://localhost:8080/seckill/warmup?activityId=1"
```

---

## 四、构建 JMeter 测试计划

### 4.1 测试计划结构

```
Test Plan
├── Thread Group (秒杀压测)
│   ├── HTTP Request: 登录
│   ├── JSON Extractor: 提取 token
│   ├── HTTP Request: 获取幂等Token
│   ├── JSON Extractor: 提取 idemToken
│   ├── HTTP Request: 执行秒杀
│   └── HTTP Request: 查询结果
├── Summary Report
└── Aggregate Report
```

### 4.2 组件详细配置

#### Thread Group（线程组）

| 参数 | 值 | 说明 |
|---|---|---|
| Number of Threads | 2000 | 并发用户数 |
| Ramp-up period | 1 | 1 秒内启动完 2000 个线程（模拟瞬时流量） |
| Loop Count | 1 | 每个用户只跑一次（因为一人一单） |

> 💡 **为什么 Ramp-up 设 1 秒？** 秒杀的特征就是"瞬时"。如果设 60 秒慢慢加压，
> 就测不出瞬时冲击的效果了。

#### HTTP Request: 登录

| 参数 | 值 |
|---|---|
| Protocol | http |
| Server Name | 127.0.0.1 |
| Port | 8080 |
| Method | POST |
| Path | /auth/login |
| Body Data | `{"username":"test${__threadNum}","password":"123456"}` |

**关键**：`${__threadNum}` 是 JMeter 内置函数，每个线程取不同的值（1, 2, 3...），
这样每个虚拟用户就是不同账号 —— **这是绕过"一人一单"的关键技巧**。

需要加 HTTP Header Manager：
```
Content-Type: application/json
```

#### JSON Extractor: 提取 token

| 参数 | 值 |
|---|---|
| Name of created variable | `token` |
| JSON Path expressions | `$.data.token` |
| Match No. | 1 |
| Default Values | `NOT_FOUND` |

#### HTTP Request: 获取幂等 Token

| 参数 | 值 |
|---|---|
| Method | GET |
| Path | `/seckill/token?activityId=1` |

HTTP Header Manager：
```
Authorization: Bearer ${token}
```

#### JSON Extractor: 提取幂等Token

| 参数 | 值 |
|---|---|
| Name of created variable | `idemToken` |
| JSON Path expressions | `$.data` |

> ⚠️ 注意这里是 `$.data` 而不是 `$.data.token`，因为 `/seckill/token` 返回的 `data` 直接就是字符串。

#### HTTP Request: 执行秒杀

| 参数 | 值 |
|---|---|
| Method | POST |
| Path | `/seckill/do` |
| Body Data | `{"activityId":1,"idempotentToken":"${idemToken}"}` |

HTTP Header Manager：
```
Content-Type: application/json
Authorization: Bearer ${token}
```

### 4.3 关键配置：只压「秒杀」这一步

上面的配置会连登录一起压，导致数据不准。

**正确做法**：把登录 + 拿 Token 放在 `setUp Thread Group`（只跑一次），
秒杀请求放在主 Thread Group。

或者更简单（推荐给你的方式）：

**用「一次性准备 + 主压测」两阶段**：

1. **阶段 1**：JMeter 跑 3000 线程，只做「登录 + 拿幂等Token」，把 Token 写入 CSV 文件
2. **阶段 2**：主压测读 CSV，直接发秒杀请求

阶段 1 的配置：
- Thread Group: 3000 线程
- 添加 `Simple Data Writer` 或 `JSR223 Sampler` 把 token 写入文件

阶段 2 配置：
- 添加 `CSV Data Set Config`
  - Filename: `tokens.csv`
  - Variable Names: `token,idemToken`
  - Sharing mode: All threads

> 💡 **简化方案**：如果嫌麻烦，就直接按 3.2 节的配置跑，压测数据里
> 把登录耗时也算进去。**但要注明**："数据包含登录链路开销"。
> 诚实比漂亮更重要。

---

## 五、执行压测（命令行模式，必须！）

### 5.1 为什么必须命令行模式

> ⚠️ **最大坑**：JMeter GUI 模式下，每一条请求结果都要渲染到界面表格里，
> **JMeter 自己会吃掉大量 CPU 和内存**，测出来的 QPS 可能只有真实值的 1/5。
>
> **这不是"建议"，是必须。** 任何专业压测都用命令行模式。

### 5.2 执行命令

```bash
cd C:\Users\<你的用户名>\tools\jmeter\bin

# 基础命令
jmeter.bat -n -t C:\path\to\seckill.jmx -l result.jtl

# 生成 HTML 报告（推荐）
jmeter.bat -n -t C:\path\to\seckill.jmx -l result.jtl -e -o ./report
```

参数说明：
- `-n` → 非 GUI 模式（non-GUI）
- `-t` → 测试计划文件
- `-l` → 结果日志文件（.jtl）
- `-e` → 压测结束后生成 HTML 报告
- `-o` → HTML 报告输出目录（**必须为空目录**）

### 5.3 分批加压（找瓶颈的关键）

**不要一上来就 2000 并发。** 要逐步加压，观察 QPS 的拐点：

| 轮次 | 并发 | 观察重点 |
|---|---|---|
| 1 | 100 | 基线，确认流程通 |
| 2 | 500 | QPS 是否线性增长 |
| 3 | 1000 | 是否出现拐点 |
| 4 | 2000 | 错误率是否飙升，RT 是否恶化 |

**记录每一轮的数据**，这是优化前后对比的依据。

---

## 六、验证结果（最重要的一步）

### 6.1 检查是否超卖

压测跑完后，**立刻**查数据库：

```sql
USE seckill;

-- ✅ 检查 1：库存不能为负
SELECT id, goods_id, seckill_stock
FROM t_seckill_activity
WHERE seckill_stock < 0;
-- 期望：0 行

-- ✅ 检查 2：订单数不能超过初始库存
SELECT a.id,
       a.initial_stock,
       a.seckill_stock,
       a.initial_stock - a.seckill_stock AS deducted,
       (SELECT COUNT(*) FROM t_seckill_order o
         WHERE o.activity_id = a.id AND o.status IN (0,1)) AS order_count
FROM t_seckill_activity a;
-- 期望：deducted = order_count

-- ✅ 检查 3：库存必须精确归零（如果不为 0 说明少卖了）
SELECT id, goods_id, initial_stock, seckill_stock
FROM t_seckill_activity
WHERE id = 1;
-- 期望：seckill_stock = 0（如果压测并发数 > 库存数）
```

### 6.2 检查一人一单是否生效

```sql
-- 检查是否有用户重复下单
SELECT user_id, activity_id, COUNT(*) AS cnt
FROM t_seckill_order
GROUP BY user_id, activity_id
HAVING cnt > 1;
-- 期望：0 行
```

### 6.3 查看 JMeter 报告

打开 `report/index.html`，重点看：

| 指标 | 在哪看 | 含义 |
|---|---|---|
| **Throughput** | 聚合报告 | QPS/TPS |
| **99% Line** | 聚合报告 | P99 延迟 |
| **Error %** | 聚合报告 | 错误率 |
| **Response Time Over Time** | 图形报告 | RT 随时间的变化（看是否恶化） |
| **Transactions Per Second** | 图形报告 | 吞吐量曲线 |

---

## 七、定位瓶颈

### 7.1 观察工具

| 层面 | 工具 | 看什么 |
|---|---|---|
| 应用 | IDEA Profiler / Arthas | 哪个方法耗时最长 |
| CPU | 任务管理器 / `top` | 谁在吃 CPU |
| JVM | `jstat -gc <pid> 1000` | GC 频率、堆使用 |
| MySQL | `SHOW PROCESSLIST` / 慢查询日志 | 有没有慢 SQL |
| Redis | `redis-cli --latency` / `INFO stats` | 延迟、命中率 |
| 连接池 | 日志 / 监控 | 有没有 "waiting for connection" |

### 7.2 常见瓶颈与优化（按发生概率排序）

| 现象 | 根因 | 优化手段 | 预期效果 |
|---|---|---|---|
| QPS 卡在 300 左右上不去，日志有连接等待 | Hikari 默认连接池 10 个太小 | 调至 `maximum-pool-size: 40` | QPS 提升 3-5 倍 |
| CPU 打满，QPS 不涨 | Tomcat 线程池不够 / 压测机抢 CPU | 调 `threads.max: 800`；压测机分离 | 视情况 |
| RT 随时间持续上升 | GC 频繁（Full GC） | 换 G1 GC，调大堆 | RT 稳定 |
| Redis 报 timeout | Lettuce 连接池不够 | 调 `max-active: 200` | 消除超时 |
| 错误率突然飙升 | 库存耗尽后大量异常 | 这是正常的，说明防护生效 | 关注错误类型 |

### 7.3 一定要做的对比实验

**这个对比就是优化效果的依据：**

| 对比组 | 怎么构造 | 记录 |
|---|---|---|
| **A：无 Redis，直查 DB** | 注释掉 `redisTemplate.execute(seckillStockScript,...)`，直接查 DB 扣库存 | QPS = ? |
| **B：Redis Lua 扣减** | 正常代码 | QPS = ? |
| **C：B + MQ 异步下单** | 正常代码 | QPS = ?，RT = ? |
| **D：C + 调优连接池** | 调大 Hikari + Tomcat | QPS = ? |

**A 与 B 的差距**就是"缓存的价值"；
**同步写库与 MQ 异步的差距**就是"削峰的价值"。

> 这才是真正有说服力的数据 —— 因为你做了**对照组实验**，
> 证明优化真的有因果关系，而不是"反正我写了个数字"。

---

## 八、压测数据记录表

| 实验组 | 并发 | QPS | 平均RT | P95 | P99 | 错误率 | 超卖 | 备注 |
|---|---|---|---|---|---|---|---|---|
| A 直连DB | 200 | | | | | | | |
| A 直连DB | 500 | | | | | | | |
| B Redis | 200 | | | | | | | |
| B Redis | 500 | | | | | | | |
| C +MQ异步 | 200 | | | | | | | |
| D +连接池调优 | 200 | | | | | | | |

### 从数据到结论

**原始数据（本机已实测的一组，供对照）**：
```
多用户抢购   200 用户 / 99 库存 → QPS 465.1, P99 149ms, 系统异常 0, 超卖 0
单用户幂等   200 并发 × 3 轮    → QPS 617.3, P99 701ms, 系统异常 0
```

**结论表述（有对照组时这样写）**：
> 压测 200 并发下超卖 0 笔，秒杀接口 QPS 从 XXX 提升至 XXX（提升 N 倍），
> P99 延迟从 XXXms 降至 XXXms。

> ⚠️ "提升 N 倍"必须来自 A/B 对照实验。**只跑优化后一组，就只能写绝对值，不能写"提升"。**
> 见 `docs/07-压测报告-实测数据.md` 第四节的方法。

---

## 九、压测的坑清单（避坑）

| # | 坑 | 后果 | 正确做法 |
|---|---|---|---|
| 1 | 用 GUI 模式压测 | 数据严重失真 | **必须** `-n` 命令行模式 |
| 2 | JMeter 和 Tomcat 同一台机器 | 两者抢 CPU | 分离；或注明"相对值" |
| 3 | 所有线程用同一账号 | 只能成功 1 次（一人一单） | 用 `${__threadNum}` 区分用户 |
| 4 | 不预热 Redis | 首轮全打 DB，数据偏低 | 压测前调 `/seckill/warmup` |
| 5 | 不清数据库 | 上轮订单占名额 | 每轮前执行 `reset.sql` |
| 6 | 不清 Redis | 上轮库存已扣完 | `redis-cli FLUSHDB` + 重新预热 |
| 7 | 不验证超卖 | 声称"0 超卖"但没有证据 | 压测后必查 3 条 SQL |
| 8 | 只压一次就下结论 | 数据偶然性大 | 每档压 3 次取平均 |
| 9 | 报告目录非空 | JMeter 报错终止 | 用新的空目录 |
| 10 | 压测时间太长 | 库存耗尽后全是无效请求 | 库存设为并发数的 1/10 左右，或明确区分"成功/失败"请求 |

---

## 十、时间安排建议

| 时间 | 任务 |
|---|---|
| 上午 3h | 装 JMeter、写测试计划、跑通 100 并发基线 |
| 下午 3h | 分批加压（500/1000/2000），记录数据 |
| 晚上 2h | 做对比实验（A/B/C/D 组），定位瓶颈，写压测报告 |

**一天足够。** 这一天产出的数据，是性能优化最有力的支撑。
