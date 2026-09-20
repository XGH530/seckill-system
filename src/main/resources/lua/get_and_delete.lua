-- =====================================================================
-- 原子"取值 + 删除"（GETDEL 的兼容实现）
-- KEYS[1] = 目标 key
--
-- 返回值：key 的原值（不存在则返回 false/nil）
--
-- 【注意】本脚本 VALUE 返回值同样可能被调用方的序列化器影响。
-- 由于 KEYS 走的是 keySerializer（StringRedisSerializer），不受影响；
-- 但若调用方对返回值做了 JSON 反序列化，拿到的可能带引号。
-- 见 user_order_limit.lua 顶部的序列化踩坑说明。
--
-- 【为什么需要这个脚本】
-- Redis 6.2 引入了 GETDEL 命令，把"取值 + 删除"变成一条原子命令。
-- 但在 6.2 之前（例如 Windows 移植版止步于 5.0.x），必须用 Lua 自己实现。
--
-- 【为什么"取值 + 删除"必须原子】
-- 幂等 Token 的校验逻辑是"取出 Token 并删除它"：
--   非原子写法：
--     value = GET key      ← 两个并发请求都可能读到这里
--     DEL key
--     两个请求都拿到了 value，都判定"Token 有效" → 幂等失效，重复下单
--   原子写法（本脚本）：
--     只有一个请求能读到 value，另一个读到 nil → 正确拦截重复提交
--
-- 【面试延伸】
-- Redis 持续把"常见的复合操作"下沉为原语（SETNX→SET NX、GET+DEL→GETDEL、
-- 各类型的 GETEX 等），目的就是让客户端不必依赖 Lua 也能拿到原子语义。
-- 这是 Redis 设计哲学的一个体现：**把原子性的保证放在服务端**。
-- =====================================================================

local value = redis.call('GET', KEYS[1])
if value then
    redis.call('DEL', KEYS[1])
end
return value
