-- 固定窗口计数器（原子版）
--
-- 用途：登录频次限制这类「先自增、再设过期」的场景。
-- 放在 Lua 里做是因为 Java 侧分两步调用不是原子的：
--   如果 INCR 成功、EXPIRE 之前连接断了，这个 key 就会永久存在，
--   对应的学号会被永久限流，只能人工去 Redis 删 key 才能恢复。
--
-- KEYS[1] = 计数器 key
-- ARGV[1] = 窗口秒数
-- 返回：本次自增后的计数

local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
