package dev.mcdrift.core;

/**
 * One problem found in one place.
 *
 * <p>{@code line} is -1 when the jar was compiled without debug information; in that
 * case {@link #location()} falls back to the enclosing method, which is still enough
 * to find the code by hand.
 */
public record Finding(
        String ruleId,
        Severity severity,
        String className,
        String methodName,
        int line,
        String message,
        String hint,
        String sourceFile
) {
    public static final int NO_LINE = -1;

    /** Convenience constructor for findings with no known source file. */
    public Finding(String ruleId, Severity severity, String className, String methodName,
                   int line, String message, String hint) {
        this(ruleId, severity, className, methodName, line, message, hint, null);
    }

    /**
     * Repository-relative source path, or null when it cannot be established.
     *
     * <p>Built from the class's package plus the {@code SourceFile} attribute that
     * javac records, so the file name is the real one rather than a guess from the
     * class name — which matters for inner classes, and for files that declare a class
     * whose name differs from the file's.
     *
     * <p>The source root is not knowable from bytecode, so it is supplied by the
     * caller. Returning null when there is no file name is deliberate: emitting a
     * plausible-looking path that does not exist is worse than emitting none.
     */
    public String sourcePath(String sourceRoot) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return null;
        }
        String pkg = className.replace('.', '/');
        int lastSlash = pkg.lastIndexOf('/');
        String dir = lastSlash < 0 ? "" : pkg.substring(0, lastSlash + 1);
        String prefix = sourceRoot == null || sourceRoot.isBlank() ? "" : sourceRoot;
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + dir + sourceFile;
    }

    /** Human-readable source position, as precise as the class file allows. */
    public String location() {
        String cls = className.replace('/', '.');
        if (line != NO_LINE) {
            return cls + ":" + line;
        }
        if (methodName != null && !methodName.isBlank()) {
            return cls + "#" + methodName;
        }
        return cls;
    }
}
