package dev.mcdrift;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.mcdrift.core.Finding;
import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.PluginMeta;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;
import dev.mcdrift.report.GithubReporter;
import dev.mcdrift.report.SarifReporter;
import dev.mcdrift.rules.RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine-readable formats are consumed by other tools, so their shape matters as
 * much as their content.
 */
class ReportFormatTest {

    private static ScanResult sampleResult(List<Finding> findings) {
        return new ScanResult(
                Path.of("MyPlugin.jar"),
                new PluginMeta("MyPlugin", "1.0", "1.21", false),
                McVersion.parse("26.1"),
                findings,
                10, 2, 0);
    }

    private static String capture(java.util.function.Consumer<PrintStream> body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            body.accept(out);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("SARIF output is valid 2.1.0 with one result per finding")
    void sarifShape() {
        List<Finding> findings = List.of(
                new Finding("world-paths", Severity.ERROR, "com/example/A", "run", 12,
                        "hardcoded path", "use the API", "A.java"),
                new Finding("deprecated-api", Severity.INFO, "com/example/B", "go",
                        Finding.NO_LINE, "deprecated call", "use something else", "B.java"));

        String json = capture(out ->
                new SarifReporter(RuleRegistry.all()).report(List.of(sampleResult(findings)), out));

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        assertEquals("2.1.0", root.get("version").getAsString());

        JsonObject run = root.getAsJsonArray("runs").get(0).getAsJsonObject();
        assertEquals("mcdrift",
                run.getAsJsonObject("tool").getAsJsonObject("driver").get("name").getAsString());
        assertEquals(2, run.getAsJsonArray("results").size());

        JsonObject first = run.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("world-paths", first.get("ruleId").getAsString());
        assertEquals("error", first.get("level").getAsString());
        assertEquals(12, first.getAsJsonArray("locations").get(0).getAsJsonObject()
                .getAsJsonObject("physicalLocation").getAsJsonObject("region")
                .get("startLine").getAsInt());

        // A finding with no debug info still needs a valid location: SARIF has no way
        // to express "line unknown", and line 0 is invalid.
        JsonObject second = run.getAsJsonArray("results").get(1).getAsJsonObject();
        assertEquals(1, second.getAsJsonArray("locations").get(0).getAsJsonObject()
                .getAsJsonObject("physicalLocation").getAsJsonObject("region")
                .get("startLine").getAsInt());
    }

    @Test
    @DisplayName("SARIF uses the recorded SourceFile, so inner classes resolve correctly")
    void sarifInnerClassPath() {
        // javac records "Outer.java" for Outer$Inner, so the real file falls out of the
        // attribute rather than having to be guessed from the class name.
        List<Finding> findings = List.of(
                new Finding("world-paths", Severity.ERROR, "com/example/Outer$Inner", "run", 5,
                        "msg", "hint", "Outer.java"));
        String json = capture(out ->
                new SarifReporter(RuleRegistry.all()).report(List.of(sampleResult(findings)), out));

        assertTrue(json.contains("src/main/java/com/example/Outer.java"),
                "inner class must resolve to the outer file, which is the one that exists");
        assertFalse(json.contains("Outer$Inner.java"));
    }

    @Test
    @DisplayName("a class whose file name differs from its class name still resolves")
    void sarifNonMatchingFileName() {
        // A package-private class declared inside another file: the class name alone
        // would produce a path that does not exist.
        List<Finding> findings = List.of(
                new Finding("world-paths", Severity.ERROR, "com/example/Helper", "run", 5,
                        "msg", "hint", "PublicClass.java"));
        String json = capture(out ->
                new SarifReporter(RuleRegistry.all()).report(List.of(sampleResult(findings)), out));

        assertTrue(json.contains("src/main/java/com/example/PublicClass.java"));
        assertFalse(json.contains("Helper.java"));
    }

    @Test
    @DisplayName("no SourceFile means no location, rather than an invented path")
    void sarifOmitsUnknownLocation() {
        List<Finding> findings = List.of(
                new Finding("world-paths", Severity.ERROR, "com/example/Obfuscated", "run", 5,
                        "msg", "hint"));
        String json = capture(out ->
                new SarifReporter(RuleRegistry.all()).report(List.of(sampleResult(findings)), out));

        JsonObject result = new Gson().fromJson(json, JsonObject.class)
                .getAsJsonArray("runs").get(0).getAsJsonObject()
                .getAsJsonArray("results").get(0).getAsJsonObject();
        // GitHub silently drops results pointing at files that do not exist, so an
        // empty location list is the honest encoding.
        assertEquals(0, result.getAsJsonArray("locations").size());
        assertEquals("com.example.Obfuscated",
                result.getAsJsonObject("properties").get("class").getAsString());
    }

    @Test
    @DisplayName("the source root is configurable for non-standard layouts")
    void sarifCustomSourceRoot() {
        List<Finding> findings = List.of(
                new Finding("world-paths", Severity.ERROR, "com/example/A", "run", 5,
                        "msg", "hint", "A.java"));
        String json = capture(out -> new SarifReporter(RuleRegistry.all(), "core/src")
                .report(List.of(sampleResult(findings)), out));

        assertTrue(json.contains("core/src/com/example/A.java"), json);
    }

    @Test
    @DisplayName("GitHub annotations escape the characters that would truncate them")
    void githubEscaping() {
        List<Finding> findings = List.of(
                new Finding("version-parsing", Severity.ERROR, "com/example/A", "run", 7,
                        "message with\nnewline and 100% signs", "hint: with colon, and comma",
                        "A.java"));

        String output = capture(out ->
                new GithubReporter().report(List.of(sampleResult(findings)), out));

        String command = output.lines().findFirst().orElseThrow();
        assertTrue(command.startsWith("::error "), "expected an error annotation: " + command);
        // A raw newline would end the workflow command early and lose the rest.
        assertFalse(command.contains("\nnewline"));
        assertTrue(command.contains("%0A"), "newline should be escaped");
        assertTrue(command.contains("%25"), "percent should be escaped");
        assertTrue(command.contains("line=7"));
    }

    @Test
    @DisplayName("multi-jar JSON is one parseable document, not concatenated objects")
    void jsonMultiJarIsOneDocument() {
        ScanResult a = sampleResult(List.of());
        ScanResult b = new ScanResult(Path.of("Other.jar"),
                new PluginMeta("Other", "2.0", "1.21", false),
                McVersion.parse("26.1"), List.of(), 5, 0, 0);

        String json = capture(out -> new dev.mcdrift.report.JsonReporter()
                .reportAll(List.of(a, b), out));

        // Printing one object per jar would produce `{...}{...}`, which no JSON parser
        // accepts — piping a directory scan into jq would fail.
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        assertEquals(2, root.getAsJsonArray("scans").size());
    }

    @Test
    @DisplayName("single-jar JSON keeps the scan at the top level")
    void jsonSingleJarIsFlat() {
        String json = capture(out -> new dev.mcdrift.report.JsonReporter()
                .reportAll(List.of(sampleResult(List.of())), out));

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        assertEquals("MyPlugin.jar", root.get("jar").getAsString());
        assertFalse(root.has("scans"), "the common case should not be wrapped");
    }

    @Test
    @DisplayName("GitHub severities map onto the three annotation levels")
    void githubSeverityMapping() {
        List<Finding> findings = List.of(
                new Finding("a", Severity.ERROR, "com/example/A", "m", 1, "e", null, "A.java"),
                new Finding("b", Severity.WARN, "com/example/B", "m", 2, "w", null, "B.java"),
                new Finding("c", Severity.INFO, "com/example/C", "m", 3, "i", null, "C.java"));

        String output = capture(out ->
                new GithubReporter().report(List.of(sampleResult(findings)), out));

        assertTrue(output.contains("::error file="));
        assertTrue(output.contains("::warning file="));
        assertTrue(output.contains("::notice file="));
    }

    @Test
    @DisplayName("a finding with no source file still produces a usable annotation")
    void githubWithoutSourceFile() {
        List<Finding> findings = List.of(
                new Finding("legacy-mappings", Severity.ERROR, "com/example/Obf", "m", 5,
                        "bad mapping", null));

        String output = capture(out ->
                new GithubReporter().report(List.of(sampleResult(findings)), out));
        String command = output.lines().findFirst().orElseThrow();

        // No file= key, because pointing at a path that may not exist would make the
        // annotation vanish. The class name carries the location instead.
        assertFalse(command.contains("file="), command);
        assertTrue(command.contains("com.example.Obf"), command);
    }
}
