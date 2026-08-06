package dev.mcdrift.core;

import java.util.Objects;

/**
 * A Minecraft version under either numbering scheme.
 *
 * <p>Mojang switched from {@code 1.x.y} to calendar versioning in March 2026: the last
 * legacy release was 1.21.11 and the first calendar release was 26.1. There is no 1.22.
 * Every calendar version is therefore newer than every legacy version, which is exactly
 * the fact that naive string or numeric comparison gets wrong.
 *
 * <p>This class is the correct comparison that {@link dev.mcdrift.rules.VersionParsingRule}
 * tells plugin authors to write.
 */
public final class McVersion implements Comparable<McVersion> {

    /** First release under the calendar scheme. */
    public static final McVersion FIRST_CALVER = new McVersion(true, 26, 1, 0);
    /** Last release under the legacy 1.x scheme. */
    public static final McVersion LAST_LEGACY = new McVersion(false, 1, 21, 11);

    private final boolean calver;
    private final int major;
    private final int minor;
    private final int patch;

    private McVersion(boolean calver, int major, int minor, int patch) {
        this.calver = calver;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public boolean isCalver() {
        return calver;
    }

    /**
     * Parse a version string such as {@code 1.21.11}, {@code 26.1} or {@code 26.1.2}.
     *
     * <p>Trailing qualifiers like {@code -R0.1-SNAPSHOT} or {@code -pre1} are ignored.
     *
     * @throws IllegalArgumentException if the string is not a recognisable version
     */
    public static McVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("version is empty");
        }
        String s = raw.trim();

        // Strip qualifiers: 26.1-pre1, 1.21.11-R0.1-SNAPSHOT, 26.3-snapshot-7
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("no numeric component in version: " + raw);
        }

        String[] parts = s.split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i < parts.length && !parts[i].isEmpty()) {
                try {
                    nums[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad version component in: " + raw, e);
                }
            }
        }

        // The scheme is decided by the leading component, not by string shape:
        // 1.x is legacy, anything >= 26 is a calendar year.
        boolean isCalver = nums[0] != 1;
        if (isCalver && nums[0] < 26) {
            throw new IllegalArgumentException(
                    "unrecognised version scheme (leading component " + nums[0] + "): " + raw);
        }
        return new McVersion(isCalver, nums[0], nums[1], nums[2]);
    }

    /** Parse, or return null instead of throwing. */
    public static McVersion parseOrNull(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public int compareTo(McVersion other) {
        // The scheme itself is the most significant component: all calver > all legacy.
        if (calver != other.calver) {
            return calver ? 1 : -1;
        }
        int c = Integer.compare(major, other.major);
        if (c != 0) return c;
        c = Integer.compare(minor, other.minor);
        if (c != 0) return c;
        return Integer.compare(patch, other.patch);
    }

    public boolean isAtLeast(McVersion other) {
        return compareTo(other) >= 0;
    }

    public boolean isOlderThan(McVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof McVersion v)) return false;
        return calver == v.calver && major == v.major && minor == v.minor && patch == v.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(calver, major, minor, patch);
    }

    @Override
    public String toString() {
        return patch == 0 ? major + "." + minor : major + "." + minor + "." + patch;
    }
}
