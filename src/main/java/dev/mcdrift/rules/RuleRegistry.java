package dev.mcdrift.rules;

import dev.mcdrift.core.Rule;

import java.util.List;
import java.util.Set;

/**
 * The set of rules mcdrift ships with.
 */
public final class RuleRegistry {

    private RuleRegistry() {
    }

    public static List<Rule> all() {
        return List.of(
                new VersionParsingRule(),
                new LegacyMappingsRule(),
                new WorldPathRule(),
                new BytecodeLevelRule(),
                new DeprecatedApiRule());
    }

    public static List<Rule> allExcept(Set<String> disabledIds) {
        return all().stream().filter(r -> !disabledIds.contains(r.id())).toList();
    }

    public static Set<String> knownIds() {
        return all().stream().map(Rule::id).collect(java.util.stream.Collectors.toSet());
    }
}
