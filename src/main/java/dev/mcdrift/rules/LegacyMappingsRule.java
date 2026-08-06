package dev.mcdrift.rules;

import dev.mcdrift.core.Finding;
import dev.mcdrift.core.Rule;
import dev.mcdrift.core.ScanContext;
import dev.mcdrift.core.Severity;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds Spigot-mapped server internals that no longer resolve on 26.1+.
 *
 * <p>Mojang stopped shipping obfuscated server jars in 26.1 and Paper dropped its
 * remapper, so Spigot-mapped names such as {@code EntityHuman} or {@code PacketPlayInChat}
 * are simply absent at runtime. This breaks loudly — {@code NoClassDefFoundError} on the
 * first call — which makes it easier to diagnose than the version rule, but it breaks
 * on every server rather than in one code path.
 *
 * <p>Both spellings are caught: direct references baked into the constant pool, and the
 * versioned-package string literals used by reflection-based plugins.
 */
public final class LegacyMappingsRule implements Rule {

    public static final String ID = "legacy-mappings";

    /** The classic versioned NMS/CraftBukkit package, e.g. v1_20_R3. */
    private static final Pattern VERSIONED_PACKAGE = Pattern.compile("v1_\\d{1,2}_R\\d");

    /**
     * Spigot-mapped type names. These are the obfuscation-era spellings; the Mojang
     * names that replace them differ ({@code EntityHuman} -> {@code Player}, etc.).
     */
    private static final Set<String> SPIGOT_MAPPED_TYPES = Set.of(
            "EntityHuman", "EntityPlayer", "EntityLiving", "EntityInsentient",
            "PlayerConnection", "MinecraftServer", "WorldServer", "IChatBaseComponent",
            "ChatComponentText", "NBTTagCompound", "NBTTagList", "ItemStack",
            "PlayerInventory", "ContainerPlayer", "DataWatcher", "ChunkProviderServer",
            "PacketDataSerializer", "EnumProtocol", "NetworkManager");

    private static final String NMS_PREFIX = "net/minecraft/server/";
    private static final String CRAFTBUKKIT_PREFIX = "org/bukkit/craftbukkit/";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Spigot-mapped internals / NMS names that do not exist on unobfuscated 26.1+ jars";
    }

    @Override
    public List<Finding> check(ClassNode cls, ScanContext ctx) {
        if (!ctx.targetIsDeobfuscated()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        Set<String> reportedPerClass = new LinkedHashSet<>();

        for (MethodNode m : cls.methods) {
            if (m.instructions == null) {
                continue;
            }
            int line = Finding.NO_LINE;
            for (AbstractInsnNode insn : m.instructions) {
                line = LineTracker.update(insn, line);

                String hit = switch (insn) {
                    case TypeInsnNode t -> internalNameHit(t.desc);
                    case MethodInsnNode c -> internalNameHit(c.owner);
                    case FieldInsnNode f -> internalNameHit(f.owner);
                    case LdcInsnNode ldc when ldc.cst instanceof String s -> literalHit(s);
                    default -> null;
                };
                if (hit == null || !reportedPerClass.add(hit + "@" + m.name)) {
                    continue;
                }
                out.add(new Finding(
                        ID,
                        Severity.ERROR,
                        cls.name,
                        m.name,
                        line,
                        "References Spigot-mapped internal \"" + hit + "\"",
                        "Minecraft 26.1 ships unobfuscated server jars and Paper removed its "
                                + "remapper, so this name does not exist at runtime. Move to the "
                                + "Bukkit/Paper API if one covers the case, or rebuild against "
                                + "Mojang mappings with paperweight-userdev."));
            }
        }
        return out;
    }

    /** Matches an internal class name (slash-separated) against known legacy shapes. */
    private static String internalNameHit(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return null;
        }
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }

        if (VERSIONED_PACKAGE.matcher(name).find()) {
            return name.replace('/', '.');
        }
        // Post-1.17 NMS is package-split (net/minecraft/world/entity/...), which is
        // Mojang-mapped and fine. Only the flat legacy package is a problem.
        if (name.startsWith(NMS_PREFIX)) {
            String tail = name.substring(NMS_PREFIX.length());
            if (!tail.contains("/") && SPIGOT_MAPPED_TYPES.contains(tail)) {
                return name.replace('/', '.');
            }
        }
        if (name.startsWith(CRAFTBUKKIT_PREFIX)) {
            String tail = name.substring(CRAFTBUKKIT_PREFIX.length());
            if (VERSIONED_PACKAGE.matcher(tail).find()) {
                return name.replace('/', '.');
            }
        }
        return null;
    }

    /** Matches reflection strings such as "net.minecraft.server.v1_20_R3.EntityPlayer". */
    private static String literalHit(String lit) {
        if (lit.length() > 200 || isProse(lit)) {
            return null;
        }
        if (VERSIONED_PACKAGE.matcher(lit).find()) {
            return lit;
        }
        if (lit.startsWith("net.minecraft.server.")) {
            String tail = lit.substring("net.minecraft.server.".length());
            if (!tail.contains(".") && SPIGOT_MAPPED_TYPES.contains(tail)) {
                return lit;
            }
        }
        return null;
    }

    /**
     * True for literals that are human-readable text rather than a class name.
     *
     * <p>Plugins routinely mention version formats in error messages — EssentialsX has
     * {@code " is not in valid version format. e.g. v1_10_R1"}. Reporting those is a
     * false positive on code the author cannot "fix", and false positives are what get
     * a linter switched off.
     */
    private static boolean isProse(String lit) {
        // A class name or reflection string has no spaces; prose almost always does.
        return lit.indexOf(' ') >= 0;
    }
}
