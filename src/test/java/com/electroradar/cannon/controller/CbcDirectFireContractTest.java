package com.electroradar.cannon.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Контракт с установленным CBC: общий native tick перехватывается, прямой fireShot остаётся штатным. */
class CbcDirectFireContractTest {
    @Test void installedAutocannonUsesTheInterceptedPermissionGate() throws Exception {
        String owner = "rbasamoyai/createbigcannons/cannon_control/contraption/MountedAutocannonContraption";
        Set<String> methods = new HashSet<>();
        Set<String> tickCalls = new HashSet<>();
        try (var input = getClass().getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    methods.add(name + desc);
                    if (!name.equals("tick")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String type, String name, String desc, boolean itf) {
                            if (type.equals(owner)) tickCalls.add(name);
                        }
                    };
                }
            }, 0);
        }
        assertTrue(methods.contains("canBeFiredOnController(Lrbasamoyai/createbigcannons/cannon_control/ControlPitchContraption;)Z"));
        assertTrue(tickCalls.contains("canBeFiredOnController"));
        assertTrue(tickCalls.contains("fireShot"));
        try (var input = getClass().getResourceAsStream("/electroradar.cbc-control.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(config.contains("AutocannonDirectControlMixin"));
            assertFalse(config.contains("CannonDiagnosticsMixinPlugin"), "управление должно работать без диагностического флага");
        }
    }
}
