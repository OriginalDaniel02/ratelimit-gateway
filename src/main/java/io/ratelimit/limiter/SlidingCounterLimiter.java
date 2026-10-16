package io.ratelimit.limiter;

public final class SlidingCounterLimiter implements RateLimiter {

    private final RedisScriptRunner redis;
    private final LimiterSpec spec;
    private final String limit;
    private final String windowMs;

    public SlidingCounterLimiter(RedisScriptRunner redis, LimiterSpec spec) {
        this.redis = redis;
        this.spec = spec;
        this.limit = Long.toString(spec.limit());
        this.windowMs = Long.toString(spec.window().toMillis());
    }

    @Override
    public Decision tryAcquire(String key) {
        return RedisScriptRunner.toDecision(
                redis.run(LuaScript.SLIDING_COUNTER, key, limit, windowMs, "1"), spec.limit());
    }
}
