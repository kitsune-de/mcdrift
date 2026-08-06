package dev.mcdrift.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rules for findings the user has chosen not to see.
 *
 * <p>A linter that reports a thousand pre-existing problems on first run gets switched
 * off, so adoption on an existing codebase has to be possible without fixing everything
 * first. Each line of an ignore file is one pattern:
 *
 * <pre>
 * # comment
 * deprecated-api                       ignore a rule everywhere
 * com.example.legacy.*                 ignore a class or package
 * deprecated-api:com.example.Old       ignore one rule in one place
 * </pre>
 *
 * <p>Class patterns use {@code *} as a wildcard; everything else is literal.
 */
public final class Suppressions {

    public static final String DEFAULT_FILE = ".mcdriftignore";

    private final List<Entry> entries;

    private Suppressions(List<Entry> entries) {
        this.entries = entries;
    }

    public static Suppressions none() {
        return new Suppressions(List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** True if this finding should be hidden. */
    public boolean suppresses(Finding finding) {
        String className = finding.className().replace('/', '.');
        for (Entry e : entries) {
            boolean ruleMatches = e.rule == null || e.rule.equals(finding.ruleId());
            boolean classMatches = e.classPattern == null
                    || e.classPattern.matcher(className).matches();
            if (ruleMatches && classMatches) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read an ignore file.
     *
     * @throws IOException if the file cannot be read, or a line is malformed
     */
    public static Suppressions load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Entry> parsed = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : lines) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                parsed.add(Entry.parse(line));
            } catch (IllegalArgumentException e) {
                throw new IOException(path.getFileName() + ":" + lineNumber + ": "
                        + e.getMessage());
            }
        }
        return new Suppressions(parsed);
    }

    /** Load the default file from a directory, or an empty set if it is absent. */
    public static Suppressions loadDefault(Path directory) throws IOException {
        Path candidate = directory.resolve(DEFAULT_FILE);
        return Files.isRegularFile(candidate) ? load(candidate) : none();
    }

    private record Entry(String rule, Pattern classPattern) {

        static Entry parse(String line) {
            String rulePart = null;
            String classPart = null;

            int colon = line.indexOf(':');
            if (colon >= 0) {
                rulePart = line.substring(0, colon).trim();
                classPart = line.substring(colon + 1).trim();
                if (rulePart.isEmpty() || classPart.isEmpty()) {
                    throw new IllegalArgumentException(
                            "expected 'rule:class-pattern', got: " + line);
                }
            } else if (line.contains(".") || line.contains("*")) {
                classPart = line;
            } else {
                rulePart = line;
            }

            if (rulePart != null && !dev.mcdrift.rules.RuleRegistry.knownIds().contains(rulePart)) {
                throw new IllegalArgumentException("unknown rule '" + rulePart + "'");
            }

            return new Entry(rulePart, classPart == null ? null : globToPattern(classPart));
        }

        /** Converts a glob such as {@code com.example.*} to a regex. */
        static Pattern globToPattern(String glob) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(regex.toString());
        }
    }
}
