package dev.mcdrift;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.JarScanner;
import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.Ruleset;
import dev.mcdrift.core.ScanResult;
import dev.mcdrift.core.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds real plugin jars with ASM and scans them, so the rules are exercised against
 * actual bytecode rather than a mocked instruction list.
 */
class RuleScanTest implements Opcodes {

    private static final McVersion TARGET_26_1 = McVersion.parse("26.1");

    @Test
    @DisplayName("catches startsWith(\"1.\") next to a server version lookup")
    void findsLegacyVersionCheck(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/VersionCheck", V21, mv -> {
            mv.visitMethodInsn(INVOKESTATIC, "org/bukkit/Bukkit", "getBukkitVersion",
                    "()Ljava/lang/String;", false);
            mv.visitLdcInsn("1.");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        List<Finding> hits = findingsFor(result, "version-parsing");
        assertEquals(1, hits.size(), "expected exactly one version-parsing finding");
        assertEquals(Severity.ERROR, hits.get(0).severity());
        assertTrue(hits.get(0).message().contains("1."));
    }

    @Test
    @DisplayName("does not flag a bare \"1.\" literal with no version lookup nearby")
    void ignoresUnrelatedLiteral(@TempDir Path dir) throws Exception {
        // Same literal and same String call, but nothing reads a server version:
        // this is ordinary string handling and must stay quiet.
        byte[] cls = classWith("com/example/Unrelated", V21, mv -> {
            mv.visitLdcInsn("some.user.input");
            mv.visitLdcInsn("1.");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);
        assertEquals(List.of(), findingsFor(result, "version-parsing"));
    }

    @Test
    @DisplayName("catches versioned NMS packages and Spigot-mapped reflection strings")
    void findsLegacyMappings(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/Nms", V21, mv -> {
            mv.visitLdcInsn("net.minecraft.server.v1_20_R3.EntityPlayer");
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
            mv.visitInsn(POP);
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "org/bukkit/craftbukkit/v1_20_R3/CraftServer", "getHandle",
                    "()Ljava/lang/Object;", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        List<Finding> hits = findingsFor(result, "legacy-mappings");
        assertEquals(2, hits.size(), "expected the reflection string and the CraftBukkit call");
        assertTrue(hits.stream().allMatch(f -> f.severity() == Severity.ERROR));
    }

    @Test
    @DisplayName("modern package-split NMS is not flagged")
    void ignoresMojangMappedNms(@TempDir Path dir) throws Exception {
        // net/minecraft/world/entity/... is Mojang-mapped and survives 26.1.
        byte[] cls = classWith("com/example/Modern", V21, mv -> {
            mv.visitMethodInsn(INVOKEVIRTUAL,
                    "net/minecraft/world/entity/player/Player", "getInventory",
                    "()Ljava/lang/Object;", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);
        assertEquals(List.of(), findingsFor(result, "legacy-mappings"));
    }

    @Test
    @DisplayName("catches hardcoded dimension folders but not lookalike strings")
    void findsWorldPaths(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/Backup", V21, mv -> {
            mv.visitLdcInsn("world_nether/region");
            mv.visitInsn(POP);
            mv.visitLdcInsn("plugins/MyPlugin/DIM-1");
            mv.visitInsn(POP);
            // Not a path segment: must not fire.
            mv.visitLdcInsn("mydimension_worldx");
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        List<Finding> hits = findingsFor(result, "world-paths");
        assertEquals(2, hits.size(), "expected the two real path literals only");
        assertTrue(hits.stream().anyMatch(f -> f.message().contains("world_nether")));
        assertTrue(hits.stream().anyMatch(f -> f.message().contains("DIM-1")));
    }

    @Test
    @DisplayName("catches deprecated API calls from the ruleset")
    void findsDeprecatedApi(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/Deprecated", V21, mv -> {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/block/Block", "getData",
                    "()B", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        // Block#getData is a "Magic value" deprecation that Paper — but not Spigot —
        // has scheduled for removal, so it lands at WARN: worth acting on for the
        // servers that run Paper, but not an outright error. The severity comes from
        // the javadoc, not from a guess made here.
        List<Finding> hits = findingsFor(result, "deprecated-api");
        assertEquals(1, hits.size());
        assertEquals(Severity.WARN, hits.get(0).severity());
        assertTrue(hits.get(0).message().contains("getData"));
    }

    @Test
    @DisplayName("terminally deprecated members are reported as errors")
    void terminalDeprecationIsError(@TempDir Path dir) throws Exception {
        // AttributeModifier(String, double, Operation) is scheduled for removal.
        byte[] cls = classWith("com/example/Terminal", V21, mv -> {
            mv.visitTypeInsn(NEW, "org/bukkit/attribute/AttributeModifier");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("speed");
            mv.visitInsn(DCONST_1);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, "org/bukkit/attribute/AttributeModifier",
                    "<init>",
                    "(Ljava/lang/String;DLorg/bukkit/attribute/AttributeModifier$Operation;)V",
                    false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        List<Finding> hits = findingsFor(result, "deprecated-api");
        assertEquals(1, hits.size(), "expected the deprecated constructor to be found");
        assertEquals(Severity.ERROR, hits.get(0).severity());
    }

    @Test
    @DisplayName("a parameter-list descriptor matches regardless of return type")
    void prefixDescriptorMatching(@TempDir Path dir) throws Exception {
        // The generated ruleset stores "(I)" for Art#getById; the real call site
        // returns org/bukkit/Art, so only prefix matching finds it.
        byte[] cls = classWith("com/example/Prefix", V21, mv -> {
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKESTATIC, "org/bukkit/Art", "getById",
                    "(I)Lorg/bukkit/Art;", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);
        assertEquals(1, findingsFor(result, "deprecated-api").size());
    }

    @Test
    @DisplayName("flags bytecode newer than the target server's JVM")
    void findsBytecodeTooNew(@TempDir Path dir) throws Exception {
        // Java 25 bytecode against a 1.21.11 server, which runs Java 21.
        // ASM 9.7 has no V25 constant yet; 69 is the Java 25 class file major version.
        byte[] cls = classWith("com/example/TooNew", 69, mv -> mv.visitInsn(NOP));
        ScanResult result = scanAgainst(dir, cls, McVersion.parse("1.21.11"));

        List<Finding> hits = findingsFor(result, "bytecode-level");
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).message().contains("Java 25"));
    }

    @Test
    @DisplayName("shaded library classes are skipped")
    void skipsVendoredClasses(@TempDir Path dir) throws Exception {
        // The same offence, but inside a relocated dependency the author cannot fix.
        byte[] cls = classWith("com/example/libs/net/minecraft/Thing", V21,
                mv -> {
                    mv.visitLdcInsn("net.minecraft.server.v1_20_R3.EntityPlayer");
                    mv.visitInsn(POP);
                });
        Path jar = dir.resolve("shaded.jar");
        writeJar(jar, "com/example/libs/net/minecraft/Thing.class", cls, null);

        ScanResult result = JarScanner.withAllRules()
                .scan(jar, TARGET_26_1, Ruleset.bundled());
        assertEquals(List.of(), result.findings());
        assertEquals(0, result.classesScanned());
    }

    @Test
    @DisplayName("plugin.yml metadata is read")
    void readsPluginMeta(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/Clean", V21, mv -> mv.visitInsn(NOP));
        Path jar = dir.resolve("meta.jar");
        writeJar(jar, "com/example/Clean.class", cls,
                "name: TestPlugin\nversion: 2.4.1\napi-version: '1.21'\nmain: com.example.Clean\n");

        ScanResult result = JarScanner.withAllRules()
                .scan(jar, TARGET_26_1, Ruleset.bundled());
        assertEquals("TestPlugin", result.meta().name());
        assertEquals("2.4.1", result.meta().version());
        assertEquals("1.21", result.meta().apiVersion());
    }

    @Test
    @DisplayName("a clean plugin produces no findings and a zero exit condition")
    void cleanPluginIsQuiet(@TempDir Path dir) throws Exception {
        byte[] cls = classWith("com/example/Clean", V21, mv -> {
            mv.visitMethodInsn(INVOKESTATIC, "org/bukkit/Bukkit", "getOnlinePlayers",
                    "()Ljava/util/Collection;", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);
        assertEquals(List.of(), result.findings());
        assertTrue(!result.hasBlockingIssues());
    }

    @Test
    @DisplayName("a version pattern inside an error message is not a mapping reference")
    void ignoresProseMentioningVersions(@TempDir Path dir) throws Exception {
        // Real case from EssentialsX: the version format appears in a user-facing
        // error string. Flagging it is a false positive on unfixable code.
        byte[] cls = classWith("com/example/Message", V21, mv -> {
            mv.visitLdcInsn(" is not in valid version format. e.g. v1_10_R1");
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);
        assertEquals(List.of(), findingsFor(result, "legacy-mappings"));
    }

    @Test
    @DisplayName("findings are grouped so each rule appears once")
    void findingsGroupedByRule(@TempDir Path dir) throws Exception {
        // Two rules, with differing severities, must not interleave.
        byte[] cls = classWith("com/example/Mixed", V21, mv -> {
            mv.visitLdcInsn("world_nether/region");   // world-paths, ERROR
            mv.visitInsn(POP);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/block/Block", "getData",
                    "()B", false);                     // deprecated-api, INFO
            mv.visitInsn(POP);
            mv.visitLdcInsn("1.2.3.4");
            mv.visitMethodInsn(INVOKESTATIC, "org/bukkit/Bukkit", "banIP",
                    "(Ljava/lang/String;)V", false);    // deprecated-api, WARN
        });
        ScanResult result = scan(dir, cls);

        List<String> ruleOrder = result.findings().stream().map(Finding::ruleId).distinct().toList();
        assertEquals(ruleOrder.size(), ruleOrder.stream().distinct().count(),
                "each rule must appear as a single contiguous group");
        assertTrue(result.findings().size() >= 3);
    }

    @Test
    @DisplayName("shaded classes are counted separately from unreadable ones")
    void separatesSkipReasons(@TempDir Path dir) throws Exception {
        byte[] own = classWith("com/example/Own", V21, mv -> mv.visitInsn(NOP));
        byte[] shaded = classWith("com/example/libs/yaml/Parser", V21, mv -> mv.visitInsn(NOP));

        Path jar = dir.resolve("mixed.jar");
        try (OutputStream os = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("com/example/Own.class"));
            zip.write(own);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("com/example/libs/yaml/Parser.class"));
            zip.write(shaded);
            zip.closeEntry();
        }

        ScanResult result = JarScanner.withAllRules().scan(jar, TARGET_26_1, Ruleset.bundled());
        assertEquals(1, result.classesScanned());
        assertEquals(1, result.classesVendored());
        assertEquals(0, result.classesUnreadable());
    }

    @Test
    @DisplayName("hints from the generated ruleset read as sentences")
    void hintGrammar(@TempDir Path dir) throws Exception {
        // Javadoc descriptions arrive in several shapes: a bare noun phrase
        // ("Magic value"), an imperative ("use Registry.get(...)"), and a cross
        // reference ("See BanEntry.getBanTarget()"). All must render as a sentence.
        byte[] cls = classWith("com/example/Hints", V21, mv -> {
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/Material", "getId", "()I", false);
            mv.visitInsn(POP);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/bukkit/block/Block", "getData", "()B", false);
            mv.visitInsn(POP);
        });
        ScanResult result = scan(dir, cls);

        List<Finding> hits = findingsFor(result, "deprecated-api");
        assertTrue(hits.size() >= 2, "expected both deprecated calls");
        for (Finding f : hits) {
            assertNotNull(f.hint());
            assertTrue(f.hint().endsWith("."), "hint must be a sentence: " + f.hint());
            assertFalse(f.hint().contains("  "), "hint has doubled spaces: " + f.hint());
            assertTrue(Character.isUpperCase(f.hint().charAt(0)),
                    "hint must start capitalised: " + f.hint());
        }
    }

    // --- helpers -------------------------------------------------------------

    private ScanResult scan(Path dir, byte[] cls) throws IOException {
        return scanAgainst(dir, cls, TARGET_26_1);
    }

    private ScanResult scanAgainst(Path dir, byte[] cls, McVersion target) throws IOException {
        Path jar = dir.resolve("test-" + System.nanoTime() + ".jar");
        writeJar(jar, "com/example/Test.class", cls, "name: Test\nversion: 1.0\n");
        return JarScanner.withAllRules().scan(jar, target, Ruleset.bundled());
    }

    private static List<Finding> findingsFor(ScanResult result, String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    private static void writeJar(Path jar, String entryName, byte[] cls, String pluginYml)
            throws IOException {
        try (OutputStream os = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(cls);
            zip.closeEntry();
            if (pluginYml != null) {
                zip.putNextEntry(new ZipEntry("plugin.yml"));
                zip.write(pluginYml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** Emits a class whose {@code run()} body is written by the given callback. */
    private static byte[] classWith(String internalName, int classVersion, BodyWriter body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(classVersion, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        mv.visitCode();
        Label start = new Label();
        mv.visitLabel(start);
        mv.visitLineNumber(42, start);
        body.write(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @FunctionalInterface
    private interface BodyWriter {
        void write(MethodVisitor mv);
    }
}
