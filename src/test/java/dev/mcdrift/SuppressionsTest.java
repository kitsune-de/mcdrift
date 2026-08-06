package dev.mcdrift;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.Severity;
import dev.mcdrift.core.Suppressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuppressionsTest {

    private static Finding finding(String rule, String className) {
        return new Finding(rule, Severity.ERROR, className, "run", 10, "msg", "hint");
    }

    private static Suppressions write(Path dir, String content) throws IOException {
        Path file = dir.resolve(".mcdriftignore");
        Files.writeString(file, content);
        return Suppressions.load(file);
    }

    @Test
    @DisplayName("a bare rule id suppresses that rule everywhere")
    void ruleOnly(@TempDir Path dir) throws Exception {
        Suppressions s = write(dir, "deprecated-api\n");
        assertTrue(s.suppresses(finding("deprecated-api", "com/example/A")));
        assertTrue(s.suppresses(finding("deprecated-api", "org/other/B")));
        assertFalse(s.suppresses(finding("world-paths", "com/example/A")));
    }

    @Test
    @DisplayName("a class glob suppresses every rule in that package")
    void classGlob(@TempDir Path dir) throws Exception {
        Suppressions s = write(dir, "com.example.legacy.*\n");
        assertTrue(s.suppresses(finding("deprecated-api", "com/example/legacy/Old")));
        assertTrue(s.suppresses(finding("world-paths", "com/example/legacy/deep/Deeper")));
        assertFalse(s.suppresses(finding("deprecated-api", "com/example/current/New")));
    }

    @Test
    @DisplayName("rule:class scopes a suppression to one place")
    void ruleAndClass(@TempDir Path dir) throws Exception {
        Suppressions s = write(dir, "deprecated-api:com.example.Old\n");
        assertTrue(s.suppresses(finding("deprecated-api", "com/example/Old")));
        // Same rule elsewhere, and a different rule in the same class, both survive.
        assertFalse(s.suppresses(finding("deprecated-api", "com/example/Other")));
        assertFalse(s.suppresses(finding("world-paths", "com/example/Old")));
    }

    @Test
    @DisplayName("comments and blank lines are ignored")
    void commentsIgnored(@TempDir Path dir) throws Exception {
        Suppressions s = write(dir, """
                # this is a comment

                deprecated-api
                   # indented comment
                """);
        assertEquals(1, s.size());
    }

    @Test
    @DisplayName("an unknown rule id is an error, naming the line")
    void unknownRuleRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".mcdriftignore");
        Files.writeString(file, "deprecated-api\nnonsense-rule\n");
        IOException e = assertThrows(IOException.class, () -> Suppressions.load(file));
        // Silently ignoring a typo would leave the user believing they had suppressed
        // something they had not.
        assertTrue(e.getMessage().contains(":2:"), "should name the line: " + e.getMessage());
        assertTrue(e.getMessage().contains("nonsense-rule"));
    }

    @Test
    @DisplayName("a malformed rule:class line is rejected")
    void malformedRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".mcdriftignore");
        Files.writeString(file, "deprecated-api:\n");
        assertThrows(IOException.class, () -> Suppressions.load(file));
    }

    @Test
    @DisplayName("a missing default file yields an empty set, not an error")
    void missingDefaultIsEmpty(@TempDir Path dir) throws Exception {
        Suppressions s = Suppressions.loadDefault(dir);
        assertTrue(s.isEmpty());
        assertFalse(s.suppresses(finding("deprecated-api", "com/example/A")));
    }

    @Test
    @DisplayName("wildcards do not leak across package boundaries by accident")
    void globIsAnchored(@TempDir Path dir) throws Exception {
        Suppressions s = write(dir, "com.example.A*\n");
        assertTrue(s.suppresses(finding("deprecated-api", "com/example/Alpha")));
        // The pattern is matched against the whole class name, so a class whose name
        // merely contains the text is not suppressed.
        assertFalse(s.suppresses(finding("deprecated-api", "org/vendor/com/example/Alpha")));
    }
}
