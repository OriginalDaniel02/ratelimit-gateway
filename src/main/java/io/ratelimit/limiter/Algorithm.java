package io.ratelimit.limiter;

public enum Algorithm {
    TOKEN_BUCKET,
    FIXED_WINDOW,
    SLIDING_LOG,
    SLIDING_COUNTER
}
