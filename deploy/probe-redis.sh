#!/bin/sh
# 排查用：打印 Redis 中所有 activity 相关 key 及其值
echo "=== KEYS ==="
redis-cli --scan --pattern "*activity*" 2>/dev/null | head -20

echo ""
echo "=== VALUES ==="
for k in $(redis-cli --scan --pattern "*activity*" 2>/dev/null | head -8); do
  echo "[$k]"
  redis-cli get "$k" 2>/dev/null | head -c 400
  echo ""
done

echo ""
echo "=== SERVER NOW ==="
date
