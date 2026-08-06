package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;

/**
 * Tracks the current source line while walking an instruction list.
 *
 * <p>Line numbers only exist when the jar was compiled with debug info. Most published
 * plugins are, but obfuscated ones are not, so every caller must tolerate
 * {@link Finding#NO_LINE}.
 */
final class LineTracker {

    private LineTracker() {
    }

    /** Returns the line in effect after this instruction. */
    static int update(AbstractInsnNode insn, int current) {
        return insn instanceof LineNumberNode ln ? ln.line : current;
    }

    /** True for pseudo-instructions that carry no runtime behaviour. */
    static boolean isMeta(AbstractInsnNode insn) {
        return insn.getOpcode() < 0;
    }
}
