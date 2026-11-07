package com.electroradar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mixin запрещает напрямую загружать любой класс из пакета своего конфига: обычный класс там
 * падает с IllegalClassLoadError при первом обращении. В пакете конфига допустимы только mixin
 * (включая accessor) и плагины конфигов, которые Mixin загружает сам.
 */
class MixinPackageContractTest {
    private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PLUGIN = Pattern.compile("\"plugin\"\\s*:\\s*\"([^\"]+)\"");

    private static final String[] CONFIGS = {"electroradar.mixins.json", "electroradar.cbc-control.mixins.json",
            "electroradar.sable.mixins.json"};

    @ParameterizedTest
    @ValueSource(strings = {"electroradar.mixins.json", "electroradar.cbc-control.mixins.json",
            "electroradar.sable.mixins.json"})
    void mixinPackagesContainOnlyMixins(String config) throws Exception {
        String json = read(config);
        Matcher pkg = PACKAGE.matcher(json);
        assertTrue(pkg.find(), config);
        // Конфиги могут делить пакет: плагин любого из них Mixin загружает сам.
        Set<String> plugins = new HashSet<>();
        for (String other : CONFIGS) {
            Matcher plugin = PLUGIN.matcher(read(other));
            if (plugin.find()) plugins.add(plugin.group(1));
        }
        String path = pkg.group(1).replace('.', '/');
        var url = getClass().getClassLoader().getResource(path);
        assertNotNull(url, path);
        List<String> offenders = new ArrayList<>();
        List<Path> classes;
        try (Stream<Path> files = Files.walk(Path.of(url.toURI()))) {
            classes = files.filter(file -> file.toString().endsWith(".class")).toList();
        }
        assertFalse(classes.isEmpty(), path);
        for (Path file : classes) {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            String name = reader.getClassName().replace('/', '.');
            // Вложенные классы mixin (лямбды, записи) тоже нельзя загружать напрямую, поэтому без исключений.
            if (plugins.contains(name) || isMixin(reader)) continue;
            offenders.add(name);
        }
        assertTrue(offenders.isEmpty(), config + ": non-mixin classes in mixin package " + offenders);
    }

    private String read(String config) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + config)) {
            assertNotNull(input, config);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isMixin(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return found[0];
    }
}
