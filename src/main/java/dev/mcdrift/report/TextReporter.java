package dev.mcdrift.report;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;

import java.io.PrintStream;
import java.util.Map;

/**
 * Human-readable report.
 *
 * <p>Findings are grouped by rule rather than by class: a plugin with the same mistake
 * in forty places should read as one problem to fix, not forty.
 */
public final class TextReporter {

    private final boolean colour;

    public TextReporter(boolean colour) {
        this.colour = colour;
    }

    private static final String RESET = "[0m";
    private static final String RED = "[31m";
    private static final String YELLOW = "[33m";
    private static final String BLUE = "[34m";
    private static final String DIM = "[2m";
    private static final String BOLD = "[1m";

    public void report(ScanResult result, PrintStream out) {
        out.println();
        out.println(paint(BOLD, result.meta().displayName()
                + (result.meta().version() == null ? "" : " " + result.meta().version()))
                + paint(DIM, "  (" + result.jar().getFileName() + ")"));
        // Name the reason for skipped classes: a bare count reads as the tool having
        // given up, when shaded libraries are deliberately none of the author's business.
        StringBuilder skipped = new StringBuilder();
        if (result.classesVendored() > 0) {
            skipped.append(", ").append(result.classesVendored())
                    .append(" shaded library classes ignored");
        }
        if (result.classesUnreadable() > 0) {
            skipped.append(", ").append(result.classesUnreadable()).append(" unreadable");
        }
        out.println(paint(DIM, "target Minecraft " + result.target()
                + "   " + result.classesScanned() + " classes scanned" + skipped));

        if (result.findings().isEmpty()) {
            out.println();
            out.println(paint(BOLD, "No issues found.") + " Nothing in this plugin trips the "
                    + "current rules for Minecraft " + result.target() + ".");
            out.println();
            return;
        }

        String currentRule = null;
        for (Finding f : result.findings()) {
            if (!f.ruleId().equals(currentRule)) {
                currentRule = f.ruleId();
                out.println();
                out.println(paint(BOLD, "[" + currentRule + "]"));
            }
            out.println("  " + severityColour(f.severity()) + f.severity().label() + reset()
                    + "  " + f.location());
            out.println("         " + f.message());
            if (f.hint() != null) {
                out.println(paint(DIM, wrapHint(f.hint())));
            }
        }

        out.println();
        out.print("Summary: ");
        StringBuilder sb = new StringBuilder();
        appendCount(sb, result.count(Severity.ERROR), "error", RED);
        appendCount(sb, result.count(Severity.WARN), "warning", YELLOW);
        appendCount(sb, result.count(Severity.INFO), "note", BLUE);
        out.println(sb);

        Map<String, Long> byRule = result.byRule();
        if (byRule.size() > 1) {
            out.println(paint(DIM, "         " + String.join(", ", byRule.entrySet().stream()
                    .map(e -> e.getKey() + " x" + e.getValue()).toList())));
        }
        out.println();
    }

    private static final int WIDTH = 88;
    private static final String HINT_INDENT = "         ";

    /**
     * Wraps a hint to the terminal width.
     *
     * <p>Hints explain the fix and are the most useful part of a finding, so they must
     * stay readable rather than running off the right edge.
     */
    private static String wrapHint(String hint) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder(HINT_INDENT + "-> ");
        int lineStart = line.length();
        for (String word : hint.split(" ")) {
            if (line.length() > lineStart && line.length() + word.length() + 1 > WIDTH) {
                out.append(line).append(System.lineSeparator());
                line = new StringBuilder(HINT_INDENT + "   ");
                lineStart = line.length();
            } else if (line.length() > lineStart) {
                line.append(' ');
            }
            line.append(word);
        }
        return out.append(line).toString();
    }

    private void appendCount(StringBuilder sb, long n, String noun, String colourCode) {
        if (n == 0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(paint(colourCode, n + " " + noun + (n == 1 ? "" : "s")));
    }

    private String severityColour(Severity s) {
        if (!colour) {
            return "";
        }
        return switch (s) {
            case ERROR -> RED;
            case WARN -> YELLOW;
            case INFO -> BLUE;
        };
    }

    private String reset() {
        return colour ? RESET : "";
    }

    private String paint(String code, String text) {
        return colour ? code + text + RESET : text;
    }
}
