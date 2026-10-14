#!/bin/sh
# 全量扫描 Redis key，找活动缓存
echo "=== 全部 key（前 60 个）==="
redis-cli --scan 2>/dev/null | head -60
echo ""
echo "=== key 总数 ==="
redis-cli dbsize 2>/dev/null
echo ""
echo "=== 精确查 seckill:activity:2 ==="
redis-cli ttl "seckill:activity:2" 2>/dev/null
echo "--- 内容 ---"
redis-cli get "seckill:activity:2" 2>/dev/null | head -c 500
echo ""
echo "=== 带花括号试 ==="
redis-cli ttl "{seckill:activity:2}" 2>/dev/null
redis-cli get "{seckill:activity:2}" 2>/dev/null | head -c 500
echo ""
echo "=== 库存 key ==="
redis-cli get "seckill:stock:2" 2>/dev/null
redis-cli ttl "seckill:stock:2" 2>/dev/null
echo ""
echo "=== 服务器当前时间 ==="
date '+%Y-%m-%dT%H:%M:%S%z'
