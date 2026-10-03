package jbro.cobblemon.clientdefaults;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

final class MinecraftOptionsCompatibilityTest {
    @TempDir Path gameDirectory;

    @BeforeAll static void initializeGameVersion() { SharedConstants.tryDetectVersion(); }

    private static CompoundTag readWithMinecraftMigration(String content) {
        CompoundTag options = new CompoundTag();
        content.lines().forEach(line -> {
            int colon = line.indexOf(':');
            if (colon > 0) options.putString(line.substring(0, colon), line.substring(colon + 1));
        });
        int version = 0;
        try { version = Integer.parseInt(options.getString("version")); }
        catch (NumberFormatException ignored) { }
        return DataFixTypes.OPTIONS.update(DataFixers.getDataFixer(), options, version, 3955);
    }

    @Test void unversionedModernBindingsFailMinecraftLegacyMigration() {
        assertThrows(NumberFormatException.class, () -> readWithMinecraftMigration("key_key.hide_icons:key.keyboard.unknown\n"));
    }

    @Test void freshOptionsPassMinecraftMigrationAndPreserveDisabledKey() throws Exception {
        KeybindingDefaults.apply(gameDirectory, gameDirectory.resolve("config"), Set.of("voicechat"));
        String content = Files.readString(gameDirectory.resolve("options.txt"));
        assertTrue(content.startsWith("version:3955\n"));
        CompoundTag migrated = readWithMinecraftMigration(content);
        assertEquals("key.keyboard.unknown", migrated.getString("key_key.hide_icons"));
    }

    @Test void freshXaeroOnlyOptionsPassMinecraftMigration() throws Exception {
        XaeroDefaults.apply(gameDirectory, gameDirectory.resolve("config"), Set.of("xaerominimap", "xaeroworldmap"));
        CompoundTag migrated = readWithMinecraftMigration(Files.readString(gameDirectory.resolve("options.txt")));
        assertEquals("key.keyboard.j", migrated.getString("key_gui.xaero_open_map"));
        assertEquals("key.keyboard.unknown", migrated.getString("key_gui.xaero_instant_waypoint"));
    }

    @Test void expectedDataVersionMatchesBundledMinecraftVersion() throws Exception {
        try (var stream = getClass().getResourceAsStream("/version.json")) {
            assertNotNull(stream);
            var version = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("1.21.1", version.get("id").getAsString());
            assertEquals(3955, version.get("world_version").getAsInt());
        }
    }

    @Test void emptyOptionsFileIsSeededWithCurrentVersionToo() throws Exception {
        Files.writeString(gameDirectory.resolve("options.txt"), "");
        KeybindingDefaults.apply(gameDirectory, gameDirectory.resolve("config"), Set.of("voicechat"));
        CompoundTag migrated = readWithMinecraftMigration(Files.readString(gameDirectory.resolve("options.txt")));
        assertEquals("3955", migrated.getString("version"));
        assertEquals("key.keyboard.unknown", migrated.getString("key_key.hide_icons"));
    }

    @Test void existingVersionAndUnrelatedOptionsRemainUnchanged() throws Exception {
        String original = "version:3954\r\nfullscreen:true\r\nkey_key.hide_icons:key.keyboard.h\r\n";
        Files.writeString(gameDirectory.resolve("options.txt"), original);
        KeybindingDefaults.apply(gameDirectory, gameDirectory.resolve("config"), Set.of("voicechat"));
        String updated = Files.readString(gameDirectory.resolve("options.txt"));
        assertEquals("version:3954\r\nfullscreen:true\r\nkey_key.hide_icons:key.keyboard.unknown\r\n", updated);
        assertEquals("key.keyboard.unknown", readWithMinecraftMigration(updated).getString("key_key.hide_icons"));
    }
}
