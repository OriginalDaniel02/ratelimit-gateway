package io.ratelimit.policy;

/** What a rule does when Redis cannot answer (down, timing out, or the circuit breaker is open). */
public enum FailMode {
    /** Let the request through unlimited. Favors availability. */
    FAIL_OPEN,
    /** Reject the request with 503. Favors protecting the upstream. */
    FAIL_CLOSED
}
