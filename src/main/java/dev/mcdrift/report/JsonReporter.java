package dev.mcdrift.report;

import com.google.gson.GsonBuilder;
import dev.mcdrift.core.Finding;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable report, for CI and for the GitHub Action that wraps this later.
 *
 * <p>The shape is deliberately flat and stable — anything consuming it should not have
 * to care which rule produced a finding.
 */
public final class JsonReporter {

    /**
     * Writes every scan as one document.
     *
     * <p>A single jar produces the scan object directly, so the common case stays easy
     * to read and to query. Several jars produce {@code {"scans": [...]}} rather than
     * concatenated objects, which would not parse.
     */
    public void reportAll(java.util.List<ScanResult> results, PrintStream out) {
        if (results.size() == 1) {
            report(results.getFirst(), out);
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "mcdrift");
        root.put("scans", results.stream().map(JsonReporter::toMap).toList());
        out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root));
    }

    public void report(ScanResult result, PrintStream out) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "mcdrift");
        root.putAll(toMap(result));
        out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root));
    }

    private static Map<String, Object> toMap(ScanResult result) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("jar", result.jar().getFileName().toString());
        root.put("target", result.target().toString());

        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("name", result.meta().name());
        plugin.put("version", result.meta().version());
        plugin.put("apiVersion", result.meta().apiVersion());
        plugin.put("paperPlugin", result.meta().paperPlugin());
        root.put("plugin", plugin);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("errors", result.count(Severity.ERROR));
        summary.put("warnings", result.count(Severity.WARN));
        summary.put("notes", result.count(Severity.INFO));
        summary.put("classesScanned", result.classesScanned());
        summary.put("classesVendored", result.classesVendored());
        summary.put("classesUnreadable", result.classesUnreadable());
        root.put("summary", summary);

        List<Map<String, Object>> findings = result.findings().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", f.ruleId());
            m.put("severity", f.severity().name());
            m.put("class", f.className().replace('/', '.'));
            m.put("method", f.methodName());
            m.put("line", f.line() == Finding.NO_LINE ? null : f.line());
            m.put("message", f.message());
            m.put("hint", f.hint());
            return m;
        }).toList();
        root.put("findings", findings);
        return root;
    }
}
