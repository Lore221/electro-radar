package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Контракт с установленным CBC: штатный рецепт ставит любой {@link rbasamoyai.createbigcannons.munitions.fuzes.FuzeItem}
 * в зенитный патрон, патрон передаёт его снаряду, снаряд вызывает его после хода, а взрыватель
 * перехвата вызывает штатный подрыв.
 */
class CbcInterceptFuzeContractTest {
    private static final String FUZE_ITEM = "rbasamoyai/createbigcannons/munitions/fuzes/FuzeItem";
    private static final String FLAK = "rbasamoyai/createbigcannons/munitions/autocannon/flak/FlakAutocannonProjectile";

    @Test void installedFlakProjectileExposesDetonationAndRemovalFlag() throws Exception {
        assertTrue(scan(FLAK, null).members.contains("detonate(Lnet/minecraft/core/Position;)V"));
        assertTrue(scan("rbasamoyai/createbigcannons/munitions/AbstractCannonProjectile", null).members
                .contains("removeNextTick:Z"));
        try (var input = getClass().getResourceAsStream("/electroradar.cbc-control.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(config.contains("FlakProjectileAccess"));
            assertTrue(config.contains("CannonProjectileAccess"));
        }
    }

    @Test void installedFlakRoundAcceptsAnyFuzeItem() throws Exception {
        assertTrue(scan("rbasamoyai/createbigcannons/crafting/munition_assembly/MunitionFuzingRecipe", "matches")
                .types.contains(FUZE_ITEM), "рецепт проверяет общий FuzeItem, а не конкретные взрыватели CBC");
        Scan round = scan("rbasamoyai/createbigcannons/munitions/autocannon/flak/FlakAutocannonRoundItem",
                "getAutocannonProjectile");
        assertTrue(round.calls.contains(FLAK + ".setFuze"));
        Scan tick = scan(FLAK, "lambda$tick$0");
        assertTrue(tick.calls.contains(FUZE_ITEM + ".onProjectileTick"));
    }

    private Scan scan(String owner, String method) throws Exception {
        Scan scan = new Scan();
        try (var input = getClass().getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    scan.members.add(name + ":" + desc);
                    return null;
                }

                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    scan.members.add(name + desc);
                    if (!name.equals(method)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitTypeInsn(int opcode, String type) {
                            scan.types.add(type);
                        }

                        @Override public void visitMethodInsn(int opcode, String type, String name, String desc, boolean itf) {
                            scan.calls.add(type + "." + name);
                        }
                    };
                }
            }, 0);
        }
        return scan;
    }

    private static final class Scan {
        private final Set<String> members = new HashSet<>();
        private final Set<String> types = new HashSet<>();
        private final Set<String> calls = new HashSet<>();
    }
}
