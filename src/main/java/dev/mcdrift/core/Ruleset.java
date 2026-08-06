package dev.mcdrift.core;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The deprecated/removed API signatures we check calls against.
 *
 * <p>Kept as data rather than code so it can be regenerated from the Spigot javadoc
 * and shipped independently of a tool release. {@code schema} guards against a newer
 * ruleset file being fed to an older binary.
 */
public final class Ruleset {

    public static final int SUPPORTED_SCHEMA = 1;

    /** owner+name+descriptor -> entry. Descriptor may be "*" to match any overload. */
    private final Map<String, List<Entry>> byOwnerAndName = new HashMap<>();
    private final String rulesetVersion;

    private Ruleset(String rulesetVersion, List<Entry> entries) {
        this.rulesetVersion = rulesetVersion;
        for (Entry e : entries) {
            byOwnerAndName.computeIfAbsent(key(e.owner, e.name), k -> new ArrayList<>()).add(e);
        }
    }

    public String version() {
        return rulesetVersion;
    }

    public int size() {
        return byOwnerAndName.values().stream().mapToInt(List::size).sum();
    }

    private static String key(String owner, String name) {
        return owner + "#" + name;
    }

    /**
     * Look up a method call. Returns null when the call is fine.
     *
     * <p>Three descriptor forms are supported, matched most-specific first so a
     * ruleset can deprecate one overload without flagging its replacement:
     *
     * <ul>
     *   <li>a full descriptor, {@code (I)Ljava/lang/String;} — exact match;
     *   <li>a parameter-list prefix, {@code (I)} — matches any return type. The
     *       generated ruleset uses this because the javadoc anchor gives parameter
     *       types but not the return type. It is still unambiguous: two overloads
     *       cannot share a parameter list;
     *   <li>{@code "*"} — any overload.
     * </ul>
     */
    public Entry lookup(String owner, String name, String descriptor) {
        List<Entry> candidates = byOwnerAndName.get(key(owner, name));
        if (candidates == null) {
            return null;
        }
        Entry prefixMatch = null;
        Entry wildcard = null;
        for (Entry e : candidates) {
            if (descriptor.equals(e.descriptor)) {
                return e;
            }
            if ("*".equals(e.descriptor)) {
                wildcard = e;
            } else if (isParameterPrefix(e.descriptor) && descriptor.startsWith(e.descriptor)) {
                prefixMatch = e;
            }
        }
        return prefixMatch != null ? prefixMatch : wildcard;
    }

    /** True for a descriptor that stops at the closing paren, e.g. {@code (I)}. */
    private static boolean isParameterPrefix(String descriptor) {
        return descriptor.endsWith(")");
    }

    public static Ruleset load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.toString());
        }
    }

    public static Ruleset bundled() throws IOException {
        try (InputStream in = Ruleset.class.getResourceAsStream("/ruleset.json")) {
            if (in == null) {
                throw new IOException("bundled ruleset.json is missing from the jar");
            }
            return parse(in, "bundled ruleset.json");
        }
    }

    private static Ruleset parse(InputStream in, String source) throws IOException {
        File file;
        try {
            file = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), File.class);
        } catch (JsonSyntaxException e) {
            throw new IOException(source + " is not valid JSON: " + e.getMessage(), e);
        }
        if (file == null) {
            throw new IOException(source + " is empty");
        }
        if (file.schema != SUPPORTED_SCHEMA) {
            throw new IOException(source + " uses schema " + file.schema
                    + " but this build of mcdrift understands schema " + SUPPORTED_SCHEMA
                    + ". Update mcdrift, or pass an older ruleset with --ruleset.");
        }
        List<Entry> entries = file.entries == null ? List.of() : file.entries;
        for (Entry e : entries) {
            if (e.owner == null || e.name == null) {
                throw new IOException(source + " has an entry missing 'owner' or 'name'");
            }
            if (e.descriptor == null) {
                e.descriptor = "*";
            }
            if (e.severity == null) {
                e.severity = Severity.WARN;
            }
        }
        return new Ruleset(file.rulesetVersion == null ? "unknown" : file.rulesetVersion, entries);
    }

    /** Top-level JSON shape. */
    private static final class File {
        int schema;
        String rulesetVersion;
        List<Entry> entries;
    }

    /** One deprecated or removed member. */
    public static final class Entry {
        public String owner;       // internal name, e.g. org/bukkit/Bukkit
        public String name;        // method name
        public String descriptor;  // JVM descriptor, or "*" for any overload
        public Severity severity;
        public String since;       // version it was deprecated in
        public String replacement; // what to use instead

        /**
         * The replacement as a complete sentence.
         *
         * <p>Descriptions come from the Spigot javadoc and arrive in several shapes: a
         * bare noun phrase ({@code "Magic value"}), an imperative clause
         * ({@code "use Registry.get(...)"}), or a cross reference
         * ({@code "See BanEntry.getBanTarget()"}). Rendering all three as one sentence
         * means capitalising the first letter and adding a full stop, but never
         * appending "instead" — the javadoc already says so where it applies.
         */
        public String replacementSentence() {
            if (replacement == null || replacement.isBlank()) {
                return null;
            }
            String r = replacement.trim().replaceAll("\\s+", " ");
            r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
            return r.endsWith(".") ? r : r + ".";
        }
    }
}
