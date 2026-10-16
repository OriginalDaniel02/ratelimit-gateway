package io.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ratelimit.limiter.Algorithm;
import io.ratelimit.policy.ConfigException;
import io.ratelimit.policy.FailMode;
import io.ratelimit.policy.PolicyLoader;
import io.ratelimit.policy.Rule;
import io.ratelimit.policy.Scope;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyLoaderTest {

    @Test
    void parsesValidPolicyWithDefaults() {
        List<Rule> rules = PolicyLoader.parse("""
                rules:
                  - name: a
                    scope: api_key
                    match: "*"
                    limit: 10
                    window: 500ms
                  - name: b
                    scope: ROUTE
                    match: /api/**
                    algorithm: sliding-log
                    limit: 5
                    window: 1m
                    onRedisFailure: FAIL_CLOSED
                """);

        assertThat(rules).hasSize(2);
        Rule a = rules.get(0);
        assertThat(a.scope()).isEqualTo(Scope.API_KEY);
        assertThat(a.limiter().algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
        assertThat(a.limiter().window()).isEqualTo(Duration.ofMillis(500));
        assertThat(a.onRedisFailure()).isEqualTo(FailMode.FAIL_OPEN);
        assertThat(rules.get(1).limiter().algorithm()).isEqualTo(Algorithm.SLIDING_LOG);
        assertThat(rules.get(1).onRedisFailure()).isEqualTo(FailMode.FAIL_CLOSED);
    }

    @Test
    void reportsEveryProblemWithTheRuleItBelongsTo() {
        assertThatThrownBy(() -> PolicyLoader.parse("""
                rules:
                  - name: bad
                    scope: USER
                    match: "*"
                    limit: -5
                    window: soon
                    colour: red
                  - name: bad
                    scope: IP
                    match: "*"
                    algorithm: FIXED_WINDOW
                    limit: 5
                    window: 1s
                    burst: 10
                """))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> {
                    String m = e.getMessage();
                    assertThat(m).contains("rules[0] (bad): 'scope' is 'USER', must be one of");
                    assertThat(m).contains("rules[0] (bad): 'limit' must be a positive integer");
                    assertThat(m).contains("rules[0] (bad): 'window' must look like");
                    assertThat(m).contains("rules[0] (bad): unknown field 'colour'");
                    assertThat(m).contains("rules[1] (bad): 'burst' only applies to TOKEN_BUCKET");
                });
    }

    @Test
    void rejectsDuplicateNamesBurstBelowLimitAndBadPathPattern() {
        assertThatThrownBy(() -> PolicyLoader.parse("""
                rules:
                  - {name: x, scope: IP, match: "*", limit: 5, window: 1s, burst: 2}
                  - {name: x, scope: ROUTE, match: "/a/**/b/**/c/{", limit: 5, window: 1s}
                """))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("'burst' (2) must be >= 'limit' (5)");
                    assertThat(e.getMessage()).contains("duplicate rule name 'x'");
                    assertThat(e.getMessage()).contains("not a valid path pattern");
                });
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> PolicyLoader.parse("just a string")).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> PolicyLoader.parse("rules: []")).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> PolicyLoader.parse("rules: [unclosed")).isInstanceOf(ConfigException.class)
                .hasMessageContaining("not valid YAML");
    }
}
