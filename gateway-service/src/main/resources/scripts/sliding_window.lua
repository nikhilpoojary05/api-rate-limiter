-- KEYS[1] = rate limit key
-- ARGV[1] = current timestamp (ms)
-- ARGV[2] = window size (ms)  
-- ARGV[3] = request limit
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local clearBefore = now - window
redis.call('ZREMRANGEBYSCORE', key, 0, clearBefore)
local count = redis.call('ZCARD', key)
if count < limit then
  redis.call('ZADD', key, now, tostring(now) .. '-' .. tostring(math.random(1000000)))
  redis.call('PEXPIRE', key, window)
  return {1, limit - count - 1}
else
  return {0, 0}
end
