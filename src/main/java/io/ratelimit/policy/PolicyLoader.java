package io.ratelimit.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ratelimit.limiter.Algorithm;
import io.ratelimit.limiter.LimiterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Parses and validates a YAML policy file. Reports every problem at once with the rule it belongs to. */
public final class PolicyLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> RULE_FIELDS =
            Set.of("name", "scope", "match", "algorithm", "limit", "window", "burst", "onRedisFailure");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");

    private PolicyLoader() {
    }

    public static List<Rule> parse(String yaml) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (Exception e) {
            throw new ConfigException(List.of("not valid YAML: " + firstLine(e.getMessage())));
        }
        if (root == null || !root.isObject() || !root.has("rules")) {
            throw new ConfigException(List.of("top level must be a mapping with a 'rules' list"));
        }
        JsonNode rulesNode = root.get("rules");
        if (!rulesNode.isArray() || rulesNode.isEmpty()) {
            throw new ConfigException(List.of("'rules' must be a non-empty list"));
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < rulesNode.size(); i++) {
            JsonNode node = rulesNode.get(i);
            String label = "rules[" + i + "]" + (node.hasNonNull("name") ? " (" + node.get("name").asText() + ")" : "");
            if (node.hasNonNull("name") && !names.add(node.get("name").asText())) {
                errors.add(label + ": duplicate rule name '" + node.get("name").asText() + "'");
            }
            Rule rule = parseRule(node, label, errors);
            if (rule != null) rules.add(rule);
        }
        if (!errors.isEmpty()) throw new ConfigException(errors);
        return List.copyOf(rules);
    }

    private static Rule parseRule(JsonNode node, String label, List<String> errors) {
        int before = errors.size();
        if (!node.isObject()) {
            errors.add(label + ": must be a mapping");
            return null;
        }
        node.fieldNames().forEachRemaining(f -> {
            if (!RULE_FIELDS.contains(f)) errors.add(label + ": unknown field '" + f + "' (allowed: " + RULE_FIELDS + ")");
        });

        String name = text(node, "name");
        if (name == null || name.isBlank()) errors.add(label + ": 'name' is required");

        Scope scope = enumValue(node, "scope", Scope.class, null, label, errors);
        String match = text(node, "match");
        if (match == null || match.isBlank()) errors.add(label + ": 'match' is required (use \"*\" for every key/IP)");
        if (scope == Scope.ROUTE && match != null && !match.isBlank()) {
            try {
                PathPatternParser.defaultInstance.parse(match);
            } catch (Exception e) {
                errors.add(label + ": 'match' is not a valid path pattern: " + firstLine(e.getMessage()));
            }
        }

        Algorithm algorithm = enumValue(node, "algorithm", Algorithm.class, Algorithm.TOKEN_BUCKET, label, errors);
        FailMode failMode = enumValue(node, "onRedisFailure", FailMode.class, FailMode.FAIL_OPEN, label, errors);

        long limit = 0;
        if (!node.hasNonNull("limit") || !node.get("limit").canConvertToLong() || node.get("limit").asLong() <= 0) {
            errors.add(label + ": 'limit' must be a positive integer");
        } else {
            limit = node.get("limit").asLong();
        }

        Duration window = null;
        if (!node.hasNonNull("window")) {
            errors.add(label + ": 'window' is required, e.g. 1s, 500ms, 1m");
        } else {
            window = parseDuration(node.get("window").asText());
            if (window == null) {
                errors.add(label + ": 'window' must look like 500ms, 1s, 1m or 1h, got '" + node.get("window").asText() + "'");
            }
        }

        long burst = 0;
        if (node.hasNonNull("burst")) {
            if (!node.get("burst").canConvertToLong() || node.get("burst").asLong() <= 0) {
                errors.add(label + ": 'burst' must be a positive integer");
            } else if (algorithm != null && algorithm != Algorithm.TOKEN_BUCKET) {
                errors.add(label + ": 'burst' only applies to TOKEN_BUCKET, not " + algorithm);
            } else {
                burst = node.get("burst").asLong();
                if (limit > 0 && burst < limit) {
                    errors.add(label + ": 'burst' (" + burst + ") must be >= 'limit' (" + limit + ")");
                }
            }
        }

        if (errors.size() > before) return null;
        return new Rule(name, scope, match, new LimiterSpec(algorithm, limit, window, burst), failMode);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type, E fallback,
                                                   String label, List<String> errors) {
        if (!node.hasNonNull(field)) {
            if (fallback == null) errors.add(label + ": '" + field + "' is required, one of " + List.of(type.getEnumConstants()));
            return fallback;
        }
        try {
            return Enum.valueOf(type, node.get(field).asText().trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            errors.add(label + ": '" + field + "' is '" + node.get(field).asText() + "', must be one of "
                    + List.of(type.getEnumConstants()));
            return null;
        }
    }

    static Duration parseDuration(String text) {
        Matcher m = DURATION.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        if (n <= 0) return null;
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    private static String firstLine(String s) {
        return s == null ? "unknown error" : s.lines().findFirst().orElse(s);
    }
}
