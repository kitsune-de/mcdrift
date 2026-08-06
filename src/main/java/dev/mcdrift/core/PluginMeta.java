package dev.mcdrift.core;

/**
 * The few fields we care about from plugin.yml / paper-plugin.yml.
 *
 * <p>Deliberately hand-parsed rather than pulling in a YAML library: we only need
 * three top-level scalars, and plugin.yml is frequently malformed in ways a strict
 * parser rejects outright. A tool that refuses to analyse a jar because its manifest
 * has a stray tab is useless.
 */
public record PluginMeta(String name, String version, String apiVersion, boolean paperPlugin) {

    public static PluginMeta unknown() {
        return new PluginMeta(null, null, null, false);
    }

    public String displayName() {
        return name == null ? "(unknown plugin)" : name;
    }
}
