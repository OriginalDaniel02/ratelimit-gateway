package io.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.ratelimit.dev.DummyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

/** RL-3 acceptance: a limit change takes effect without a restart; an invalid edit is rejected clearly. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.ratelimit.gateway.GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHotReloadTest {

    private static final String POLICY_TEMPLATE = """
            rules:
              - name: per-key
                scope: API_KEY
                match: "*"
                algorithm: FIXED_WINDOW
                limit: %d
                window: 1h
            """;

    @Container
    static final GenericContainer<?> REDIS = TestSupport.newRedis();
    static HttpServer upstream;
    static final Path POLICY = TestSupport.tempPolicy(POLICY_TEMPLATE.formatted(2));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        upstream = DummyService.start(0);
        r.add("gateway.upstream", () -> "http://localhost:" + upstream.getAddress().getPort());
        r.add("gateway.redis-uri", () -> TestSupport.redisUri(REDIS));
        r.add("gateway.policy-file", POLICY::toString);
        r.add("gateway.policy-poll-interval", () -> "100ms");
        r.add("gateway.redis.command-timeout", () -> "3s");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @LocalServerPort
    int port;

    int status(String key) {
        return TestSupport.get("http://localhost:" + port + "/x", "X-API-Key", key).statusCode();
    }

    String gatewayStatus() {
        return TestSupport.get("http://localhost:" + port + "/_gateway/status").body();
    }

    @Test
    void limitChangeAppliesWithoutRestartAndBadConfigIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(status(key)).isEqualTo(200);
        assertThat(status(key)).isEqualTo(200);
        assertThat(status(key)).as("limit is 2").isEqualTo(429);

        // Raise the limit to 4 on the live gateway: same key, same Redis state, no restart.
        Files.writeString(POLICY, POLICY_TEMPLATE.formatted(4));
        TestSupport.await(Duration.ofSeconds(10), "limit 4 to apply", () -> status(key) == 200);
        assertThat(status(key)).isEqualTo(200);
        assertThat(status(key)).as("limit is now 4").isEqualTo(429);

        // An invalid edit is rejected with a clear message and the previous rules stay in force.
        Files.writeString(POLICY, POLICY_TEMPLATE.formatted(-1).replace("FIXED_WINDOW", "SPIRAL"));
        TestSupport.await(Duration.ofSeconds(10), "bad config to be reported", () -> gatewayStatus().contains("policyError\":\""));
        String error = gatewayStatus();
        assertThat(error).contains("'algorithm' is 'SPIRAL'", "'limit' must be a positive integer");
        String otherKey = UUID.randomUUID().toString();
        for (int i = 0; i < 4; i++) assertThat(status(otherKey)).isEqualTo(200);
        assertThat(status(otherKey)).as("still limit 4 after rejected edit").isEqualTo(429);

        // Fixing the file clears the error.
        Files.writeString(POLICY, POLICY_TEMPLATE.formatted(4) + "\n");
        TestSupport.await(Duration.ofSeconds(10), "error to clear", () -> gatewayStatus().contains("\"policyError\":null"));
    }
}
