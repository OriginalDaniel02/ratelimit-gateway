package io.ratelimit.limiter;

public final class Limiters {

    private Limiters() {
    }

    public static RateLimiter create(RedisScriptRunner redis, LimiterSpec spec) {
        return switch (spec.algorithm()) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(redis, spec);
            case FIXED_WINDOW -> new FixedWindowLimiter(redis, spec);
            case SLIDING_LOG -> new SlidingLogLimiter(redis, spec);
            case SLIDING_COUNTER -> new SlidingCounterLimiter(redis, spec);
        };
    }
}
