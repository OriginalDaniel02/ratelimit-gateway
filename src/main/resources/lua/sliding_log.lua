-- Sliding window log: one sorted-set member per allowed request.
-- KEYS[1] zset key
-- ARGV[1] limit, ARGV[2] window ms, ARGV[3] unique member id
-- Returns {allowed, remaining, retry_after_ms, reset_ms}
local t = redis.call('TIME')
local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_ms - window)
local count = redis.call('ZCARD', KEYS[1])

if count < limit then
  redis.call('ZADD', KEYS[1], now_ms, ARGV[3])
  redis.call('PEXPIRE', KEYS[1], window)
  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  return {1, limit - count - 1, 0, tonumber(oldest[2]) + window - now_ms}
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_ms = math.max(1, tonumber(oldest[2]) + window - now_ms)
return {0, 0, retry_ms, retry_ms}
