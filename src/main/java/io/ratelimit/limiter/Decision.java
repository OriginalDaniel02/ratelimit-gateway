package io.ratelimit.limiter;

/**
 * Result of one limiter check.
 *
 * @param allowed      whether the request may proceed
 * @param limit        the configured limit, for the RateLimit-Limit header
 * @param remaining    requests (or tokens) left right now
 * @param retryAfterMs how long a denied caller should wait; 0 when allowed
 * @param resetMs      time until the quota is fully restored or the window rolls over
 */
public record Decision(boolean allowed, long limit, long remaining, long retryAfterMs, long resetMs) {
}
