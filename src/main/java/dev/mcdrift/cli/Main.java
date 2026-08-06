package dev.mcdrift.cli;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.JarScanner;
import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.Ruleset;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;
import dev.mcdrift.core.Suppressions;
import dev.mcdrift.report.GithubReporter;
import dev.mcdrift.report.JsonReporter;
import dev.mcdrift.report.SarifReporter;
import dev.mcdrift.report.SummaryReporter;
import dev.mcdrift.report.TextReporter;
import dev.mcdrift.rules.RuleRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
        name = "mcdrift",
        mixinStandardHelpOptions = true,
        version = "mcdrift 1.0.0",
        sortOptions = false,
        description = "Finds Minecraft plugin code that breaks on newer server versions, "
                + "by reading the compiled jar. No source required.")
public final class Main implements Callable<Integer> {

    private static final int EXIT_FINDINGS = 1;
    private static final int EXIT_USAGE = 2;

    @Parameters(
            arity = "0..*",
            paramLabel = "<path>",
            description = "Plugin jar(s), or a directory to scan for jars.")
    private List<Path> paths = new ArrayList<>();

    @Option(
            names = {"-t", "--target"},
            paramLabel = "<version>",
            description = "Minecraft version to check against (default: ${DEFAULT-VALUE}).")
    private String target = "26.1";

    @Option(
            names = "--format",
            paramLabel = "<format>",
            description = "text, summary, json, sarif or github (default: ${DEFAULT-VALUE}).")
    private String format = "text";

    @Option(
            names = "--ruleset",
            paramLabel = "<file>",
            description = "Deprecation ruleset JSON. Defaults to the bundled one.")
    private Path rulesetPath;

    @Option(
            names = "--disable",
            paramLabel = "<rule>",
            split = ",",
            description = "Rule ids to skip, comma-separated.")
    private Set<String> disabled = Set.of();

    @Option(
            names = "--min-severity",
            paramLabel = "<error|warn|info>",
            description = "Hide findings below this severity (default: ${DEFAULT-VALUE}).")
    private String minSeverity = "info";

    @Option(
            names = "--fail-on",
            paramLabel = "<error|warn|never>",
            description = "Exit non-zero at this severity or worse (default: ${DEFAULT-VALUE}).")
    private String failOn = "error";

    @Option(
            names = "--ignore-file",
            paramLabel = "<file>",
            description = "Suppression list. Defaults to .mcdriftignore if present.")
    private Path ignoreFile;

    @Option(
            names = "--no-ignore-file",
            description = "Ignore any .mcdriftignore that would otherwise be picked up.")
    private boolean noIgnoreFile;

    @Option(
            names = "--source-root",
            paramLabel = "<dir>",
            description = "Source directory prefix for sarif/github paths "
                    + "(default: ${DEFAULT-VALUE}).")
    private String sourceRoot = "src/main/java";

    @Option(
            names = "--stats",
            description = "Print machine-readable counts (key=value) instead of a report.")
    private boolean stats;

    @Option(names = "--list-rules", description = "Print the available rules and exit.")
    private boolean listRules;

    @Option(names = "--no-color", description = "Disable coloured output.")
    private boolean noColor;

    @Override
    public Integer call() {
        if (listRules) {
            for (Rule rule : RuleRegistry.all()) {
                System.out.printf("  %-18s %s%n", rule.id(), rule.description());
            }
            return 0;
        }

        if (paths.isEmpty()) {
            System.err.println("mcdrift: no jar or directory given.");
            System.err.println("Usage: mcdrift <plugin.jar|plugins/> [--target 26.1]  "
                    + "(--help for all options)");
            return EXIT_USAGE;
        }

        McVersion targetVersion;
        try {
            targetVersion = McVersion.parse(target);
        } catch (IllegalArgumentException e) {
            System.err.println("mcdrift: bad --target: " + e.getMessage());
            return EXIT_USAGE;
        }

        Set<String> known = RuleRegistry.knownIds();
        for (String id : disabled) {
            if (!known.contains(id)) {
                System.err.println("mcdrift: unknown rule in --disable: " + id
                        + " (see --list-rules)");
                return EXIT_USAGE;
            }
        }

        Format outputFormat = Format.parse(format);
        if (outputFormat == null) {
            System.err.println("mcdrift: --format must be one of "
                    + "text, summary, json, sarif, github");
            return EXIT_USAGE;
        }
        if (!List.of("error", "warn", "never").contains(failOn)) {
            System.err.println("mcdrift: --fail-on must be 'error', 'warn' or 'never'");
            return EXIT_USAGE;
        }
        Severity floor = parseSeverity(minSeverity);
        if (floor == null) {
            System.err.println("mcdrift: --min-severity must be 'error', 'warn' or 'info'");
            return EXIT_USAGE;
        }

        Ruleset ruleset;
        try {
            ruleset = rulesetPath == null ? Ruleset.bundled() : Ruleset.load(rulesetPath);
        } catch (Exception e) {
            System.err.println("mcdrift: could not load ruleset: " + e.getMessage());
            return EXIT_USAGE;
        }

        Suppressions suppressions;
        try {
            suppressions = resolveSuppressions();
        } catch (Exception e) {
            System.err.println("mcdrift: " + e.getMessage());
            return EXIT_USAGE;
        }

        List<Path> jars;
        try {
            jars = collectJars();
        } catch (Exception e) {
            System.err.println("mcdrift: " + e.getMessage());
            return EXIT_USAGE;
        }
        if (jars.isEmpty()) {
            System.err.println("mcdrift: no .jar files found in " + paths.getFirst());
            return EXIT_USAGE;
        }

        JarScanner scanner = new JarScanner(RuleRegistry.allExcept(disabled));
        List<ScanResult> results = new ArrayList<>();

        for (Path jar : jars) {
            try {
                results.add(filter(scanner.scan(jar, targetVersion, ruleset),
                        suppressions, floor));
            } catch (java.util.zip.ZipException e) {
                // Scanning a directory must not stop because one file is not a jar.
                if (jars.size() == 1) {
                    System.err.println("mcdrift: " + jar.getFileName()
                            + " is not a valid jar (could not read it as a zip archive).");
                    return EXIT_USAGE;
                }
                System.err.println("mcdrift: skipping " + jar.getFileName()
                        + " (not a valid jar)");
            } catch (Exception e) {
                if (jars.size() == 1) {
                    System.err.println("mcdrift: could not read " + jar.getFileName()
                            + ": " + e.getMessage());
                    return EXIT_USAGE;
                }
                System.err.println("mcdrift: skipping " + jar.getFileName()
                        + ": " + e.getMessage());
            }
        }
        if (results.isEmpty()) {
            System.err.println("mcdrift: nothing could be scanned.");
            return EXIT_USAGE;
        }

        if (stats) {
            // Shell-parseable aggregate, so CI does not have to grep JSON.
            long errors = results.stream().mapToLong(r -> r.count(Severity.ERROR)).sum();
            long warnings = results.stream().mapToLong(r -> r.count(Severity.WARN)).sum();
            long notes = results.stream().mapToLong(r -> r.count(Severity.INFO)).sum();
            System.out.println("errors=" + errors);
            System.out.println("warnings=" + warnings);
            System.out.println("notes=" + notes);
            System.out.println("jars=" + results.size());
            System.out.println("target=" + targetVersion);
        } else {
            emit(results, outputFormat, targetVersion);
        }

        boolean shouldFail = results.stream().anyMatch(r -> switch (failOn) {
            case "error" -> r.hasBlockingIssues();
            case "warn" -> !r.findings().isEmpty();
            default -> false;
        });
        return shouldFail ? EXIT_FINDINGS : 0;
    }

