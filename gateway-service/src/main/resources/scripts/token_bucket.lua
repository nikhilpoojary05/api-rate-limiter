-- KEYS[1] = bucket key
-- ARGV[1] = current timestamp (ms)
-- ARGV[2] = bucket capacity
-- ARGV[3] = refill rate (tokens per second)
-- ARGV[4] = window ms (for expiry)
local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_rate = tonumber(ARGV[3])
local window = tonumber(ARGV[4])
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now
local elapsed = (now - last_refill) / 1000.0
local new_tokens = math.min(capacity, tokens + elapsed * refill_rate)
if new_tokens >= 1 then
  redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now)
  redis.call('PEXPIRE', key, window)
  return {1, math.floor(new_tokens - 1)}
else
  redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
  redis.call('PEXPIRE', key, window)
  return {0, 0}
end
