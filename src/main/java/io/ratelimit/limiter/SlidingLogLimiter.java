package io.ratelimit.limiter;

import java.util.UUID;

public final class SlidingLogLimiter implements RateLimiter {

    private final RedisScriptRunner redis;
    private final LimiterSpec spec;
    private final String limit;
    private final String windowMs;

    public SlidingLogLimiter(RedisScriptRunner redis, LimiterSpec spec) {
        this.redis = redis;
        this.spec = spec;
        this.limit = Long.toString(spec.limit());
        this.windowMs = Long.toString(spec.window().toMillis());
    }

    @Override
    public Decision tryAcquire(String key) {
        // The member must be unique, otherwise two requests in the same millisecond would collapse into one.
        return RedisScriptRunner.toDecision(
                redis.run(LuaScript.SLIDING_LOG, key, limit, windowMs, UUID.randomUUID().toString()), spec.limit());
    }
}
