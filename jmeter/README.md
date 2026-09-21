# JMeter 压测产物说明

> 本目录存放 Apache JMeter 5.6.3 的压测计划与**原始结果数据**。
> 这些 `.jtl` 是「简历中压测数字」的证据链——每个数字都能从这里复算出来。

## 文件清单

| 文件 | 说明 |
|---|---|
| `seckill-pressure-test.jmx` | 测试计划：setUp 线程组批量注册用户 → 主线程组并发秒杀（含业务断言） |
| `final_result.jtl` | **200 并发抢 100 库存** 的原始结果（534 样本，Err 0.00%） |
| `act3_result.jtl` | **300 并发抢 100 库存** 的原始结果（790 样本，Err 0.00%） |

> `.jtl` 是 CSV 格式，**Excel 可直接打开**；也可用下方命令生成可视化 HTML 报告。
> `*_users.csv`（压测账号 token）与 HTML 报告目录不入库——前者含凭据、后者含 250+ 第三方前端文件。

## 从原始数据复算指标（不依赖任何报告界面）

```powershell
$csv = Import-Csv "jmeter\final_result.jtl"
$csv | Group-Object label | ForEach-Object {
  $t = $_.Group | ForEach-Object { [double]$_.elapsed } | Sort-Object
  $n = $t.Count
  "$($_.Name)  n=$n  avg=$([math]::Round((($t | Measure-Object -Average).Average),1))ms  p95=$($t[[math]::Floor($n*0.95)])ms  p99=$($t[[math]::Min($n-1,[math]::Floor($n*0.99))])ms"
}
```

## 从 jtl 生成 HTML 报告（面试现场可演示）

```bash
jmeter -g jmeter/final_result.jtl -o jmeter/final_report
```

## 实测结果摘要（2026-09-20）

| 场景 | 样本 | 吞吐量 | 秒杀接口 P95 / P99 | Err | 对账 |
|---|---|---|---|---|---|
| 200 并发抢 100 | 534 | **381.7 /s** | 15 / **22** ms | 0.00% | 占位 100 + 剩余 0 = 100 ✅ |
| 300 并发抢 100 | 790 | **408.1 /s** | 18 / **30** ms | 0.00% | 占位 45 + 剩余 55 = 100 ✅ |

> ⚠️ **口径纪律**：这两个数字来自 JMeter；另一套 465.1 QPS 来自自研 Java 压测程序
> （`docs/07-压测报告-实测数据.md` 场景 2）。**两套工具的数字不能混用表述**。
> 详细压测流程见 `docs/02-JMeter压测指南.md`。
