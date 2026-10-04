-- KEYS[1] = rate limit key
-- ARGV[1] = current timestamp (ms), or 0 to use Redis TIME
-- ARGV[2] = window size (ms)  
-- ARGV[3] = request limit
-- ARGV[4] = unique id for this request. Two requests in the same millisecond must
--           not share a sorted-set member, or the second overwrites the first and is
--           never counted. This used to come from math.random, which made correctness
--           depend on how the Redis version seeds its generator and still left a small
--           chance of collision.
local key = KEYS[1]
-- One clock for every gateway instance. Each instance used to pass its own time, so
-- on separate servers clock skew broke the limit: an instance running ahead trimmed
-- (sliding window) or refilled (token bucket) early. 0 means "use Redis's clock";
-- tests pass an explicit time to drive the clock without sleeping.
local now = tonumber(ARGV[1])
if now == nil or now <= 0 then
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local clearBefore = now - window
redis.call('ZREMRANGEBYSCORE', key, 0, clearBefore)
local count = redis.call('ZCARD', key)
if count < limit then
  redis.call('ZADD', key, now, tostring(now) .. '-' .. ARGV[4])
  redis.call('PEXPIRE', key, window)
  return {1, limit - count - 1, now}
else
  return {0, 0, now}
end
