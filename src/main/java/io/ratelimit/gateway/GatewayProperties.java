package io.ratelimit.gateway;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:9000") URI upstream,
        @DefaultValue("redis://localhost:6379") String redisUri,
        @DefaultValue("policies.yml") Path policyFile,
        @DefaultValue("1s") Duration policyPollInterval,
        @DefaultValue("X-API-Key") String apiKeyHeader,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Redis redis,
        @DefaultValue Breaker breaker) {

    /** Timeouts are deliberately short: the limiter sits on the hot path of every request. */
    public record Redis(
            @DefaultValue("200ms") Duration connectTimeout,
            @DefaultValue("100ms") Duration commandTimeout) {
    }

    public record Breaker(
            @DefaultValue("20") int windowSize,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("50") float failureRatePercent,
            @DefaultValue("5s") Duration openDuration,
            @DefaultValue("3") int halfOpenCalls) {
    }
}
