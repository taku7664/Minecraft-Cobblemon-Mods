package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BetterMusicConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializeCreatesOnlyMissingDefaultsAndPreservesExistingUserFile() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("better_cobblemon_music");
        Files.createDirectories(configDirectory);
        String customConfig = musicJson(2.0);
        Files.writeString(configDirectory.resolve("music.json"), customConfig, StandardCharsets.UTF_8);

        var manager = new BetterMusicConfigManager(configDirectory);
        var result = manager.initialize();

        assertEquals(BetterMusicConfigManager.Outcome.APPLIED, result.outcome());
        assertEquals(2.0, manager.activeSnapshot().orElseThrow().playback().scanIntervalSeconds());
        assertEquals(customConfig, Files.readString(configDirectory.resolve("music.json")));
        assertTrue(Files.isDirectory(configDirectory.resolve("music")));
        assertFalse(Files.exists(configDirectory.resolve("playback.json")));
        assertFalse(Files.exists(configDirectory.resolve("cues.json")));
        assertFalse(Files.exists(configDirectory.resolve("field_rules.json")));
    }

    @Test
    void invalidReloadRetainsTheExactLastGoodSnapshot() throws Exception {
        var manager = new BetterMusicConfigManager(temporaryDirectory.resolve("config"));
        assertEquals(BetterMusicConfigManager.Outcome.APPLIED, manager.initialize().outcome());
        var lastGood = manager.activeSnapshot().orElseThrow();

        Files.writeString(
            manager.configDirectory().resolve("music.json"),
            "{\"schemaVersion\":1}",
            StandardCharsets.UTF_8
        );
        var result = manager.reload();

        assertEquals(BetterMusicConfigManager.Outcome.RETAINED_LAST_GOOD, result.outcome());
        assertTrue(result.message().contains("music.json"));
        assertTrue(result.message().contains("$.scanIntervalSeconds"));
        assertSame(lastGood, manager.activeSnapshot().orElseThrow());
    }

    @Test
    void invalidFirstUserConfigFallsBackToBundledDefaultsWithoutOverwritingIt() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        Files.createDirectories(configDirectory);
        Path music = configDirectory.resolve("music.json");
        Files.writeString(music, "{\"schemaVersion\":1}", StandardCharsets.UTF_8);

        var manager = new BetterMusicConfigManager(configDirectory);
        var result = manager.initialize();

        assertEquals(BetterMusicConfigManager.Outcome.FALLBACK_TO_BUNDLED, result.outcome());
        assertEquals(1.0, manager.activeSnapshot().orElseThrow().playback().scanIntervalSeconds());
        assertEquals("{\"schemaVersion\":1}", Files.readString(music));
        assertFalse(result.success());
    }

    @Test
    void successfulReloadAtomicallyReplacesTheSnapshot() throws Exception {
        var manager = new BetterMusicConfigManager(temporaryDirectory.resolve("config"));
        manager.initialize();
        var before = manager.activeSnapshot().orElseThrow();
        Files.writeString(
            manager.configDirectory().resolve("music.json"),
            musicJson(3.0),
            StandardCharsets.UTF_8
        );

        var result = manager.reload();

        assertEquals(BetterMusicConfigManager.Outcome.APPLIED, result.outcome());
        assertEquals(3.0, manager.activeSnapshot().orElseThrow().playback().scanIntervalSeconds());
        assertFalse(before == manager.activeSnapshot().orElseThrow());
        assertTrue(result.success());
    }

    private static String musicJson(double scanIntervalSeconds) {
        return """
            {
              "schemaVersion": 1,
              "scanIntervalSeconds": %s,
              "fieldChangeDelaySeconds": 4.0,
              "betweenTracksSeconds": 0.0,
              "fadeInSeconds": 1.0,
              "fadeOutSeconds": 1.0,
              "selection": "shuffle",
              "volume": 1.0,
              "field": {
                "default": "field/plains.ogg",
                "dimensions": {},
                "biomes": {},
                "biomePathContains": {}
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": "battle/trainer.ogg",
                "pvp": "battle/pvp.ogg",
                "pokemon": []
              }
            }
            """.formatted(scanIntervalSeconds);
    }
}
