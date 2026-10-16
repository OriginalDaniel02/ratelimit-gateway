package io.ratelimit.limiter;

import java.time.Duration;

/**
 * @param limit requests allowed per {@code window}
 * @param burst token bucket capacity; ignored by the other algorithms
 */
public record LimiterSpec(Algorithm algorithm, long limit, Duration window, long burst) {

    public LimiterSpec {
        if (algorithm == null) throw new IllegalArgumentException("algorithm is required");
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        if (window == null || window.toMillis() <= 0) throw new IllegalArgumentException("window must be >= 1ms");
        if (burst < 0) throw new IllegalArgumentException("burst must be >= 0");
    }

    public static LimiterSpec of(Algorithm algorithm, long limit, Duration window) {
        return new LimiterSpec(algorithm, limit, window, 0);
    }

    /** Token bucket capacity: the explicit burst, or the limit when none is set. */
    public long capacity() {
        return burst > 0 ? burst : limit;
    }

    /** Refill rate in tokens per second. */
    public double refillPerSecond() {
        return limit * 1000.0 / window.toMillis();
    }
}
