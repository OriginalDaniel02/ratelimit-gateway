-- Fixed window counter.
-- KEYS[1] key prefix; the window index is appended inside the script
-- ARGV[1] limit, ARGV[2] window ms, ARGV[3] cost
-- Returns {allowed, remaining, retry_after_ms, reset_ms}
local t = redis.call('TIME')
local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local idx = math.floor(now_ms / window)
local key = KEYS[1] .. ':' .. idx
local reset_ms = (idx + 1) * window - now_ms

local count = tonumber(redis.call('GET', key) or '0')
if count + cost <= limit then
  count = redis.call('INCRBY', key, cost)
  redis.call('PEXPIRE', key, window)
  return {1, limit - count, 0, reset_ms}
end
return {0, math.max(0, limit - count), reset_ms, reset_ms}
