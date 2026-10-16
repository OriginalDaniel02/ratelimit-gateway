package io.ratelimit.gateway;

import io.ratelimit.limiter.Decision;

/** Outcome of checking one rule: a real limiter decision, or a degraded answer because Redis was unavailable. */
public record Verdict(Kind kind, Decision decision, String ruleName) {

    public enum Kind {
        ALLOW,
        DENY,
        /** Redis unavailable, rule says fail open: request proceeds unlimited. */
        FAIL_OPEN,
        /** Redis unavailable, rule says fail closed: request is rejected with 503. */
        FAIL_CLOSED
    }
}
