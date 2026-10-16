package io.ratelimit.limiter;

public final class TokenBucketLimiter implements RateLimiter {

    private final RedisScriptRunner redis;
    private final LimiterSpec spec;
    private final String capacity;
    private final String rate;

    public TokenBucketLimiter(RedisScriptRunner redis, LimiterSpec spec) {
        this.redis = redis;
        this.spec = spec;
        this.capacity = Long.toString(spec.capacity());
        this.rate = Double.toString(spec.refillPerSecond());
    }

    @Override
    public Decision tryAcquire(String key) {
        return RedisScriptRunner.toDecision(
                redis.run(LuaScript.TOKEN_BUCKET, key, capacity, rate, "1"), spec.capacity());
    }
}
