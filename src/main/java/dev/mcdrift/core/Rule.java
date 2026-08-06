package dev.mcdrift.core;

import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/**
 * A single check, run against every class in the jar.
 *
 * <p>Rules are stateless with respect to each other: each gets the parsed class and
 * returns what it found. Adding a rule means adding one class and registering it.
 */
public interface Rule {

    /** Stable identifier, shown in output and used by --disable. */
    String id();

    /** One line explaining what this rule catches, for --list-rules. */
    String description();

    List<Finding> check(ClassNode cls, ScanContext ctx);
}
