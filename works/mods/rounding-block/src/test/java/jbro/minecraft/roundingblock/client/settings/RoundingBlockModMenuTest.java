package jbro.minecraft.roundingblock.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundingBlockModMenuTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void modMenuWithoutClothConfigCanDiscoverTheNativeScreenFactory() throws Exception {
        // Isolate the real entrypoint from Cloth Config and the Minecraft runtime.
        // Stubs supply only the external contract needed by Mod Menu discovery.
        var arguments = new ArrayList<String>();
        arguments.add("-d");
        arguments.add(temporaryDirectory.toString());
        stub(arguments, "net.minecraft.client.gui.screens.Screen", """
            package net.minecraft.client.gui.screens;
            public class Screen {}
            """);
        stub(arguments, "com.terraformersmc.modmenu.api.ConfigScreenFactory", """
            package com.terraformersmc.modmenu.api;
            import net.minecraft.client.gui.screens.Screen;
            public interface ConfigScreenFactory<T extends Screen> {
                T create(Screen parent);
            }
            """);
        stub(arguments, "com.terraformersmc.modmenu.api.ModMenuApi", """
            package com.terraformersmc.modmenu.api;
            public interface ModMenuApi {
                ConfigScreenFactory<?> getModConfigScreenFactory();
            }
            """);
        stub(arguments, "net.fabricmc.loader.api.FabricLoader", """
            package net.fabricmc.loader.api;
            public class FabricLoader {
                public static FabricLoader getInstance() { return new FabricLoader(); }
                public boolean isModLoaded(String id) { return false; }
                public java.nio.file.Path getConfigDir() { return java.nio.file.Path.of("config"); }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, arguments.toArray(String[]::new)
        ));
        URL mainClasses = RoundingBlockConfig.class.getProtectionDomain().getCodeSource().getLocation();
        URL logging = org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(
            new URL[] {temporaryDirectory.toUri().toURL(), mainClasses, logging},
            getClass().getClassLoader()
        ) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("me.shedaniel.")) throw new ClassNotFoundException(name);
                if (name.startsWith("com.terraformersmc.modmenu.")
                    || name.equals("net.fabricmc.loader.api.FabricLoader")
                    || name.equals("jbro.minecraft.roundingblock.client.settings.RoundingBlockModMenu")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> integration = loader.loadClass(
                "jbro.minecraft.roundingblock.client.settings.RoundingBlockModMenu"
            );
            Object entrypoint = integration.getConstructor().newInstance();
            Object factory = integration.getMethod("getModConfigScreenFactory").invoke(entrypoint);
            assertNotNull(factory);
        }
    }

    private void stub(ArrayList<String> arguments, String name, String source) throws Exception {
        Path file = temporaryDirectory.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        arguments.add(file.toString());
    }
}
