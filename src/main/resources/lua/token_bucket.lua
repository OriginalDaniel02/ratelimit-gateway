-- Token bucket. Refill, check and decrement happen in one atomic script.
-- KEYS[1] bucket key
-- ARGV[1] capacity (max tokens = burst), ARGV[2] refill rate (tokens/second), ARGV[3] cost
-- Returns {allowed, remaining, retry_after_ms, reset_ms}
-- Time comes from Redis (TIME), never from the client, so client clock skew cannot matter.
local t = redis.call('TIME')
local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now_ms
end

local elapsed = math.max(0, now_ms - ts)
tokens = math.min(capacity, tokens + elapsed * rate / 1000)

local allowed = 0
local retry_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_ms = math.ceil((cost - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', now_ms)
-- A full bucket is the default state, so the key can expire once it would have refilled.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)

local reset_ms = math.ceil((capacity - tokens) * 1000 / rate)
return {allowed, math.floor(tokens), retry_ms, reset_ms}
