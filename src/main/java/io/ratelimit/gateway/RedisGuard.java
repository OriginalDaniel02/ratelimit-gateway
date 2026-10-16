package io.ratelimit.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.ratelimit.limiter.Decision;
import io.ratelimit.policy.CompiledRule;
import io.ratelimit.policy.FailMode;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs limiter calls behind one circuit breaker and turns Redis failures into the rule's documented fail mode.
 * A single breaker is right because every limiter shares the same Redis: when it is down, it is down for all rules.
 */
public final class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final CircuitBreaker breaker;
    private final AtomicLong degraded = new AtomicLong();

    public RedisGuard(GatewayProperties.Breaker settings) {
        this.breaker = CircuitBreaker.of("redis", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRatePercent())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        this.breaker.getEventPublisher().onStateTransition(e -> log.warn("Redis circuit breaker: {}", e.getStateTransition()));
    }

    public Verdict check(CompiledRule rule, String key) {
        String name = rule.rule().name();
        try {
            Decision d = breaker.executeSupplier(() -> rule.limiter().tryAcquire(key));
            return new Verdict(d.allowed() ? Verdict.Kind.ALLOW : Verdict.Kind.DENY, d, name);
        } catch (RuntimeException e) {
            degraded.incrementAndGet();
            if (!(e instanceof CallNotPermittedException)) {
                log.debug("Redis call failed for rule {}: {}", name, e.toString());
            }
            boolean open = rule.rule().onRedisFailure() == FailMode.FAIL_OPEN;
            return new Verdict(open ? Verdict.Kind.FAIL_OPEN : Verdict.Kind.FAIL_CLOSED, null, name);
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    public long degradedCount() {
        return degraded.get();
    }
}
