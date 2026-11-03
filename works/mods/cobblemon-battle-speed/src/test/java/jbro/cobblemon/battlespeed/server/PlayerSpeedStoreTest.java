package jbro.cobblemon.battlespeed.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSpeedStoreTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void choicesSurviveSaveAndLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data").resolve("players.json");
        var store = new PlayerSpeedStore(file);
        store.set(ALICE, 3);
        store.save();

        var reloaded = new PlayerSpeedStore(file);
        reloaded.load();
        assertEquals(Optional.of(3), reloaded.level(ALICE));
        assertTrue(Files.notExists(file.resolveSibling("players.json.tmp")));
    }

    @Test
    void missingFileMeansNoChoices(@TempDir Path dir) throws Exception {
        var store = new PlayerSpeedStore(dir.resolve("none.json"));
        store.load();
        assertEquals(Optional.empty(), store.level(ALICE));
    }

    @Test
    void badLinesAreSkippedButBrokenFilesFail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("players.json");
        Files.writeString(file, "{\"not-a-uuid\": 2, \"" + ALICE + "\": 4}", StandardCharsets.UTF_8);
        var store = new PlayerSpeedStore(file);
        store.load();
        assertEquals(Optional.of(4), store.level(ALICE));

        Files.writeString(file, "{\"" + ALICE + "\": ", StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, store::load);
    }
}
