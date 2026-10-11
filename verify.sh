#!/bin/bash
export PATH="/usr/bin:/bin:$PATH"
BASE="http://127.0.0.1:8080"
CLI="${REDIS_CLI:-redis-cli}"
OUT=/tmp/seckill_test_result.txt

{
echo "########## 秒杀系统端到端验证 ##########"
echo "时间: $(date)"
echo ""

echo "===== [1] 登录 ====="
LOGIN=$(curl -s --max-time 10 -X POST "$BASE/auth/login" -H "Content-Type: application/json" -d '{"username":"zhangsan","password":"123456"}')
echo "响应: $(echo "$LOGIN" | head -c 200)"
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "Token长度: ${#TOKEN}"
echo ""

echo "===== [2] 秒杀前 Redis 库存 ====="
echo "seckill:stock:1 = $("$CLI" get "seckill:stock:1")"
echo ""

echo "===== [3] 获取幂等Token ====="
IDEM=$(curl -s --max-time 10 -X GET "$BASE/seckill/token?activityId=1" -H "Authorization: Bearer $TOKEN")
IDEMTOKEN=$(echo "$IDEM" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')
echo "响应: $(echo "$IDEM" | head -c 150)"
echo "Token长度: ${#IDEMTOKEN}"
echo ""

echo "===== [4] 执行秒杀 ====="
BODY="{\"activityId\":1,\"idempotentToken\":\"$IDEMTOKEN\"}"
DO_RESULT=$(curl -s --max-time 10 -X POST "$BASE/seckill/do" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$BODY")
echo "响应: $DO_RESULT"
echo ""

echo "===== [5] 秒杀后 Redis 库存(期望 99) ====="
echo "seckill:stock:1 = $("$CLI" get "seckill:stock:1")"
echo "用户下单标记: $("$CLI" keys "seckill:order:user:*")"
echo ""

echo "===== [6] 轮询秒杀结果 (等待MQ消费) ====="
sleep 2
RESULT=$(curl -s --max-time 10 -X GET "$BASE/seckill/result?activityId=1" -H "Authorization: Bearer $TOKEN")
echo "响应: $RESULT"
ORDERNO=$(echo "$RESULT" | sed -n 's/.*"orderNo":"\([^"]*\)".*/\1/p')
echo "订单号: $ORDERNO"
echo ""

echo "===== [7] 数据库订单 (期望1条) ====="
echo "$RESULT" | head -c 300
echo ""

echo "===== [8] 重复秒杀测试(期望被拦截) ====="
IDEM2=$(curl -s --max-time 10 -X GET "$BASE/seckill/token?activityId=1" -H "Authorization: Bearer $TOKEN" | sed -n 's/.*"data":"\([^"]*\)".*/\1/p')
BODY2="{\"activityId\":1,\"idempotentToken\":\"$IDEM2\"}"
curl -s --max-time 10 -X POST "$BASE/seckill/do" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$BODY2"
echo ""
echo ""

echo "===== [9] 支付订单 ====="
if [ -n "$ORDERNO" ]; then
  echo "支付响应: $(curl -s --max-time 10 -X POST "$BASE/seckill/pay?orderNo=$ORDERNO" -H "Authorization: Bearer $TOKEN")"
  echo ""
  echo "===== [10] 重复支付测试(期望被拦截) ====="
  echo "重复支付响应: $(curl -s --max-time 10 -X POST "$BASE/seckill/pay?orderNo=$ORDERNO" -H "Authorization: Bearer $TOKEN")"
fi
echo ""
echo "########## 验证结束 ##########"
} > "$OUT" 2>&1

cat "$OUT"
