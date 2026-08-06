package dev.mcdrift;

import dev.mcdrift.core.McVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison this tool tells plugin authors to adopt has to be right itself.
 */
class McVersionTest {

    @Test
    @DisplayName("every calendar version outranks every legacy version")
    void calverBeatsLegacy() {
        assertTrue(McVersion.parse("26.1").isAtLeast(McVersion.parse("1.21.11")));
        assertTrue(McVersion.parse("26.1").isAtLeast(McVersion.parse("1.8")));
        assertTrue(McVersion.parse("1.21.11").isOlderThan(McVersion.parse("26.1")));
        // The trap: naive numeric comparison of the leading component says 26 > 1,
        // which is right here by luck; naive string comparison says "1..." < "26..."
        // only sometimes. Both are wrong in general, this must be right always.
        assertFalse(McVersion.parse("1.21.11").isAtLeast(McVersion.parse("26.1")));
    }

    @Test
    @DisplayName("ordering within each scheme is numeric, not lexical")
    void withinSchemeOrdering() {
        assertTrue(McVersion.parse("1.21.11").isAtLeast(McVersion.parse("1.21.2")));
        assertTrue(McVersion.parse("1.21").isAtLeast(McVersion.parse("1.9")));
        assertTrue(McVersion.parse("26.2").isAtLeast(McVersion.parse("26.1.2")));
        assertTrue(McVersion.parse("26.1.2").isAtLeast(McVersion.parse("26.1")));
        assertTrue(McVersion.parse("26.1.2").isAtLeast(McVersion.parse("26.1.1")));
    }

    @Test
    @DisplayName("sorting a mixed list puts the boundary in the right place")
    void sortingMixedList() {
        List<McVersion> versions = new ArrayList<>(List.of(
                McVersion.parse("26.1"),
                McVersion.parse("1.21.11"),
                McVersion.parse("26.2"),
                McVersion.parse("1.8"),
                McVersion.parse("1.21.2")));
        versions.sort(McVersion::compareTo);
        assertEquals(
                List.of("1.8", "1.21.2", "1.21.11", "26.1", "26.2"),
                versions.stream().map(McVersion::toString).toList());
    }

    @Test
    @DisplayName("qualifiers and snapshot suffixes are ignored")
    void stripsQualifiers() {
        assertEquals(McVersion.parse("1.21.11"), McVersion.parse("1.21.11-R0.1-SNAPSHOT"));
        assertEquals(McVersion.parse("26.1"), McVersion.parse("26.1-pre1"));
        assertEquals(McVersion.parse("26.3"), McVersion.parse("26.3-snapshot-7"));
    }

    @Test
    @DisplayName("scheme is decided by the leading component")
    void schemeDetection() {
        assertFalse(McVersion.parse("1.21.11").isCalver());
        assertTrue(McVersion.parse("26.1").isCalver());
        assertTrue(McVersion.parse("27.4").isCalver());
    }

    @Test
    @DisplayName("nonsense input is rejected rather than silently parsed")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> McVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> McVersion.parse(null));
        assertThrows(IllegalArgumentException.class, () -> McVersion.parse("abc"));
        // A leading component that is neither 1 nor a plausible year.
        assertThrows(IllegalArgumentException.class, () -> McVersion.parse("7.3"));
        assertEquals(null, McVersion.parseOrNull("nonsense"));
    }

    @Test
    @DisplayName("known boundary constants match the real releases")
    void boundaryConstants() {
        assertEquals(McVersion.parse("1.21.11"), McVersion.LAST_LEGACY);
        assertEquals(McVersion.parse("26.1"), McVersion.FIRST_CALVER);
        // There is no 1.22: nothing legacy may sort above the last legacy release.
        assertTrue(McVersion.LAST_LEGACY.isOlderThan(McVersion.FIRST_CALVER));
    }
}
