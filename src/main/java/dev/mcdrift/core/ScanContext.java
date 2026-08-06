package dev.mcdrift.core;

/**
 * Everything a rule needs to know beyond the class it is looking at.
 */
public record ScanContext(McVersion target, PluginMeta meta, Ruleset ruleset) {

    /** True once the server ships unobfuscated jars and the Paper remapper is gone. */
    public boolean targetIsDeobfuscated() {
        return target.isAtLeast(McVersion.FIRST_CALVER);
    }

    /** True once dimension folders moved under {@code world/dimensions/}. */
    public boolean targetHasNewWorldLayout() {
        return target.isAtLeast(McVersion.FIRST_CALVER);
    }
}
