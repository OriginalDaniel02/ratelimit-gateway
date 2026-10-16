-- Sliding window counter: weights the previous fixed window by how much of it still overlaps.
-- estimate = previous * (1 - elapsedFraction) + current
-- KEYS[1] key prefix; window indexes are appended inside the script
-- ARGV[1] limit, ARGV[2] window ms, ARGV[3] cost
-- Returns {allowed, remaining, retry_after_ms, reset_ms}
local t = redis.call('TIME')
local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local idx = math.floor(now_ms / window)
local cur_key = KEYS[1] .. ':' .. idx
local prev_key = KEYS[1] .. ':' .. (idx - 1)

local cur = tonumber(redis.call('GET', cur_key) or '0')
local prev = tonumber(redis.call('GET', prev_key) or '0')

local elapsed = now_ms - idx * window
local weight = (window - elapsed) / window
local estimate = prev * weight + cur

if estimate + cost <= limit then
  redis.call('INCRBY', cur_key, cost)
  redis.call('PEXPIRE', cur_key, window * 2)
  return {1, math.floor(limit - estimate - cost), 0, window - elapsed}
end

-- Denied: find when the weighted previous window shrinks enough to fit the cost.
local retry_ms = window - elapsed
if prev > 0 then
  local needed_weight = (limit - cur - cost) / prev
  if needed_weight >= 0 then
    retry_ms = math.max(1, math.ceil(window * (1 - needed_weight)) - elapsed)
  end
end
return {0, 0, retry_ms, window - elapsed}
