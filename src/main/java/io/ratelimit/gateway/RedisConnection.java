package io.ratelimit.gateway;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.function.Supplier;

/**
 * One shared Lettuce connection, opened on first use so the gateway can start while Redis is down.
 * After the first connect Lettuce reconnects on its own; while disconnected, commands are rejected
 * immediately instead of queueing, so callers fail fast and the circuit breaker sees the failure.
 */
public final class RedisConnection implements Supplier<StatefulRedisConnection<String, String>>, AutoCloseable {

    private final RedisClient client;
    private final GatewayProperties.Redis settings;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisConnection(String uri, GatewayProperties.Redis settings) {
        this.settings = settings;
        this.client = RedisClient.create(uri);
        this.client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled(settings.commandTimeout()))
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }

    @Override
    public StatefulRedisConnection<String, String> get() {
        StatefulRedisConnection<String, String> c = connection;
        if (c != null) return c;
        synchronized (this) {
            if (connection == null) {
                StatefulRedisConnection<String, String> fresh = client.connect();
                fresh.setTimeout(settings.commandTimeout());
                connection = fresh;
            }
            return connection;
        }
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> c = connection;
        if (c != null) c.close();
        client.shutdown();
    }
}
