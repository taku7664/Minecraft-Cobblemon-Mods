package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientSetupMigrationTest {
    @TempDir Path game;
    private Path config() { return game.resolve("config"); }
    private Path legacy() { return config().resolve("cobblemon-client-defaults"); }
    private Path current() { return config().resolve("cobblemon-client-setup"); }

    @Test void migrationMovesSettingsAndCompletionRecordsWithoutChangingBytes() throws Exception {
        Files.createDirectories(legacy());
        String settings = "# personal modes\r\n[clc_hud]\r\nmode = \"ONCE\"\r\n[keybindings]\r\nmode = \"ONCE\"\r\n[xaero]\r\nmode = \"ONCE\"\r\n";
        String state = "clc-hud-v1=true\nkeybindings-voicechat-v1=true\nkeybindings-voicechat-microphone-v1=true\nxaero-xaerominimap-v1=true\n";
        Files.writeString(legacy().resolve("client.toml"), settings);
        Files.writeString(legacy().resolve("applied-defaults.properties"), state);
        Files.writeString(legacy().resolve("personal-note.txt"), "keep this file");
        String options = "version:3955\nkey_key.hide_icons:key.keyboard.j\n";
        Files.writeString(game.resolve("options.txt"), options);

        ClientSetup.migrateLegacyConfig(config());

        assertFalse(Files.exists(legacy()));
        assertEquals(settings, Files.readString(current().resolve("client.toml")));
        assertEquals(state, Files.readString(current().resolve("applied-defaults.properties")));
        assertEquals("keep this file", Files.readString(current().resolve("personal-note.txt")));
        assertEquals(ClientSetup.Result.ALREADY_APPLIED, ClientSetup.apply(config(), true));
        assertEquals(0, KeybindingSetup.apply(game, config(), Set.of("voicechat")));
        assertEquals(1, XaeroSetup.apply(game, config(), Set.of("xaerominimap")));
        assertTrue(Files.readString(game.resolve("options.txt")).startsWith(options));
        assertTrue(Files.readString(config().resolve("xaero/minimap/profiles/default.cfg"))
            .contains("waypoints_in_world = true"));
        String upgradedState = Files.readString(current().resolve("applied-defaults.properties"));
        assertTrue(upgradedState.contains("xaero-xaerominimap-v1=true"));
        assertTrue(upgradedState.contains("xaero-xaerominimap-v3=true"));
    }

    @Test void freshInstallationDoesNotCreateLegacyOrEmptySettingsDirectory() throws Exception {
        ClientSetup.migrateLegacyConfig(config());
        assertFalse(Files.exists(legacy()));
        assertFalse(Files.exists(current()));
    }

    @Test void migrationIsIdempotent() throws Exception {
        Files.createDirectories(legacy());
        Files.writeString(legacy().resolve("client.toml"), "[keybindings]\nmode = \"ONCE\"\n");
        ClientSetup.migrateLegacyConfig(config());
        byte[] first = Files.readAllBytes(current().resolve("client.toml"));
        ClientSetup.migrateLegacyConfig(config());
        assertArrayEquals(first, Files.readAllBytes(current().resolve("client.toml")));
        assertFalse(Files.exists(legacy()));
    }

    @Test void conflictingDirectoriesFailWithoutOverwritingEither() throws Exception {
        Files.createDirectories(legacy());
        Files.createDirectories(current());
        Files.writeString(legacy().resolve("client.toml"), "old settings");
        Files.writeString(current().resolve("client.toml"), "new settings");
        assertThrows(IOException.class, () -> ClientSetup.migrateLegacyConfig(config()));
        assertEquals("old settings", Files.readString(legacy().resolve("client.toml")));
        assertEquals("new settings", Files.readString(current().resolve("client.toml")));
    }

    @Test void nonDirectoryLegacyPathFailsWithoutRemovingIt() throws Exception {
        Files.createDirectories(config());
        Files.writeString(legacy(), "not a directory");
        assertThrows(IOException.class, () -> ClientSetup.migrateLegacyConfig(config()));
        assertEquals("not a directory", Files.readString(legacy()));
        assertFalse(Files.exists(current()));
    }
}
