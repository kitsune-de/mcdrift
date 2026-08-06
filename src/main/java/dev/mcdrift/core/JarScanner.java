package dev.mcdrift.core;

import dev.mcdrift.rules.RuleRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Reads a plugin jar and runs every enabled rule over every class in it.
 */
public final class JarScanner {

    private final List<Rule> rules;

    public JarScanner(List<Rule> rules) {
        this.rules = rules;
    }

    public ScanResult scan(Path jar, McVersion target, Ruleset ruleset) throws IOException {
        List<Finding> findings = new ArrayList<>();
        PluginMeta meta = PluginMeta.unknown();
        int classesScanned = 0;
        int classesVendored = 0;
        int classesUnreadable = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            PluginMeta parsed = readMeta(zip);
            if (parsed != null) {
                meta = parsed;
            }
            ScanContext ctx = new ScanContext(target, meta, ruleset);

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                if (isVendored(entry.getName())) {
                    classesVendored++;
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                } catch (Exception e) {
                    classesUnreadable++;
                    continue;
                }

                ClassNode node;
                try {
                    node = readClass(bytes);
                } catch (Exception e) {
                    // ASM refuses class files newer than it knows about. Silently
                    // skipping would mean ignoring exactly the plugins built for the
                    // newest Minecraft — the ones most worth checking — so report the
                    // one thing still readable straight from the header.
                    classesUnreadable++;
                    int major = classFileMajor(bytes);
                    if (major > 0) {
                        findings.add(unreadableClassFinding(entry.getName(), major, ctx));
                    }
                    continue;
                }
                classesScanned++;
                for (Rule rule : rules) {
                    try {
                        // Rules do not need to know about the SourceFile attribute, so
                        // it is attached here rather than threaded through every rule.
                        for (Finding f : rule.check(node, ctx)) {
                            findings.add(withSourceFile(f, node.sourceFile));
                        }
                    } catch (RuntimeException e) {
                        // One rule failing on one class must not lose the results from
                        // every other rule. The class itself was still scanned, so it
                        // does not count as skipped.
                    }
                }
            }
        }

        // Group by rule first so the report shows each rule once; severity orders the
        // findings within a rule. Sorting by severity first splits a rule's findings
        // across several headings, which reads as several unrelated problems.
        findings.sort(Comparator
                .comparing(Finding::ruleId)
                .thenComparing(Finding::severity)
                .thenComparing(Finding::className)
                .thenComparingInt(Finding::line));

        return new ScanResult(jar, meta, target, findings, classesScanned,
                classesVendored, classesUnreadable);
    }

    /** Copies a finding with the class's SourceFile attribute attached. */
    private static Finding withSourceFile(Finding f, String sourceFile) {
        if (sourceFile == null || f.sourceFile() != null) {
            return f;
        }
        return new Finding(f.ruleId(), f.severity(), f.className(), f.methodName(),
                f.line(), f.message(), f.hint(), sourceFile);
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        // SKIP_FRAMES: we never rewrite bytecode, so frames cost time and tell us nothing.
        reader.accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    /**
     * Reads the class file major version straight from the header.
     *
     * <p>Bytes 0-3 are the magic number and 6-7 are the major version, so this works on
     * class files far newer than the ASM we are linked against.
     */
    private static int classFileMajor(byte[] bytes) {
        if (bytes.length < 8) {
            return -1;
        }
        boolean isClassFile = (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0xBA && (bytes[3] & 0xFF) == 0xBE;
        if (!isClassFile) {
            return -1;
        }
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    /** Fallback finding for a class ASM could not parse. */
    private static Finding unreadableClassFinding(String entryName, int major, ScanContext ctx) {
        String className = entryName.substring(0, entryName.length() - ".class".length());
        int serverJava = ctx.target().isAtLeast(McVersion.parse("26.1")) ? 69
                : ctx.target().isAtLeast(McVersion.parse("1.20.5")) ? 65 : 61;
        boolean tooNew = major > serverJava;
        return new Finding(
                tooNew ? "bytecode-level" : "unreadable-class",
                tooNew ? Severity.ERROR : Severity.WARN,
                className,
                null,
                Finding.NO_LINE,
                tooNew
                        ? "Compiled for Java " + (major - 44) + " but Minecraft " + ctx.target()
                                + " runs on Java " + (serverJava - 44)
                        : "Class file version " + major + " could not be parsed; it was not "
                                + "checked by the other rules",
                tooNew
                        ? "The server throws UnsupportedClassVersionError before the plugin "
                                + "loads. Set your toolchain's release to Java "
                                + (serverJava - 44) + " or lower."
                        : "This build of mcdrift is older than the compiler that produced the "
                                + "class. Update mcdrift to scan it properly.");
    }

    /**
     * True for classes that came from a shaded third-party library rather than the
     * plugin author's own code.
     *
     * <p>Without this, scanning any plugin that shades a library reports findings the
     * author cannot act on, which is the fastest way to make a linter get uninstalled.
     */
    private static boolean isVendored(String path) {
        String p = path.replace('/', '.');
        return p.contains(".shaded.")
                || p.contains(".shadow.")
                || p.contains(".repackaged.")
                || p.contains(".libs.")
                || p.startsWith("com.google.")
                || p.startsWith("com.mojang.")
                || p.startsWith("org.apache.")
                || p.startsWith("org.slf4j.")
                || p.startsWith("org.yaml.")
                || p.startsWith("kotlin.")
                || p.startsWith("net.kyori.")
                || p.startsWith("org.jetbrains.")
                || p.startsWith("org.intellij.")
                || p.startsWith("javax.")
                || p.startsWith("META-INF.");
    }

    /**
     * Minimal plugin.yml reader.
     *
     * <p>Only three scalars matter and plugin.yml is often malformed, so this reads the
     * keys directly rather than failing the whole scan on a strict YAML parse.
     */
    private static PluginMeta readMeta(ZipFile zip) {
        boolean paperPlugin = zip.getEntry("paper-plugin.yml") != null;
        ZipEntry entry = zip.getEntry("paper-plugin.yml");
        if (entry == null) {
            entry = zip.getEntry("plugin.yml");
        }
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new PluginMeta(
                    scalar(text, "name"),
                    scalar(text, "version"),
                    scalar(text, "api-version"),
                    paperPlugin);
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads a top-level {@code key: value} scalar, ignoring nested and quoted forms. */
    private static String scalar(String yaml, String key) {
        for (String rawLine : yaml.split("\r?\n")) {
            // Top-level keys only: an indented line belongs to some nested section.
            if (rawLine.isEmpty() || Character.isWhitespace(rawLine.charAt(0))) {
                continue;
            }
            String line = rawLine.trim();
            if (line.startsWith("#") || !line.startsWith(key)) {
                continue;
            }
            String rest = line.substring(key.length()).trim();
            if (!rest.startsWith(":")) {
                continue;
            }
            String value = rest.substring(1).trim();
            int comment = value.indexOf(" #");
            if (comment >= 0) {
                value = value.substring(0, comment).trim();
            }
            if (value.length() >= 2
                    && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    public static JarScanner withAllRules() {
        return new JarScanner(RuleRegistry.all());
    }
}
