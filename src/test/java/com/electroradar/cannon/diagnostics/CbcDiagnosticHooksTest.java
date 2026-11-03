package com.electroradar.cannon.diagnostics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Проверяет совместимость перехватов с фактически подключённым jar CBC, без загрузки его сущностей. */
class CbcDiagnosticHooksTest {
    @Test void wrappedCallsExistInTheInstalledCbcClipMethod() throws Exception {
        String owner = "rbasamoyai/createbigcannons/munitions/AbstractCannonProjectile";
        Set<String> calls = new HashSet<>();
        try (var input = getClass().getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("clipAndDamage")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String type, String method, String descriptor, boolean itf) {
                            if (type.equals(owner)) calls.add(method + descriptor);
                        }
                    };
                }
            }, 0);
        }
        assertTrue(calls.contains("calculateBlockPenetration(Lrbasamoyai/createbigcannons/munitions/ProjectileContext;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;)L" + owner + "$ImpactResult;"));
        assertTrue(calls.contains("onHitEntity(Lnet/minecraft/world/entity/Entity;Lrbasamoyai/createbigcannons/munitions/ProjectileContext;)Z"));
        try (var input = getClass().getResourceAsStream("/electroradar.mixins.json")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("CannonDiagnosticsMixinPlugin"));
        }
    }
}
