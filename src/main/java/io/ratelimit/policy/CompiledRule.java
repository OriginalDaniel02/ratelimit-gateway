package io.ratelimit.policy;

import io.ratelimit.limiter.RateLimiter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** A rule bound to its limiter, ready to be matched against a request. */
public final class CompiledRule {

    private final Rule rule;
    private final RateLimiter limiter;
    private final PathPattern pattern;

    CompiledRule(Rule rule, RateLimiter limiter) {
        this.rule = rule;
        this.limiter = limiter;
        this.pattern = rule.scope() == Scope.ROUTE ? PathPatternParser.defaultInstance.parse(rule.match()) : null;
    }

    public Rule rule() {
        return rule;
    }

    public RateLimiter limiter() {
        return limiter;
    }

    /**
     * Returns the Redis key for this request, or null when the rule does not apply to it.
     * An absent API key means API_KEY rules simply do not apply.
     */
    public String keyFor(String apiKey, String ip, String path) {
        String identity = switch (rule.scope()) {
            case API_KEY -> select(apiKey);
            case IP -> select(ip);
            case ROUTE -> pattern.matches(PathContainer.parsePath(path)) ? rule.match() : null;
        };
        return identity == null ? null : "rl:" + rule.name() + ":" + identity;
    }

    private String select(String value) {
        if (value == null) return null;
        if (rule.match().equals("*") || rule.match().equals(value)) return value;
        return null;
    }
}
