#!/bin/sh
# 排查 601：抓取最近的秒杀相关日志
echo "=== 最近 50 行应用日志（含 seckill/ERROR/WARN） ==="
journalctl -u seckill -n 200 --no-pager 2>/dev/null | grep -iE "seckill|601|ended|活动|Exception|WARN|ERROR" | tail -40
echo ""
echo "=== 无过滤最后 30 行 ==="
journalctl -u seckill -n 30 --no-pager 2>/dev/null
