package io.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ratelimit.gateway.GatewayProperties;
import io.ratelimit.gateway.RedisConnection;
import io.ratelimit.limiter.Algorithm;
import io.ratelimit.limiter.LimiterSpec;
import io.ratelimit.limiter.Limiters;
import io.ratelimit.limiter.RateLimiter;
import io.ratelimit.limiter.RedisScriptRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RL-1 / RL-2 acceptance: 100 parallel clients against a real Redis never get more than the limit.
 * The bounds are derived from the algorithm, not tuned to pass; see the comments on each assertion.
 */
@Testcontainers(disabledWithoutDocker = true)
class LimiterConcurrencyTest {

    private static final int CLIENTS = 100;
    private static final long LIMIT = 50;

    @Container
    static final GenericContainer<?> REDIS = TestSupport.newRedis();

    static RedisConnection connection;
    static RedisScriptRunner runner;

    @BeforeAll
    static void connect() {
        connection = new RedisConnection(TestSupport.redisUri(REDIS),
                new GatewayProperties.Redis(Duration.ofSeconds(2), Duration.ofSeconds(5)));
        runner = new RedisScriptRunner(connection);
        // The first call pays for the TCP connect, Netty/Lettuce class loading and SCRIPT LOAD. Do that before any
        // timed run, otherwise a slow machine spends the whole measurement window on setup.
        for (Algorithm a : Algorithm.values()) {
            Limiters.create(runner, LimiterSpec.of(a, 1, Duration.ofSeconds(1))).tryAcquire("test:warmup:" + a);
        }
    }

    @AfterAll
    static void disconnect() {
        connection.close();
    }

    /** 100 clients all call at the same instant. A bucket of 50 must hand out 50 tokens, never 51. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void simultaneousBurstAllowsExactlyTheLimit(Algorithm algorithm) throws Exception {
        // A 10 minute window means no token refill or window rollover can happen during the test.
        RateLimiter limiter = Limiters.create(runner, LimiterSpec.of(algorithm, LIMIT, Duration.ofMinutes(10)));
        String key = "test:burst:" + algorithm + ":" + UUID.randomUUID();

        AtomicLong allowed = new AtomicLong();
        CyclicBarrier start = new CyclicBarrier(CLIENTS);
        try (ExecutorService pool = Executors.newFixedThreadPool(CLIENTS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire(key).allowed()) allowed.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        }
        assertThat(allowed.get()).isEqualTo(LIMIT);
    }

    /**
     * Sustained load for 3 seconds from 100 clients at 50/s with capacity 50.
     * A bucket can never have produced more than capacity + rate * elapsed tokens, and the clients' elapsed time
     * is at least Redis's, so that is a hard upper bound.
     */
    @Test
    void tokenBucketNeverExceedsCapacityPlusRefillUnderSustainedLoad() throws Exception {
        RateLimiter limiter = Limiters.create(runner,
                new LimiterSpec(Algorithm.TOKEN_BUCKET, LIMIT, Duration.ofSeconds(1), LIMIT));
        String key = "test:sustained:" + UUID.randomUUID();

        Run run = hammer(limiter, key, Duration.ofSeconds(3));

        double elapsed = run.elapsedSeconds;
        long upperBound = (long) Math.ceil(LIMIT + LIMIT * elapsed);
        System.out.printf("token bucket: allowed=%d denied=%d elapsed=%.2fs bound=%d%n",
                run.allowed, run.denied, elapsed, upperBound);
        assertThat(run.allowed).as("allowed in %.2fs", elapsed).isLessThanOrEqualTo(upperBound);
        // Not stuck closed: must have used most of what the bucket offered.
        assertThat(run.allowed).isGreaterThanOrEqualTo((long) (LIMIT + LIMIT * (elapsed - 0.5)));
        assumeTrue(run.denied > run.allowed,
                "load never saturated the limiter (machine too slow), so the upper bound above proved nothing");
    }

    /** Window algorithms: each 1s window admits at most LIMIT, so N windows of traffic admit at most N * LIMIT. */
    @ParameterizedTest
    @EnumSource(value = Algorithm.class, names = {"FIXED_WINDOW", "SLIDING_LOG", "SLIDING_COUNTER"})
    void windowAlgorithmsNeverExceedLimitPerWindowUnderSustainedLoad(Algorithm algorithm) throws Exception {
        RateLimiter limiter = Limiters.create(runner, LimiterSpec.of(algorithm, LIMIT, Duration.ofSeconds(1)));
        String key = "test:window:" + algorithm + ":" + UUID.randomUUID();

        Run run = hammer(limiter, key, Duration.ofSeconds(3));

        // A run of E seconds touches at most ceil(E) + 1 fixed windows, and ceil(E) sliding ones.
        long windowsTouched = (long) Math.ceil(run.elapsedSeconds) + (algorithm == Algorithm.FIXED_WINDOW ? 1 : 0);
        assertThat(run.allowed).as("allowed in %.2fs", run.elapsedSeconds).isLessThanOrEqualTo(LIMIT * windowsTouched);
        assertThat(run.allowed).isGreaterThanOrEqualTo(LIMIT * 2);
    }

    private record Run(long allowed, long denied, double elapsedSeconds) {
    }

    private static Run hammer(RateLimiter limiter, String key, Duration duration) throws Exception {
        AtomicLong allowed = new AtomicLong();
        AtomicLong denied = new AtomicLong();
        CyclicBarrier ready = new CyclicBarrier(CLIENTS + 1);
        long[] endNanos = new long[1];
        try (ExecutorService pool = Executors.newFixedThreadPool(CLIENTS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CLIENTS; i++) {
                futures.add(pool.submit(() -> {
                    ready.await();
                    long stop = System.nanoTime() + duration.toNanos();
                    while (System.nanoTime() < stop) {
                        if (limiter.tryAcquire(key).allowed()) allowed.incrementAndGet();
                        else denied.incrementAndGet();
                    }
                    return null;
                }));
            }
            long begin = System.nanoTime();
            ready.await();
            for (Future<?> f : futures) f.get();
            endNanos[0] = System.nanoTime();
            return new Run(allowed.get(), denied.get(), (endNanos[0] - begin) / 1e9);
        }
    }
}
