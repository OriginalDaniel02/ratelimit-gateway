package io.ratelimit.policy;

import io.ratelimit.limiter.LimiterSpec;
import io.ratelimit.limiter.RateLimiter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the active rules and swaps them atomically when the policy file changes.
 * An invalid edit is rejected: the previous rules stay active and the error is logged and kept in {@link #lastError()}.
 * Limiter state lives in Redis, so swapping rules does not reset anyone's quota.
 */
public final class PolicyRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

    private final Path file;
    private final Function<LimiterSpec, RateLimiter> limiterFactory;
    private final AtomicReference<List<CompiledRule>> active = new AtomicReference<>(List.of());
    private final ScheduledExecutorService poller;
    private volatile String lastContent;
    private volatile String lastError;

    /** Loads the file immediately; throws {@link ConfigException} if it is invalid so startup fails loudly. */
    public PolicyRegistry(Path file, Function<LimiterSpec, RateLimiter> limiterFactory, Duration pollInterval) {
        this.file = file;
        this.limiterFactory = limiterFactory;
        String content = read();
        active.set(compile(PolicyLoader.parse(content)));
        lastContent = content;
        if (pollInterval != null) {
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "policy-reload");
                t.setDaemon(true);
                return t;
            });
            poller.scheduleWithFixedDelay(this::reloadNow, pollInterval.toMillis(), pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        } else {
            poller = null;
        }
    }

    public List<CompiledRule> rules() {
        return active.get();
    }

    /** The reason the last reload attempt was rejected, or null if the active policy is the latest file content. */
    public String lastError() {
        return lastError;
    }

    /** Re-reads the file now. Returns true if a new policy was applied. */
    public synchronized boolean reloadNow() {
        try {
            String content = read();
            if (content.equals(lastContent)) return false;
            lastContent = content; // remember bad content too, so a rejected file is reported once, not every poll
            List<CompiledRule> compiled = compile(PolicyLoader.parse(content));
            active.set(compiled);
            lastError = null;
            log.info("Policy reloaded: {} rules from {}", compiled.size(), file);
            return true;
        } catch (ConfigException e) {
            lastError = e.getMessage();
            log.error("Rejected policy change, keeping previous rules. {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.error("Could not reload policy from {}, keeping previous rules", file, e);
            return false;
        }
    }

    private List<CompiledRule> compile(List<Rule> rules) {
        return rules.stream().map(r -> new CompiledRule(r, limiterFactory.apply(r.limiter()))).toList();
    }

    private String read() {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(List.of("cannot read policy file " + file + ": " + e.getMessage()));
        }
    }

    @Override
    public void close() {
        if (poller != null) poller.shutdownNow();
    }
}
