package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.ScanContext;
import dev.mcdrift.core.Severity;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds version checks that assume the old {@code 1.x} numbering.
 *
 * <p>This is the highest-value rule in the tool because the failure is silent. A plugin
 * doing {@code version.startsWith("1.")} does not crash on 26.1 — it takes the wrong
 * branch, disables a feature, or applies a legacy compatibility path forever. Nothing
 * in the log points at it.
 *
 * <p>Detection is deliberately conservative: a string literal alone proves nothing, so
 * we only report when a suspicious literal is consumed by a string operation whose
 * result drives a comparison.
 */
public final class VersionParsingRule implements Rule {

    public static final String ID = "version-parsing";

    /** Literals that only make sense as a legacy-version prefix test. */
    private static final Pattern LEGACY_PREFIX = Pattern.compile("^1\\.\\d{0,2}\\.?$");

    /** Regex literals anchored to a leading "1.". */
    private static final Pattern LEGACY_REGEX = Pattern.compile("^\\^?1\\\\?\\.[\\\\\\[(].*");

    /** String methods that turn a literal into a branch decision. */
    private static final Set<String> PREFIX_METHODS = Set.of(
            "startsWith", "contains", "equals", "equalsIgnoreCase", "matches", "split", "indexOf");

    private static final Set<String> VERSION_SOURCES = Set.of(
            "getVersion", "getBukkitVersion", "getMinecraftVersion", "getServerVersion");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Version checks that assume the legacy 1.x scheme (silently wrong on 26.1+)";
    }

    @Override
    public List<Finding> check(ClassNode cls, ScanContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions == null) {
                continue;
            }
            int line = Finding.NO_LINE;
            boolean readsVersion = methodReadsServerVersion(m);

            for (AbstractInsnNode insn : m.instructions) {
                line = LineTracker.update(insn, line);

                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String lit)) {
                    continue;
                }
                Kind kind = classify(lit);
                if (kind == null) {
                    continue;
                }
                MethodInsnNode consumer = nextStringCall(insn);
                if (consumer == null) {
                    continue;
                }

                // A bare "1." literal is only interesting next to a version lookup;
                // an anchored regex is suspicious on its own.
                if (kind == Kind.PREFIX && !readsVersion) {
                    continue;
                }

                out.add(new Finding(
                        ID,
                        Severity.ERROR,
                        cls.name,
                        m.name,
                        line,
                        "Version check on literal \"" + lit + "\" via " + consumer.name
                                + "() assumes the legacy 1.x scheme",
                        "Minecraft 26.1 replaced 1.21.11; there is no 1.22. This test does not "
                                + "crash, it silently takes the wrong branch. Compare parsed "
                                + "versions where every calendar version outranks every 1.x "
                                + "version, or feature-detect instead of checking the string."));
            }
        }
        return out;
    }

    private enum Kind { PREFIX, REGEX }

    private static Kind classify(String lit) {
        if (lit.length() > 40) {
            return null;
        }
        if (LEGACY_PREFIX.matcher(lit).matches()) {
            return Kind.PREFIX;
        }
        if (LEGACY_REGEX.matcher(lit).matches()) {
            return Kind.REGEX;
        }
        return null;
    }

    /**
     * Walks forward past stack shuffling to the string method that consumes the literal.
     * Returns null if the literal is not used in a string comparison.
     */
    private static MethodInsnNode nextStringCall(AbstractInsnNode from) {
        AbstractInsnNode cur = from.getNext();
        int budget = 6;
        while (cur != null && budget-- > 0) {
            if (cur instanceof MethodInsnNode call) {
                boolean stringLike = call.owner.equals("java/lang/String")
                        || call.owner.equals("java/util/regex/Pattern");
                return stringLike && PREFIX_METHODS.contains(call.name) ? call : null;
            }
            if (cur.getOpcode() >= 0) {
                // A real instruction that is not a call: the literal went elsewhere.
                if (!LineTracker.isMeta(cur)) {
                    return null;
                }
            }
            cur = cur.getNext();
        }
        return null;
    }

    /** True if the method fetches a server/Bukkit version anywhere in its body. */
    private static boolean methodReadsServerVersion(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call
                    && VERSION_SOURCES.contains(call.name)
                    && (call.owner.startsWith("org/bukkit") || call.owner.startsWith("io/papermc"))) {
                return true;
            }
        }
        return false;
    }
}
