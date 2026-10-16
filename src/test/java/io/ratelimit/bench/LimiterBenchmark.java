package io.ratelimit.bench;

import io.ratelimit.gateway.GatewayProperties;
import io.ratelimit.gateway.RedisConnection;
import io.ratelimit.limiter.Algorithm;
import io.ratelimit.limiter.LimiterSpec;
import io.ratelimit.limiter.Limiters;
import io.ratelimit.limiter.RateLimiter;
import io.ratelimit.limiter.RedisScriptRunner;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of one limiter check against a real Redis. {@code baseline} is the same loop without a limiter call, so the
 * difference is the price of limiting. Limits are set absurdly high so every call takes the "allowed" path.
 * Each thread uses its own key to measure the algorithm, not contention on one hot key.
 *
 * Needs Redis: docker run --rm -p 6379:6379 redis:7-alpine   (override with -DREDIS_URI=redis://host:port)
 * Run: see README, "Benchmarks".
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@State(Scope.Benchmark)
public class LimiterBenchmark {

    private static final long HUGE = 1_000_000_000L;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    private RedisConnection connection;
    private RateLimiter tokenBucket;
    private RateLimiter fixedWindow;
    private RateLimiter slidingLog;
    private RateLimiter slidingCounter;

    @State(Scope.Thread)
    public static class Key {
        final String name = "bench:" + THREAD_IDS.incrementAndGet() + ":" + System.nanoTime();
    }

    @Setup
    public void setup() {
        String uri = System.getProperty("REDIS_URI", "redis://127.0.0.1:6379");
        connection = new RedisConnection(uri, new GatewayProperties.Redis(Duration.ofSeconds(2), Duration.ofSeconds(5)));
        RedisScriptRunner runner = new RedisScriptRunner(connection);
        Duration window = Duration.ofHours(1);
        tokenBucket = Limiters.create(runner, new LimiterSpec(Algorithm.TOKEN_BUCKET, HUGE, window, HUGE));
        fixedWindow = Limiters.create(runner, LimiterSpec.of(Algorithm.FIXED_WINDOW, HUGE, window));
        // The log stores one member per allowed request, so keep its window short to bound memory during the run.
        slidingLog = Limiters.create(runner, LimiterSpec.of(Algorithm.SLIDING_LOG, HUGE, Duration.ofSeconds(1)));
        slidingCounter = Limiters.create(runner, LimiterSpec.of(Algorithm.SLIDING_COUNTER, HUGE, window));
    }

    @TearDown
    public void tearDown() {
        connection.close();
    }

    @Benchmark
    public boolean baseline(Key k) {
        return k.name.length() > 0;
    }

    @Benchmark
    public boolean tokenBucket(Key k) {
        return tokenBucket.tryAcquire(k.name).allowed();
    }

    @Benchmark
    public boolean fixedWindow(Key k) {
        return fixedWindow.tryAcquire(k.name).allowed();
    }

    @Benchmark
    public boolean slidingLog(Key k) {
        return slidingLog.tryAcquire(k.name).allowed();
    }

    @Benchmark
    public boolean slidingCounter(Key k) {
        return slidingCounter.tryAcquire(k.name).allowed();
    }
}
