package dev.mcdrift.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything one scan produced.
 */
public record ScanResult(
        Path jar,
        PluginMeta meta,
        McVersion target,
        List<Finding> findings,
        int classesScanned,
        int classesVendored,
        int classesUnreadable
) {
    /** Classes not analysed, for any reason. */
    public int classesSkipped() {
        return classesVendored + classesUnreadable;
    }

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    public boolean hasBlockingIssues() {
        return count(Severity.ERROR) > 0;
    }

    /** Findings grouped by rule, for the summary line. */
    public Map<String, Long> byRule() {
        Map<String, Long> out = new TreeMap<>();
        for (Finding f : findings) {
            out.merge(f.ruleId(), 1L, Long::sum);
        }
        return out;
    }
}
