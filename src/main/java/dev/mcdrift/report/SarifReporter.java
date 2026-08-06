package dev.mcdrift.report;

import com.google.gson.GsonBuilder;
import dev.mcdrift.core.Finding;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SARIF 2.1.0 output, for GitHub Code Scanning and other standard tooling.
 *
 * <p>SARIF locations are file-and-line based, but mcdrift reads bytecode. The file name
 * comes from the class file's {@code SourceFile} attribute and the directory from the
 * package, leaving only the source root to be supplied by the caller. A class compiled
 * without that attribute gets no location at all rather than a guessed one — GitHub
 * discards results whose path is not in the repository, so a wrong path loses the
 * finding entirely.
 *
 * <p>When debug info is missing there is no line either, and the finding is anchored to
 * line 1 with the method named in the message instead.
 */
public final class SarifReporter {

    private static final String SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";

    private final List<Rule> rules;
    private final String sourceRoot;

    public SarifReporter(List<Rule> rules) {
        this(rules, "src/main/java");
    }

    public SarifReporter(List<Rule> rules, String sourceRoot) {
        this.rules = rules;
        this.sourceRoot = sourceRoot;
    }

    public void report(List<ScanResult> results, PrintStream out) {
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "mcdrift");
        driver.put("informationUri", "https://github.com/kitsune-de/mcdrift");
        driver.put("version", version());
        driver.put("rules", rules.stream().map(SarifReporter::ruleDescriptor).toList());

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("driver", driver);

        List<Map<String, Object>> sarifResults = new ArrayList<>();
        for (ScanResult result : results) {
            for (Finding f : result.findings()) {
                sarifResults.add(toResult(f, result, sourceRoot));
            }
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", tool);
        run.put("results", sarifResults);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", SCHEMA);
        root.put("version", "2.1.0");
        root.put("runs", List.of(run));

        out.println(new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    private static Map<String, Object> ruleDescriptor(Rule rule) {
        Map<String, Object> shortDesc = new LinkedHashMap<>();
        shortDesc.put("text", rule.description());

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", rule.id());
        descriptor.put("name", rule.id());
        descriptor.put("shortDescription", shortDesc);
        return descriptor;
    }

    private static Map<String, Object> toResult(Finding f, ScanResult scan, String sourceRoot) {
        Map<String, Object> message = new LinkedHashMap<>();
        String text = f.message();
        if (f.line() == Finding.NO_LINE && f.methodName() != null) {
            text += " (in " + f.methodName() + ")";
        }
        if (f.hint() != null) {
            text += " " + f.hint();
        }
        message.put("text", text);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", f.ruleId());
        result.put("level", sarifLevel(f.severity()));
        result.put("message", message);

        // Only emit a location when the class file actually recorded a source file.
        // A guessed path that does not exist in the repository makes GitHub drop the
        // result silently, which is worse than reporting it without a location.
        String path = f.sourcePath(sourceRoot);
        if (path != null) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("uri", path);

            Map<String, Object> region = new LinkedHashMap<>();
            region.put("startLine", f.line() == Finding.NO_LINE ? 1 : f.line());

            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", artifact);
            physical.put("region", region);

            Map<String, Object> location = new LinkedHashMap<>();
            location.put("physicalLocation", physical);
            result.put("locations", List.of(location));
        } else {
            result.put("locations", List.of());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("jar", scan.jar().getFileName().toString());
        properties.put("target", scan.target().toString());
        properties.put("class", f.className().replace('/', '.'));
        result.put("properties", properties);

        return result;
    }

    private static String sarifLevel(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARN -> "warning";
            case INFO -> "note";
        };
    }

    private static String version() {
        String v = SarifReporter.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
