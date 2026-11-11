package jbro.cobblemon.clientsetup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class XaeroOverworldResetTest {
    @TempDir Path game;

    private Path config() { return game.resolve("config"); }

    private Path map(String kind, String server, String dimension) throws Exception {
        Path path = game.resolve("xaero").resolve(kind).resolve(server).resolve(dimension);
        Files.createDirectories(path);
        Files.writeString(path.resolve("map.txt"), dimension);
        return path;
    }

    private Path backup(String kind, String server, String dimension) {
        return game.resolve("ppakemon_client_setup/backups").resolve(XaeroOverworldReset.RULE)
            .resolve(kind).resolve(server).resolve(dimension);
    }

    @Test void movesOnlyOverworldAndKeepsOtherDimensionsAndServers() throws Exception {
        String server = "Multiplayer_210.207.108.196";
        Path minimap = map("minimap", server, "dim%0");
        Path worldMap = map("world-map", server, "null");
        Path roomWaypoints = map("minimap", server, "dim%myroom$rooms");
        Path roomMap = map("world-map", server, "myroom$rooms");
        Path endMap = map("world-map", server, "DIM1");
        Path otherServer = map("world-map", "Multiplayer_elsewhere", "null");
        Path serverConfig = worldMap.getParent().resolve("server_config.txt");
        Files.writeString(serverConfig, "keep");

        assertEquals(2, XaeroOverworldReset.apply(game, config()));
        assertFalse(Files.exists(minimap));
        assertFalse(Files.exists(worldMap));
        assertEquals("dim%0", Files.readString(backup("minimap", server, "dim%0").resolve("map.txt")));
        assertEquals("null", Files.readString(backup("world-map", server, "null").resolve("map.txt")));
        assertTrue(Files.exists(roomWaypoints));
        assertTrue(Files.exists(roomMap));
        assertTrue(Files.exists(endMap));
        assertTrue(Files.exists(otherServer));
        assertEquals("keep", Files.readString(serverConfig));
    }

    @Test void portSuffixWorksAndOnlyRunsOnce() throws Exception {
        String server = "Multiplayer_210.207.108.196_25565";
        Path map = map("minimap", server, "dim%0");
        Path lookalike = map("minimap", "Multiplayer_210.207.108.1960", "dim%0");
        assertEquals(1, XaeroOverworldReset.apply(game, config()));
        assertFalse(Files.exists(map));
        assertTrue(Files.exists(lookalike));
        Path fresh = map("minimap", server, "dim%0");
        assertEquals(0, XaeroOverworldReset.apply(game, config()));
        assertTrue(Files.exists(fresh));
    }

    @Test void existingBackupIsNeverOverwritten() throws Exception {
        String server = "Multiplayer_210.207.108.196";
        Path oldBackup = backup("world-map", server, "null");
        Files.createDirectories(oldBackup);
        Files.writeString(oldBackup.resolve("map.txt"), "old");
        map("world-map", server, "null");
        assertEquals(1, XaeroOverworldReset.apply(game, config()));
        assertEquals("old", Files.readString(oldBackup.resolve("map.txt")));
        assertEquals("null", Files.readString(backup("world-map", server, "null-2").resolve("map.txt")));
    }
}
