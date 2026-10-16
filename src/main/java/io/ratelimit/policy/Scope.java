package io.ratelimit.policy;

public enum Scope {
    /** Limit per API key. {@code match} is a specific key or {@code *} for every key separately. */
    API_KEY,
    /** Limit per client IP. {@code match} is a specific address or {@code *} for every address separately. */
    IP,
    /** Limit per route. {@code match} is a path pattern such as {@code /api/orders/**}; all callers share it. */
    ROUTE
}
