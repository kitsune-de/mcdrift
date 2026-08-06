package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.ScanContext;
import dev.mcdrift.core.Severity;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds hardcoded world directory paths that moved in 26.1.
 *
 * <p>Mojang reorganised the world folder: the Overworld moved out of the world root and
 * the Nether and End moved out of their sibling/DIM directories, all into
 * {@code world/dimensions/minecraft/*}. Plugins that touch region files directly —
 * backup tools, map renderers, pregenerators, world trimmers — read from paths that now
 * silently contain nothing.
 *
 * <p>This one is worth flagging even below the target version, because the fix (ask the
 * API for the world folder) is correct on every version.
 */
public final class WorldPathRule implements Rule {

    public static final String ID = "world-paths";

    /** Legacy path fragment -> where that data lives on 26.1+. */
    private static final Map<String, String> MOVED = Map.of(
            "world_nether", "world/dimensions/minecraft/the_nether",
            "world_the_end", "world/dimensions/minecraft/the_end",
            "DIM-1", "world/dimensions/minecraft/the_nether",
            "DIM1", "world/dimensions/minecraft/the_end");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Hardcoded world/dimension directories that moved under world/dimensions/ in 26.1";
    }

    @Override
    public List<Finding> check(ClassNode cls, ScanContext ctx) {
        if (!ctx.targetHasNewWorldLayout()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (m.instructions == null) {
                continue;
            }
            int line = Finding.NO_LINE;
            for (AbstractInsnNode insn : m.instructions) {
                line = LineTracker.update(insn, line);
                if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String lit)) {
                    continue;
                }
                String moved = matchMovedPath(lit);
                if (moved == null) {
                    continue;
                }
                out.add(new Finding(
                        ID,
                        Severity.ERROR,
                        cls.name,
                        m.name,
                        line,
                        "Hardcoded world path \"" + lit + "\"",
                        "Minecraft 26.1 moved this to " + MOVED.get(moved) + ". Ask the API for "
                                + "the directory (World#getWorldFolder) instead of building the "
                                + "path by hand, which stays correct across the move."));
            }
        }
        return out;
    }

    /**
     * Returns the legacy key this literal refers to, or null.
     *
     * <p>Requires a path-like context so an unrelated string that merely contains
     * {@code DIM1} does not trip the rule.
     */
    private static String matchMovedPath(String lit) {
        if (lit.length() > 200) {
            return null;
        }
        String normalised = lit.replace('\\', '/');
        for (String key : MOVED.keySet()) {
            int idx = normalised.indexOf(key);
            if (idx < 0) {
                continue;
            }
            // The literal must be exactly the folder, or a path segment within one.
            boolean startOk = idx == 0 || normalised.charAt(idx - 1) == '/';
            int end = idx + key.length();
            boolean endOk = end == normalised.length() || normalised.charAt(end) == '/';
            // "DIM1" is a prefix of nothing else, but "DIM-1" vs "DIM-10" needs the check.
            if (startOk && endOk) {
                return key;
            }
        }
        return null;
    }
}
