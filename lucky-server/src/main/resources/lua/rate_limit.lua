-- Redis+Lua 分布式限流脚本（固定窗口计数器）
-- 原子化执行：计数 + 过期设置，杜绝并发超发
-- KEYS[1] = 限流 key（如 rate_limit:danmaku:192.168.1.1）
-- ARGV[1] = 窗口内最大请求次数
-- ARGV[2] = 时间窗口（秒）
-- 返回：1 = 允许通过，0 = 被限流

local key = KEYS[1]
local maxCount = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])

-- 原子递增计数
local current = redis.call('INCR', key)

-- 首次访问时设置过期时间
if current == 1 then
    redis.call('EXPIRE', key, windowSeconds)
end

-- 判断是否超过限流阈值
if current > maxCount then
    return 0
end

return 1
