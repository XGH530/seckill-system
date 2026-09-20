-- =====================================================================
-- 一人一单：原子校验 + 占位
-- KEYS[1] = 用户下单标记 key，如 seckill:order:user:1:1001
-- ARGV[1] = 订单占位值（用户 ID）
-- ARGV[2] = 过期时间（秒），通常为活动结束时间 - 当前时间
--
-- 返回值：
--   1  → 可以下单（此前未下单，本次占位成功）
--   0  → 重复下单（已存在标记）
--
-- =====================================================================
-- 【踩坑记录 · 这是本项目最隐蔽的一个坑，面试时的加分素材】
-- =====================================================================
-- 现象：直接 redis-cli --eval 执行本脚本完全正常，但通过
--       redisTemplate.execute(script, keys, args...) 调用时报：
--          "Lua redis() command arguments must be strings or integers"
--       加了 math.floor 之后，又变成：
--          "bad argument #1 to 'floor' (number expected, got nil)"
--
-- 根因：参数被 Jackson 序列化器「包了一层引号」。
--   项目里 RedisTemplate 的 valueSerializer 配置为 Jackson JSON，
--   调用 execute(script, keys, args) 时 args 也会走这个序列化器，
--   于是 Java 的字符串 86375 传到 Lua 里变成了带引号的 JSON 字面量：
--        ARGV[2] == "\"86375\""   （引号是内容的一部分，不是语法符号）
--   结果 tonumber(ARGV[2]) 返回 nil，math.floor(nil) 直接抛错；
--   即使不抛错，SET ... EX "\"86375\"" 也是非法的。
--
-- 解法：写一个 sanitize() 把参数外层可能存在的引号剥掉，
--       再做类型转换。这样无论调用方是否经过 JSON 序列化都能正常工作。
--
-- 【为什么这个坑值得记住】
--   Redis 客户端在「命令参数」和「Lua 脚本参数」上使用不同的序列化路径，
--   很多项目在接入 Lua 后才暴露这个问题，且报错信息指向脚本行号，
--   容易让人误以为是 Lua 逻辑写错了，实际根因在 Java 序列化配置。
-- =====================================================================

-- 剥离参数外层可能存在的 JSON 引号，并转成字符串
local function sanitize(v)
    if v == nil then
        return nil
    end
    local s = tostring(v)
    -- 去掉成对的双引号（Jackson 序列化字符串时会加上）
    if string.sub(s, 1, 1) == '"' and string.sub(s, -1) == '"' then
        s = string.sub(s, 2, -2)
    end
    return s
end

local key = KEYS[1]
local value = sanitize(ARGV[1]) or "1"

-- 解析过期时间，容错处理：解析失败则用默认值
local ttlRaw = sanitize(ARGV[2])
local ttl = tonumber(ttlRaw)
if ttl == nil or ttl <= 0 then
    ttl = 3600
end
-- 必须转成整数并转为字符串，redis.call 只接受字符串或整数
local expireSeconds = tostring(math.floor(ttl))

-- SET key value NX EX seconds 的原子写法
local ok = redis.call('SET', key, value, 'NX', 'EX', expireSeconds)

if ok then
    return 1
end
return 0
