package io.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.ratelimit.dev.DummyService;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** RL-4 acceptance: the filter works as a reverse proxy in front of a dummy service, with 429 + headers. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.ratelimit.gateway.GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayProxyTest {

    @Container
    static final GenericContainer<?> REDIS = TestSupport.newRedis();
    static HttpServer upstream;
    static final Path POLICY = TestSupport.tempPolicy("""
            rules:
              - name: per-key
                scope: API_KEY
                match: "*"
                algorithm: FIXED_WINDOW
                limit: 3
                window: 1h
              - name: limited-route
                scope: ROUTE
                match: /limited/**
                algorithm: SLIDING_LOG
                limit: 2
                window: 1h
            """);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        upstream = DummyService.start(0);
        r.add("gateway.upstream", () -> "http://localhost:" + upstream.getAddress().getPort());
        r.add("gateway.redis-uri", () -> TestSupport.redisUri(REDIS));
        r.add("gateway.policy-file", POLICY::toString);
        // Generous: this test is about headers and 429s, not timeouts, and a slow CI box must not trigger fail-open.
        r.add("gateway.redis.command-timeout", () -> "3s");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @LocalServerPort
    int port;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void proxiesMethodPathQueryAndBody() {
        String key = UUID.randomUUID().toString();
        HttpResponse<String> get = TestSupport.get(url("/orders/7?x=1&y=two"), "X-API-Key", key);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).contains("\"method\":\"GET\"", "\"path\":\"/orders/7?x=1&y=two\"");

        HttpResponse<String> post = TestSupport.post(url("/orders"), "hello world", "X-API-Key", key);
        assertThat(post.statusCode()).isEqualTo(200);
        assertThat(post.body()).contains("\"method\":\"POST\"", "\"bodyBytes\":11");
    }

    @Test
    void addsRateLimitHeadersAndReturns429WithRetryAfterWhenExhausted() {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = TestSupport.get(url("/a"), "X-API-Key", key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("RateLimit-Limit")).contains("3");
        assertThat(first.headers().firstValue("RateLimit-Remaining")).contains("2");
        assertThat(first.headers().firstValue("RateLimit-Reset")).isPresent();

        TestSupport.get(url("/a"), "X-API-Key", key);
        HttpResponse<String> third = TestSupport.get(url("/a"), "X-API-Key", key);
        assertThat(third.statusCode()).isEqualTo(200);
        assertThat(third.headers().firstValue("RateLimit-Remaining")).contains("0");

        HttpResponse<String> blocked = TestSupport.get(url("/a"), "X-API-Key", key);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        assertThat(blocked.headers().firstValue("RateLimit-Remaining")).contains("0");
        assertThat(blocked.body()).contains("\"error\":\"rate_limited\"", "\"rule\":\"per-key\"");
    }

    @Test
    void limitsAreIndependentPerApiKey() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) TestSupport.get(url("/a"), "X-API-Key", a);
        assertThat(TestSupport.get(url("/a"), "X-API-Key", a).statusCode()).isEqualTo(429);
        assertThat(TestSupport.get(url("/a"), "X-API-Key", b).statusCode()).isEqualTo(200);
    }

    @Test
    void routeRuleIsSharedByAllCallers() {
        String route = "/limited/" + UUID.randomUUID();
        // Rule key is the pattern, not the path, so use the pattern's shared bucket once and expect it spent.
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            if (TestSupport.get(url(route), "X-API-Key", UUID.randomUUID().toString()).statusCode() == 200) ok++;
        }
        assertThat(ok).as("sliding log of 2 per hour shared by every caller on /limited/**").isLessThanOrEqualTo(2);
    }

    @Test
    void requestsWithoutApiKeyBypassKeyRules() {
        for (int i = 0; i < 5; i++) {
            assertThat(TestSupport.get(url("/nokey")).statusCode()).isEqualTo(200);
        }
    }
}
