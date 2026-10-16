package io.ratelimit.limiter;

public interface RateLimiter {

    /** Atomically checks and consumes one unit of quota for {@code key}. */
    Decision tryAcquire(String key);
}
