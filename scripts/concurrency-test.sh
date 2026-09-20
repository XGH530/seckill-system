#!/usr/bin/env bash
# 并发压测脚本：验证分布式锁 + 一人一单 + 异步下单 + 指标统计
# 用法: bash concurrency-test.sh [并发数] [活动ID] [用户名]
# 说明: 同一用户对同一活动只有一次参与机会(订单取消也不返还资格),
#       想复现"1成功+N拒绝"请传一个没用过的新用户名
# 依赖: curl (Git Bash 自带)

BASE="http://localhost:8080"
CONCURRENCY="${1:-8}"
ACTIVITY_ID="${2:-1}"
USERNAME="${3:-zhangsan}"
WORK="/tmp/sk_test"
PASS="123456"

rm -rf "$WORK"
mkdir -p "$WORK"

echo "=============================================="
echo " 秒杀系统并发验证  并发=$CONCURRENCY  活动=$ACTIVITY_ID"
echo "=============================================="

# ---------- 1. 登录取 JWT ----------
# 注意: LoginRequest 字段是 username,不是 phone
curl -s -m 10 -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASS\"}" > "$WORK/login.json"

TOKEN=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$WORK/login.json" | head -1)

if [ -z "$TOKEN" ]; then
  echo "[登录失败] 响应:"
  cat "$WORK/login.json"
  echo
  echo "尝试注册该账号(幂等)..."
  curl -s -m 10 -X POST "$BASE/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASS\"}" > "$WORK/reg.json"
  cat "$WORK/reg.json"; echo
  TOKEN=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$WORK/reg.json" | head -1)
fi

if [ -z "$TOKEN" ]; then
  echo "!! 无法获取登录 token,终止"
  exit 1
fi
echo "[1/4] 登录成功 user=$USERNAME, JWT 长度 = ${#TOKEN}"

# ---------- 2. 预热(可选) + 重置指标 ----------
curl -s -m 10 -X POST "$BASE/admin/warmup/all" > /dev/null
curl -s -m 10 -X POST "$BASE/admin/metrics/reset" > /dev/null
echo "[2/4] 预热 + 指标已重置"

# ---------- 3. 并发抢购 (每请求独立幂等Token) ----------
echo "[3/4] 发起 $CONCURRENCY 个并发请求(同一用户,同一活动)..."
echo ""

for i in $(seq 1 "$CONCURRENCY"); do
  (
    # 每个请求独立取一个幂等 token
    curl -s -m 10 "$BASE/seckill/token?activityId=$ACTIVITY_ID" \
      -H "Authorization: Bearer $TOKEN" > "$WORK/tok_$i.json"

    TK=$(sed -n 's/.*"data":"\([^"]*\)".*/\1/p' "$WORK/tok_$i.json" | head -1)

    if [ -z "$TK" ]; then
      echo "TOKEN_EMPTY|code=0|msg=取token失败" >> "$WORK/result_$i.txt"
      exit 0
    fi

    curl -s -m 15 -X POST "$BASE/seckill/do" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d "{\"activityId\":$ACTIVITY_ID,\"idempotentToken\":\"$TK\"}" > "$WORK/resp_$i.json"

    CODE=$(sed -n 's/.*"code":\([0-9]*\).*/\1/p' "$WORK/resp_$i.json" | head -1)
    MSG=$(sed -n 's/.*"message":"\([^"]*\)".*/\1/p' "$WORK/resp_$i.json" | head -1)
    echo "${CODE:-0}|${MSG:-unknown}" >> "$WORK/result_$i.txt"
  ) &
done
wait

echo "--- 业务码分布 ---"
cat "$WORK"/result_*.txt 2>/dev/null | sort | uniq -c | sort -rn

# ---------- 4. 指标 + 对账 ----------
echo ""
echo "[4/4] 服务端指标:"
curl -s -m 10 "$BASE/admin/metrics" | sed 's/,"/,\n  "/g' | head -40

echo ""
echo "--- 数据库对账 ---"
curl -s -m 10 "$BASE/admin/stats"

echo ""
echo "--- 当前用户订单数 ---"
curl -s -m 10 "$BASE/seckill/orders" -H "Authorization: Bearer $TOKEN" | head -c 400
echo ""
