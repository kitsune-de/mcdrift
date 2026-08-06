package dev.mcdrift.report;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;

import java.io.PrintStream;
import java.util.List;

/**
 * GitHub Actions workflow commands, so findings appear inline on the PR diff.
 *
 * <p>Emits {@code ::error file=...,line=...::message}. Only findings on lines GitHub
 * can map to changed source show up in the diff; the rest still appear in the job log
 * and the run summary.
 */
public final class GithubReporter {

    private final String sourceRoot;

    public GithubReporter() {
        this("src/main/java");
    }

    public GithubReporter(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    public void report(List<ScanResult> results, PrintStream out) {
        for (ScanResult result : results) {
            for (Finding f : result.findings()) {
                out.println(command(f, sourceRoot));
            }
        }
        out.println(summaryLine(results));
    }

    private static String command(Finding f, String sourceRoot) {
        String level = switch (f.severity()) {
            case ERROR -> "error";
            case WARN -> "warning";
            case INFO -> "notice";
        };

        String title = "mcdrift/" + f.ruleId();

        StringBuilder message = new StringBuilder(f.message());
        if (f.line() == Finding.NO_LINE && f.methodName() != null) {
            message.append(" (in ").append(f.methodName()).append(")");
        }
        if (f.hint() != null) {
            message.append(" — ").append(f.hint());
        }

        // Without a real source file, emit an annotation with no file: GitHub shows it
        // on the run instead of attaching it to a path that may not exist. The class
        // name goes in the message so the finding is still actionable.
        String file = f.sourcePath(sourceRoot);
        if (file == null) {
            message.append(" [").append(f.className().replace('/', '.')).append("]");
            return "::" + level + " title=" + escapeProperty(title)
                    + "::" + escapeData(message.toString());
        }

        return "::" + level
                + " file=" + file
                + ",line=" + (f.line() == Finding.NO_LINE ? 1 : f.line())
                + ",title=" + escapeProperty(title)
                + "::" + escapeData(message.toString());
    }

    private static String summaryLine(List<ScanResult> results) {
        long errors = 0;
        long warnings = 0;
        for (ScanResult r : results) {
            errors += r.count(Severity.ERROR);
            warnings += r.count(Severity.WARN);
        }
        if (errors == 0 && warnings == 0) {
            return "::notice::mcdrift found no blocking issues";
        }
        return "::notice::mcdrift found " + errors + " error(s) and "
                + warnings + " warning(s)";
    }

    /**
     * Workflow commands are newline-delimited and use {@code ::} as a separator, so
     * those characters have to be escaped or the command is truncated.
     */
    private static String escapeData(String s) {
        return s.replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A");
    }

    private static String escapeProperty(String s) {
        return escapeData(s).replace(":", "%3A").replace(",", "%2C");
    }
}
