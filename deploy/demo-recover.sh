#!/bin/sh
# 演示环境一键恢复：清掉活动缓存 + 预热，让秒杀立即可用
# 用途：面试/演示前执行，保证"打开就能抢"
set -e

echo "=== 1. 清理 Redis 中的活动缓存（防止读到过期的旧对象）==="
for i in 1 2 3 4; do
  redis-cli del "seckill:activity:$i" >/dev/null 2>&1 || true
  redis-cli del "seckill:null:activity:$i" >/dev/null 2>&1 || true
done
echo "已清理活动缓存"

echo ""
echo "=== 2. 等待应用就绪 ==="
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1:8081/actuator/health 2>/dev/null || echo "000")
  if [ "$code" = "200" ]; then
    echo "应用已就绪"
    break
  fi
  sleep 2
done

echo ""
echo "=== 3. 批量预热 ==="
curl -s -X POST http://127.0.0.1:8081/admin/warmup/all | head -c 200
echo ""

echo ""
echo "=== 4. 当前活动窗口 ==="
curl -s http://127.0.0.1:8081/goods/activity/list | head -c 400
echo ""

echo ""
echo "=== 5. 对账状态 ==="
curl -s http://127.0.0.1:8081/admin/stats | head -c 300
echo ""
