package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.Ruleset;
import dev.mcdrift.core.ScanContext;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags calls to deprecated or removed Bukkit/Paper API, using the shipped ruleset.
 *
 * <p>Scoped by the deprecation's own {@code since} version against the scan target, so
 * scanning for an older server does not report deprecations that had not happened yet.
 */
public final class DeprecatedApiRule implements Rule {

    public static final String ID = "deprecated-api";

    /**
     * Renders a descriptor's parameters as simple type names, e.g. {@code (I)V} becomes
     * {@code "int"} — enough to tell overloads apart in a one-line message.
     */
    static String describeParams(String descriptor) {
        int close = descriptor.indexOf(')');
        if (!descriptor.startsWith("(") || close < 0) {
            return "";
        }
        List<String> names = new ArrayList<>();
        int i = 1;
        while (i < close) {
            int arrays = 0;
            while (i < close && descriptor.charAt(i) == '[') {
                arrays++;
                i++;
            }
            if (i >= close) {
                break;
            }
            char c = descriptor.charAt(i);
            String name;
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0 || end > close) {
                    break;
                }
                String internal = descriptor.substring(i + 1, end);
                name = internal.substring(Math.max(internal.lastIndexOf('/'),
                        internal.lastIndexOf('$')) + 1);
                i = end + 1;
            } else {
                name = switch (c) {
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'D' -> "double";
                    case 'F' -> "float";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'S' -> "short";
                    case 'Z' -> "boolean";
                    default -> String.valueOf(c);
                };
                i++;
            }
            names.add(name + "[]".repeat(arrays));
        }
        return String.join(", ", names);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Calls to deprecated or removed Bukkit/Paper API (ruleset-driven)";
    }

    @Override
    public List<Finding> check(ClassNode cls, ScanContext ctx) {
        Ruleset ruleset = ctx.ruleset();
        if (ruleset == null) {
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
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                Ruleset.Entry entry = ruleset.lookup(call.owner, call.name, call.desc);
                if (entry == null) {
                    continue;
                }
                // Don't report a deprecation that hasn't landed on the target yet.
                if (entry.since != null) {
                    McVersion since = McVersion.parseOrNull(entry.since);
                    if (since != null && ctx.target().isOlderThan(since)) {
                        continue;
                    }
                }
                String owner = call.owner.replace('/', '.');
                String hint = entry.replacement != null
                        ? entry.replacementSentence()
                        : "This member has no direct replacement; check the API docs for the "
                                + "current approach.";

                // A constructor is `<init>` in bytecode, which is not how anyone writes
                // or reads it. Report it as `new Owner(...)`.
                String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
                String what = "<init>".equals(call.name)
                        ? "constructor new " + simpleOwner + "(" + describeParams(call.desc) + ")"
                        : owner + "#" + call.name + "()";

                out.add(new Finding(
                        ID,
                        entry.severity,
                        cls.name,
                        m.name,
                        line,
                        "Calls deprecated " + what
                                + (entry.since == null ? "" : " (since " + entry.since + ")"),
                        hint));
            }
        }
        return out;
    }
}
