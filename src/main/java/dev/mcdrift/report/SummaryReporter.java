package dev.mcdrift.report;

import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;

/**
 * One-line-per-jar table, for scanning a whole {@code plugins/} directory.
 *
 * <p>A server admin deciding whether to upgrade does not want every finding from forty
 * plugins — they want to know which plugins to deal with first. Rows are ordered worst
 * first so the answer is the top of the table.
 */
public final class SummaryReporter {

    private final boolean colour;

    public SummaryReporter(boolean colour) {
        this.colour = colour;
    }

    private static final String RESET = "[0m";
    private static final String RED = "[31m";
    private static final String YELLOW = "[33m";
    private static final String GREEN = "[32m";
    private static final String DIM = "[2m";
    private static final String BOLD = "[1m";

    public void report(List<ScanResult> results, McVersion target, PrintStream out) {
        List<ScanResult> ordered = results.stream()
                .sorted(Comparator
                        .comparingLong((ScanResult r) -> -r.count(Severity.ERROR))
                        .thenComparingLong(r -> -r.count(Severity.WARN))
                        .thenComparing(r -> r.jar().getFileName().toString()))
                .toList();

        int nameWidth = Math.max(6, ordered.stream()
                .mapToInt(r -> displayName(r).length())
                .max().orElse(6));
        nameWidth = Math.min(nameWidth, 34);

        out.println();
        out.println(paint(BOLD, "Scanning " + results.size() + " plugins against Minecraft "
                + target));
        out.println();
        out.printf("  %-" + nameWidth + "s  %7s  %8s  %6s   %s%n",
                "PLUGIN", "ERRORS", "WARNINGS", "NOTES", "VERDICT");
        out.println("  " + "-".repeat(nameWidth + 34));

        int blocked = 0;
        for (ScanResult r : ordered) {
            long errors = r.count(Severity.ERROR);
            long warnings = r.count(Severity.WARN);
            long notes = r.count(Severity.INFO);

            String verdict;
            if (errors > 0) {
                verdict = paint(RED, "will break");
                blocked++;
            } else if (warnings > 0) {
                verdict = paint(YELLOW, "needs review");
            } else {
                verdict = paint(GREEN, "looks fine");
            }

            out.printf("  %-" + nameWidth + "s  %7s  %8s  %6s   %s%n",
                    truncate(displayName(r), nameWidth),
                    errors == 0 ? "-" : String.valueOf(errors),
                    warnings == 0 ? "-" : String.valueOf(warnings),
                    notes == 0 ? "-" : String.valueOf(notes),
                    verdict);
        }

        out.println();
        if (blocked == 0) {
            out.println("None of these plugins trip a blocking rule for Minecraft "
                    + target + ".");
        } else {
            out.println(blocked + " of " + results.size()
                    + " plugins have errors that will break on Minecraft " + target + ".");
            out.println(paint(DIM, "Run mcdrift on a single jar to see the details."));
        }
        out.println();
    }

    /**
     * Label for a row.
     *
     * <p>The file name comes first: an admin looking at this table is deciding which
     * file in {@code plugins/} to deal with, and two files can carry the same internal
     * plugin name. The declared name is appended only when it differs from the file.
     */
    private static String displayName(ScanResult r) {
        String file = r.jar().getFileName().toString();
        String stem = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        String declared = r.meta().name();
        if (declared == null || declared.equalsIgnoreCase(stem)) {
            return stem;
        }
        return stem + " (" + declared + ")";
    }

    private static String truncate(String s, int width) {
        return s.length() <= width ? s : s.substring(0, width - 1) + "…";
    }

    private String paint(String code, String text) {
        return colour ? code + text + RESET : text;
    }
}
