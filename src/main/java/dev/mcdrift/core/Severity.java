package dev.mcdrift.core;

/**
 * How badly a finding will hurt when the server actually starts.
 */
public enum Severity {
    /** Will not work on the target version. Hard breakage. */
    ERROR,
    /** Silently wrong behaviour, or breakage on a near-future version. */
    WARN,
    /** Still works, but is on its way out. */
    INFO;

    public String label() {
        return switch (this) {
            case ERROR -> "ERROR";
            case WARN -> "WARN ";
            case INFO -> "INFO ";
        };
    }
}
