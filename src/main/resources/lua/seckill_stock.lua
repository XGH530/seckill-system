-- =====================================================================
-- 秒杀库存原子扣减脚本
-- KEYS[1] = 库存 key，如 seckill:stock:1
-- ARGV[1] = 扣减数量（通常为 1）
--
-- 返回值：
--   >= 0 → 扣减成功，返回剩余库存
--   -1   → 库存不足
--   -2   → key 不存在（活动未预热或已结束）
--
-- =====================================================================
-- 【踩坑记录】参数可能被 JSON 序列化器包上引号，必须 sanitize
-- 详见 user_order_limit.lua 顶部说明。核心问题：
--   redisTemplate.execute(script, keys, args) 的 args 会经过 valueSerializer
--   （本项目为 Jackson JSON），把字符串 "1" 变成带引号的 "\"1\""，
--   导致 Lua 侧 tonumber 得到 nil、redis.call 收到非法参数类型。
-- =====================================================================

-- 剥离 JSON 引号并转字符串
local function sanitize(v)
    if v == nil then
        return nil
    end
    local s = tostring(v)
    if string.sub(s, 1, 1) == '"' and string.sub(s, -1) == '"' then
        s = string.sub(s, 2, -2)
    end
    return s
end

local stockKey = KEYS[1]

-- 扣减数量，容错默认 1，并确保是整数字符串
local num = tonumber(sanitize(ARGV[1]))
if num == nil or num <= 0 then
    num = 1
end
local numArg = tostring(math.floor(num))

-- 判断 key 是否存在
if redis.call('EXISTS', stockKey) == 0 then
    return -2
end

local stock = tonumber(sanitize(redis.call('GET', stockKey)))

-- 库存不足直接返回，不执行扣减
if stock == nil or stock < num then
    return -1
end

-- 扣减并返回剩余库存
-- 注意：DECRBY 在库存恰好等于 num 时会得到 0，不会出现负数
return redis.call('DECRBY', stockKey, numArg)