    private void emit(List<ScanResult> results, Format outputFormat, McVersion targetVersion) {
        boolean colour = !noColor && System.console() != null;
        switch (outputFormat) {
            case SUMMARY -> new SummaryReporter(colour).report(results, targetVersion, System.out);
            case JSON -> new JsonReporter().reportAll(results, System.out);
            case SARIF -> new SarifReporter(RuleRegistry.allExcept(disabled), sourceRoot)
                    .report(results, System.out);
            case GITHUB -> new GithubReporter(sourceRoot).report(results, System.out);
            case TEXT -> {
                // A directory scan defaults to the table; printing every finding from
                // forty plugins is not a report anyone reads.
                if (results.size() > 1 && isDirectoryScan()) {
                    new SummaryReporter(colour).report(results, targetVersion, System.out);
                } else {
                    TextReporter text = new TextReporter(colour);
                    results.forEach(r -> text.report(r, System.out));
                    if (results.size() > 1) {
                        System.out.println("Scanned " + results.size()
                                + " jars against Minecraft " + targetVersion + ".");
                    }
                }
            }
        }
    }

    private boolean isDirectoryScan() {
        return paths.size() == 1 && Files.isDirectory(paths.getFirst());
    }

    /** Applies the ignore list and severity floor to a scan. */
    private static ScanResult filter(ScanResult result, Suppressions suppressions, Severity floor) {
        List<Finding> kept = result.findings().stream()
                .filter(f -> !suppressions.suppresses(f))
                .filter(f -> f.severity().compareTo(floor) <= 0)
                .toList();
        if (kept.size() == result.findings().size()) {
            return result;
        }
        return new ScanResult(result.jar(), result.meta(), result.target(), kept,
                result.classesScanned(), result.classesVendored(), result.classesUnreadable());
    }

    private Suppressions resolveSuppressions() throws Exception {
        if (noIgnoreFile) {
            return Suppressions.none();
        }
        if (ignoreFile != null) {
            if (!Files.isRegularFile(ignoreFile)) {
                throw new IllegalArgumentException("ignore file not found: " + ignoreFile);
            }
            return Suppressions.load(ignoreFile);
        }
        return Suppressions.loadDefault(Path.of("."));
    }

    /** Expands directories into the jars they contain. */
    private List<Path> collectJars() throws Exception {
        List<Path> jars = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString()
                                    .toLowerCase(Locale.ROOT).endsWith(".jar"))
                            // Server owners keep disabled plugins alongside live ones.
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .sorted()
                            .forEach(jars::add);
                } catch (UncheckedIOException e) {
                    throw new IllegalArgumentException("could not read directory " + path);
                }
            } else if (Files.isRegularFile(path)) {
                jars.add(path);
            } else {
                throw new IllegalArgumentException("no such file or directory: " + path);
            }
        }
        return jars;
    }

    private static Severity parseSeverity(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "error" -> Severity.ERROR;
            case "warn", "warning" -> Severity.WARN;
            case "info", "note" -> Severity.INFO;
            default -> null;
        };
    }

    private enum Format {
        TEXT, SUMMARY, JSON, SARIF, GITHUB;

        static Format parse(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "text" -> TEXT;
                case "summary" -> SUMMARY;
                case "json" -> JSON;
                case "sarif" -> SARIF;
                case "github" -> GITHUB;
                default -> null;
            };
        }
    }

    public static void main(String[] args) {
        // Windows consoles default to a legacy code page, which turns any non-ASCII
        // output into replacement characters. Everything printed is ASCII, but the
        // plugin names and messages inside a scanned jar may not be.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),
                true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),
                true, StandardCharsets.UTF_8));
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
