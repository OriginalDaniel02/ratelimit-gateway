package io.ratelimit.policy;

import java.util.List;

/** Thrown when a policy file is unreadable or invalid. Carries every problem found, not just the first. */
public class ConfigException extends RuntimeException {

    private final List<String> errors;

    public ConfigException(List<String> errors) {
        super("Invalid rate limit policy (" + errors.size() + " problem" + (errors.size() == 1 ? "" : "s") + "):\n  - "
                + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
