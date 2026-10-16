package io.ratelimit.gateway;

import io.ratelimit.limiter.Decision;
import io.ratelimit.policy.CompiledRule;
import io.ratelimit.policy.PolicyRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reverse proxy with rate limiting. Every rule that matches the request is checked in order;
 * the first denial returns 429. If Redis is unavailable the matching rule's fail mode decides.
 */
public final class GatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayFilter.class);

    /** Hop-by-hop headers (RFC 9110 7.6.1) plus ones the HTTP client sets itself. */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect");

    private final GatewayProperties props;
    private final PolicyRegistry policies;
    private final RedisGuard guard;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GatewayFilter(GatewayProperties props, PolicyRegistry policies, RedisGuard guard) {
        this.props = props;
        this.policies = policies;
        this.guard = guard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().equals("/_gateway/status")) {
            status(resp);
            return;
        }

        String apiKey = req.getHeader(props.apiKeyHeader());
        String ip = clientIp(req);
        Decision tightest = null;

        for (CompiledRule rule : policies.rules()) {
            String key = rule.keyFor(apiKey, ip, req.getRequestURI());
            if (key == null) continue;
            Verdict v = guard.check(rule, key);
            switch (v.kind()) {
                case FAIL_CLOSED -> {
                    resp.setHeader("Retry-After", Long.toString(Math.max(1, props.breaker().openDuration().toSeconds())));
                    resp.setHeader("X-RateLimit-Degraded", "fail-closed");
                    reject(resp, 503, "limiter_unavailable", v.ruleName(), 0);
                    return;
                }
                case FAIL_OPEN -> resp.setHeader("X-RateLimit-Degraded", "fail-open");
                case DENY -> {
                    rateHeaders(resp, v.decision());
                    long retrySeconds = Math.max(1, (v.decision().retryAfterMs() + 999) / 1000);
                    resp.setHeader("Retry-After", Long.toString(retrySeconds));
                    reject(resp, 429, "rate_limited", v.ruleName(), v.decision().retryAfterMs());
                    return;
                }
                case ALLOW -> {
                    if (tightest == null || v.decision().remaining() < tightest.remaining()) tightest = v.decision();
                }
            }
        }
        if (tightest != null) rateHeaders(resp, tightest);
        proxy(req, resp);
    }

    /** IETF draft-ietf-httpapi-ratelimit-headers names. Reset is in seconds, rounded up. */
    private static void rateHeaders(HttpServletResponse resp, Decision d) {
        resp.setHeader("RateLimit-Limit", Long.toString(d.limit()));
        resp.setHeader("RateLimit-Remaining", Long.toString(Math.max(0, d.remaining())));
        resp.setHeader("RateLimit-Reset", Long.toString((d.resetMs() + 999) / 1000));
    }

    private void proxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String query = req.getQueryString();
        URI target = URI.create(props.upstream().toString().replaceAll("/+$", "") + req.getRequestURI()
                + (query == null ? "" : "?" + query));

        boolean hasBody = req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null;
        HttpRequest.Builder out = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .method(req.getMethod(), hasBody
                        ? HttpRequest.BodyPublishers.ofInputStream(() -> inputOf(req))
                        : HttpRequest.BodyPublishers.noBody());
        for (Enumeration<String> names = req.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement();
            if (SKIP_HEADERS.contains(name.toLowerCase())) continue;
            for (Enumeration<String> values = req.getHeaders(name); values.hasMoreElements(); ) {
                out.header(name, values.nextElement());
            }
        }
        out.header("X-Forwarded-For", clientIp(req));

        try {
            HttpResponse<InputStream> upstream = http.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
            resp.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                if (name.startsWith(":") || SKIP_HEADERS.contains(name.toLowerCase()) && !name.equalsIgnoreCase("content-length")) return;
                values.forEach(v -> resp.addHeader(name, v));
            });
            try (InputStream body = upstream.body()) {
                body.transferTo(resp.getOutputStream());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reject(resp, 502, "bad_gateway", null, 0);
        } catch (IOException e) {
            log.warn("Upstream call failed: {}", e.toString());
            if (!resp.isCommitted()) reject(resp, 502, "bad_gateway", null, 0);
        }
    }

    private static InputStream inputOf(HttpServletRequest req) {
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String clientIp(HttpServletRequest req) {
        if (props.trustForwardedFor()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private void status(HttpServletResponse resp) throws IOException {
        String err = policies.lastError();
        String json = "{\"circuitBreaker\":\"" + guard.state() + "\",\"degradedChecks\":" + guard.degradedCount()
                + ",\"rules\":" + policies.rules().size()
                + ",\"policyError\":" + (err == null ? "null" : "\"" + escape(err) + "\"") + "}";
        resp.setContentType("application/json");
        resp.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse resp, int status, String error, String rule, long retryMs)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        String body = "{\"error\":\"" + error + "\"" + (rule == null ? "" : ",\"rule\":\"" + escape(rule) + "\"")
                + (retryMs > 0 ? ",\"retryAfterMs\":" + retryMs : "") + "}";
        resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
