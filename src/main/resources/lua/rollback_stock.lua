-- =====================================================================
-- 库存回滚脚本（订单超时未支付时调用）
-- KEYS[1] = 库存 key
-- ARGV[1] = 回滚数量
-- ARGV[2] = 初始库存上限（防止超回滚导致库存虚高）
--
-- 返回值：
--   >= 0 → 回滚成功，返回回滚后库存
--   -1   → 回滚后会超过初始库存，拒绝回滚（说明该订单已回滚过）
--   -2   → key 不存在
--
-- 【踩坑记录】参数可能被 JSON 序列化器包上引号，必须 sanitize。
-- 详见 user_order_limit.lua 顶部说明：
--   redisTemplate.execute() 的 args 走 valueSerializer（Jackson JSON），
--   字符串 "1" 会变成带引号的 "\"1\"", 导致 tonumber 得到 nil。
-- =====================================================================

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

local num = tonumber(sanitize(ARGV[1]))
if num == nil or num <= 0 then
    num = 1
end

local maxStock = tonumber(sanitize(ARGV[2]))
if maxStock == nil then
    maxStock = 2147483647
end

local numArg = tostring(math.floor(num))

if redis.call('EXISTS', stockKey) == 0 then
    return -2
end

local stock = tonumber(sanitize(redis.call('GET', stockKey)))
if stock == nil then
    return -2
end

-- 防止重复回滚导致库存超过初始值
if stock + num > maxStock then
    return -1
end

return redis.call('INCRBY', stockKey, numArg)
