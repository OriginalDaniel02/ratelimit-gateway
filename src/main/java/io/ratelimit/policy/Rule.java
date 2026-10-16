package io.ratelimit.policy;

import io.ratelimit.limiter.LimiterSpec;

public record Rule(String name, Scope scope, String match, LimiterSpec limiter, FailMode onRedisFailure) {
}
