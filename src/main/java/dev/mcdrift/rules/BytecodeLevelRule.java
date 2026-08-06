package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.McVersion;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.ScanContext;
import dev.mcdrift.core.Severity;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the class file version against what the target server's JVM can load.
 *
 * <p>26.1 raised the requirement to Java 25. A plugin compiled above the server's Java
 * level dies with {@code UnsupportedClassVersionError} at load time, before any of its
 * code runs. This is the cheapest possible check — the answer is in the class header —
 * and it catches a whole class of "my plugin won't load" reports.
 */
public final class BytecodeLevelRule implements Rule {

    public static final String ID = "bytecode-level";

    /** Class file major version 65 == Java 21. */
    private static final int JAVA_21 = 65;
    private static final int JAVA_25 = 69;

    /** Minimum JVM the server itself requires, by Minecraft version. */
    private static final McVersion MC_REQUIRING_JAVA_25 = McVersion.parse("26.1");
    private static final McVersion MC_REQUIRING_JAVA_21 = McVersion.parse("1.20.5");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Class file version above what the target server's JVM can load";
    }

    @Override
    public List<Finding> check(ClassNode cls, ScanContext ctx) {
        int serverJava = serverJavaFor(ctx.target());
        int major = cls.version & 0xFFFF;
        if (major <= serverJava) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>(1);
        out.add(new Finding(
                ID,
                Severity.ERROR,
                cls.name,
                null,
                Finding.NO_LINE,
                "Compiled for Java " + javaName(major) + " but Minecraft " + ctx.target()
                        + " runs on Java " + javaName(serverJava),
                "The server throws UnsupportedClassVersionError before the plugin loads. "
                        + "Set your toolchain's release to " + javaName(serverJava) + " or lower."));
        return out;
    }

    /** Highest Java release the server for this Minecraft version is guaranteed to run. */
    private static int serverJavaFor(McVersion target) {
        if (target.isAtLeast(MC_REQUIRING_JAVA_25)) {
            return JAVA_25;
        }
        if (target.isAtLeast(MC_REQUIRING_JAVA_21)) {
            return JAVA_21;
        }
        return 61; // Java 17, the floor for modern Paper.
    }

    private static String javaName(int classFileMajor) {
        return String.valueOf(classFileMajor - 44);
    }
}
