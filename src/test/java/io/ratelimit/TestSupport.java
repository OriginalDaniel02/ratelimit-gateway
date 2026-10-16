package io.ratelimit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.testcontainers.containers.GenericContainer;

public final class TestSupport {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private TestSupport() {
    }

    public static GenericContainer<?> newRedis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }

    public static String redisUri(GenericContainer<?> redis) {
        return "redis://127.0.0.1:" + redis.getMappedPort(6379);
    }

    public static Path tempPolicy(String yaml) {
        try {
            Path p = Files.createTempFile("policies", ".yml");
            p.toFile().deleteOnExit();
            Files.writeString(p, yaml);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HttpResponse<String> get(String url, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET();
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static HttpResponse<String> post(String url, String body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static void await(Duration timeout, String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for: " + what);
    }
}
