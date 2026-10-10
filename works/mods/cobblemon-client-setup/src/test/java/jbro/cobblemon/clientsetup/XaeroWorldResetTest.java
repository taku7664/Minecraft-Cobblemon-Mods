package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class XaeroWorldResetTest {
    @TempDir Path gameDirectory;

    private Path config() { return gameDirectory.resolve("config"); }
    private Path cache(String kind, String folder) throws Exception {
        Path path = gameDirectory.resolve("xaero").resolve(kind).resolve(folder);
        Files.createDirectories(path);
        Files.writeString(path.resolve("waypoints.txt"), folder);
        return path;
    }
    private Path backup(String kind, String folder) {
        return gameDirectory.resolve("cobblemon_client_setup/backups").resolve(XaeroWorldReset.RULE).resolve(kind).resolve(folder);
    }

    @Test void liveServerCachesMoveAsideAndOthersStay() throws Exception {
        Path minimap = cache("minimap", "Multiplayer_210.207.108.196");
        Path worldMap = cache("world-map", "Multiplayer_210.207.108.196");
        Path other = cache("minimap", "Multiplayer_127.0.0.1");
        Path single = cache("world-map", "새로운 세계");

        assertEquals(2, XaeroWorldReset.apply(gameDirectory, config()));
        assertFalse(Files.exists(minimap));
        assertFalse(Files.exists(worldMap));
        assertEquals("Multiplayer_210.207.108.196", Files.readString(backup("minimap", "Multiplayer_210.207.108.196").resolve("waypoints.txt")));
        assertTrue(Files.exists(backup("world-map", "Multiplayer_210.207.108.196")));
        assertTrue(Files.exists(other));
        assertTrue(Files.exists(single));
    }

    @Test void runsOnlyOnceSoTheNewWorldsMapStays() throws Exception {
        cache("minimap", "Multiplayer_210.207.108.196");
        XaeroWorldReset.apply(gameDirectory, config());
        Path fresh = cache("minimap", "Multiplayer_210.207.108.196");
        assertEquals(0, XaeroWorldReset.apply(gameDirectory, config()));
        assertTrue(Files.exists(fresh));
    }

    @Test void freshInstallIsMarkedWithoutMaps() throws Exception {
        assertEquals(0, XaeroWorldReset.apply(gameDirectory, config()));
        assertEquals("true", SetupFiles.readState(config()).getProperty(XaeroWorldReset.RULE));
    }

    @Test void portSuffixMatchesButLookalikeAddressDoesNot() {
        assertTrue(XaeroWorldReset.isLiveServer("Multiplayer_210.207.108.196"));
        assertTrue(XaeroWorldReset.isLiveServer("Multiplayer_210.207.108.196_25565"));
        assertFalse(XaeroWorldReset.isLiveServer("Multiplayer_210.207.108.19"));
        assertFalse(XaeroWorldReset.isLiveServer("Multiplayer_210.207.108.1960"));
    }
}
