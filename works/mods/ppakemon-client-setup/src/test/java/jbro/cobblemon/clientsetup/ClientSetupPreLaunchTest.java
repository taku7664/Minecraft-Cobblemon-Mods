package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientSetupPreLaunchTest {
    @TempDir Path game;

    @Test void existingOptionsAreProtectedAndEmptyOptionsGetFirstLaunchDefaults() throws Exception {
        assertFalse(ClientSetupPreLaunch.hasExistingOptions(game));
        Files.writeString(game.resolve("options.txt"), "");
        assertFalse(ClientSetupPreLaunch.hasExistingOptions(game));
        Files.writeString(game.resolve("options.txt"), "version:3955\nresourcePacks:[\"file/personal.zip\"]\n");
        assertTrue(ClientSetupPreLaunch.hasExistingOptions(game));
    }
}
