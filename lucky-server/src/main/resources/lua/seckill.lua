-- 抢奖原子脚本
-- 一次 Redis 调用内完成：频率限制 → 库存判断 → 本轮去重 → 跨轮次去重 → 扣减 → 记录
--
-- KEYS[1] = 本轮剩余名额        lucky:round:stock:{roundId}
-- KEYS[2] = 本轮已中奖集合      lucky:round:users:{roundId}
-- KEYS[3] = 本活动已中奖集合    lucky:activity:winners:{activityId}
-- KEYS[4] = 该用户点击频率计数   lucky:join:rate:{participantId}
-- ARGV[1] = participantId
-- ARGV[2] = 每秒最大点击次数
--
-- 返回值：
--   1  抢奖成功
--   0  本轮名额已抢完
--  -1  本轮已参与过（重复点击）
--  -2  本活动已中过奖（跨轮次限一次）
--  -3  本轮未预热（库存 key 不存在，通常表示轮次未开始）
--  -6  点击过于频繁（疑似脚本连点）

local stockKey = KEYS[1]
local roundSetKey = KEYS[2]
local activitySetKey = KEYS[3]
local rateKey = KEYS[4]
local participantId = ARGV[1]
local maxPerSecond = tonumber(ARGV[2]) or 5

-- 0. 频率限制
--    放在最前面：脚本连点时，请求在扣减之前就被挡掉，不浪费库存判断和后续的 MQ 投递。
--    阈值按「正常手点根本到不了」来定，1 秒 5 次已经非常宽容。
local clicks = redis.call('INCR', rateKey)
if clicks == 1 then
    redis.call('PEXPIRE', rateKey, 1000)
end
if clicks > maxPerSecond then
    return -6
end

local stock = redis.call('GET', stockKey)
if not stock then
    return -3
end

if tonumber(stock) <= 0 then
    return 0
end

if redis.call('SISMEMBER', roundSetKey, participantId) == 1 then
    return -1
end

if redis.call('SISMEMBER', activitySetKey, participantId) == 1 then
    return -2
end

redis.call('DECR', stockKey)
redis.call('SADD', roundSetKey, participantId)
redis.call('SADD', activitySetKey, participantId)

return 1
