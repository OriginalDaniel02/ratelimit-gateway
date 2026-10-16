package io.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.ratelimit.dev.DummyService;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RL-5 acceptance: Redis is taken away while requests are in flight, and the documented behavior is asserted.
 * <ul>
 *   <li>/open/**   FAIL_OPEN:   keeps returning 200 (unlimited), marked X-RateLimit-Degraded: fail-open</li>
 *   <li>/closed/** FAIL_CLOSED: returns 503 with Retry-After, marked X-RateLimit-Degraded: fail-closed</li>
 * </ul>
 * First a frozen Redis (docker pause: connections hang, so the command timeout and breaker are exercised),
 * then a dead one (container stopped: connection refused).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.ratelimit.gateway.GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisFailureTest {

    @Container
    static final GenericContainer<?> REDIS = TestSupport.newRedis();
    static HttpServer upstream;
    static final Path POLICY = TestSupport.tempPolicy("""
            rules:
              - name: open-route
                scope: ROUTE
                match: /open/**
                algorithm: FIXED_WINDOW
                limit: 1000000
                window: 1h
                onRedisFailure: FAIL_OPEN
              - name: closed-route
                scope: ROUTE
                match: /closed/**
                algorithm: FIXED_WINDOW
                limit: 1000000
                window: 1h
                onRedisFailure: FAIL_CLOSED
            """);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        upstream = DummyService.start(0);
        r.add("gateway.upstream", () -> "http://localhost:" + upstream.getAddress().getPort());
        r.add("gateway.redis-uri", () -> TestSupport.redisUri(REDIS));
        r.add("gateway.policy-file", POLICY::toString);
        // 1s so that "answered without waiting on Redis" is unambiguous: a call that touched a frozen Redis takes >= 1s.
        r.add("gateway.redis.command-timeout", () -> "1s");
        r.add("gateway.breaker.window-size", () -> "10");
        r.add("gateway.breaker.minimum-calls", () -> "5");
        r.add("gateway.breaker.open-duration", () -> "2s");
        r.add("gateway.breaker.half-open-calls", () -> "2");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String breakerState() {
        String body = TestSupport.get(url("/_gateway/status")).body();
        return body.replaceAll(".*\"circuitBreaker\":\"(\\w+)\".*", "$1");
    }

    /** Background traffic on both routes for the whole test; records any /open request that did not return 200. */
    private final class Load implements AutoCloseable {
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger openFailures = new AtomicInteger();
        final AtomicInteger openRequests = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final List<Future<?>> futures = new ArrayList<>();

        Load() {
            for (int i = 0; i < 8; i++) {
                String path = i % 2 == 0 ? "/open/load" : "/closed/load";
                futures.add(pool.submit(() -> {
                    while (running.get()) {
                        HttpResponse<String> r = TestSupport.get(url(path));
                        if (path.startsWith("/open")) {
                            openRequests.incrementAndGet();
                            if (r.statusCode() != 200) openFailures.incrementAndGet();
                        }
                    }
                }));
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            for (Future<?> f : futures) f.get();
            pool.shutdown();
        }
    }

    private void assertDocumentedDegradedBehavior() {
        long t0 = System.nanoTime();
        HttpResponse<String> open = TestSupport.get(url("/open/x"));
        long openMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(open.statusCode()).as("FAIL_OPEN route keeps serving").isEqualTo(200);
        assertThat(open.headers().firstValue("X-RateLimit-Degraded")).contains("fail-open");
        assertThat(open.headers().firstValue("RateLimit-Limit")).as("no fabricated limit info").isEmpty();
        assertThat(openMs).as("open breaker answers without waiting for the 1s Redis timeout").isLessThan(500);

        HttpResponse<String> closed = TestSupport.get(url("/closed/x"));
        assertThat(closed.statusCode()).as("FAIL_CLOSED route rejects").isEqualTo(503);
        assertThat(closed.headers().firstValue("Retry-After")).isPresent();
        assertThat(closed.headers().firstValue("X-RateLimit-Degraded")).contains("fail-closed");
        assertThat(closed.body()).contains("limiter_unavailable");
    }

    @Test
    @Order(1)
    void redisFrozenMidLoad_breakerOpensBehaviorMatchesDocsAndRecovers() throws Exception {
        assertThat(TestSupport.get(url("/open/x")).headers().firstValue("RateLimit-Limit")).isPresent();
        assertThat(TestSupport.get(url("/closed/x")).statusCode()).isEqualTo(200);

        try (Load load = new Load()) {
            Thread.sleep(500);
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                TestSupport.await(Duration.ofSeconds(15), "breaker to open", () -> breakerState().equals("OPEN"));
                assertDocumentedDegradedBehavior();
            } finally {
                REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }

            TestSupport.await(Duration.ofSeconds(20), "breaker to close and limiting to resume", () ->
                    breakerState().equals("CLOSED")
                            && TestSupport.get(url("/closed/x")).statusCode() == 200);
            assertThat(TestSupport.get(url("/open/x")).headers().firstValue("RateLimit-Limit")).isPresent();

            load.close();
            assertThat(load.openRequests.get()).isGreaterThan(50);
            assertThat(load.openFailures.get()).as("FAIL_OPEN route never failed during the outage").isZero();
        }
    }

    @Test
    @Order(2)
    void redisKilledMidLoad_behaviorMatchesDocs() throws Exception {
        try (Load load = new Load()) {
            Thread.sleep(500);
            REDIS.stop();
            TestSupport.await(Duration.ofSeconds(15), "breaker to open", () -> breakerState().equals("OPEN"));
            assertDocumentedDegradedBehavior();

            load.close();
            assertThat(load.openFailures.get()).as("FAIL_OPEN route never failed during the outage").isZero();
        }
    }
}
