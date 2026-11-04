-- 抢奖回滚脚本（补偿用）
-- 当 MQ 消费失败、数据库落库异常时调用，把预扣的名额与去重标记还回去
--
-- KEYS[1] = 本轮剩余名额        lucky:round:stock:{roundId}
-- KEYS[2] = 本轮已中奖集合      lucky:round:users:{roundId}
-- KEYS[3] = 本活动已中奖集合    lucky:activity:winners:{activityId}
-- ARGV[1] = participantId
--
-- 返回：1 回滚完成

local stockKey = KEYS[1]
local roundSetKey = KEYS[2]
local activitySetKey = KEYS[3]
local participantId = ARGV[1]

-- 只有确实记录过该用户才回滚，避免重复回滚把库存越加越多
if redis.call('SREM', roundSetKey, participantId) == 1 then
    redis.call('INCR', stockKey)
end
redis.call('SREM', activitySetKey, participantId)

return 1
