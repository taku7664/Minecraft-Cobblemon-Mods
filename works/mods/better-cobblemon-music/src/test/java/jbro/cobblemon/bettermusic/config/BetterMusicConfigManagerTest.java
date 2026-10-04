package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BetterMusicConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializeCreatesOnlySettingsAndWaitsForResourceCatalogs() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("better_cobblemon_music");
        var manager = new BetterMusicConfigManager(configDirectory);

        var result = manager.initialize();

        assertEquals(BetterMusicConfigManager.Outcome.INITIALIZED, result.outcome());
        assertTrue(Files.isRegularFile(configDirectory.resolve("settings.json")));
        assertFalse(Files.exists(configDirectory.resolve("music.json")));
        assertFalse(Files.exists(configDirectory.resolve("music")));
        assertTrue(manager.activeConfiguration().isEmpty());
    }

    @Test
    void resourceReloadCompilesCatalogAndPublishesAtomically() throws Exception {
        var manager = new BetterMusicConfigManager(temporaryDirectory.resolve("config"));
        manager.initialize();

        var result = manager.reloadCatalogs(List.of(document(validCatalog("better_cobblemon_music:music.one"))));

        assertEquals(BetterMusicConfigManager.Outcome.APPLIED, result.outcome());
        assertEquals(1, result.revision());
        var active = manager.activeConfiguration().orElseThrow();
        assertEquals("better_cobblemon_music:music.one", active.trackEvents().get("better_cobblemon_music:one"));
        assertSame(active.snapshot(), manager.activeSnapshot().orElseThrow());
    }

    @Test
    void failedResourceReloadKeepsTheExactLastGoodConfiguration() throws Exception {
        var manager = new BetterMusicConfigManager(temporaryDirectory.resolve("config"));
        manager.initialize();
        manager.reloadCatalogs(List.of(document(validCatalog("better_cobblemon_music:music.one"))));
        var before = manager.activeConfiguration().orElseThrow();

        var result = manager.reloadCatalogs(List.of(new BetterMusicConfigManager.CatalogDocument(
            "broken-pack", "{\"schemaVersion\":1}"
        )));

        assertEquals(BetterMusicConfigManager.Outcome.RETAINED_LAST_GOOD, result.outcome());
        assertEquals(1, result.revision());
        assertSame(before, manager.activeConfiguration().orElseThrow());
        assertTrue(result.message().contains("broken-pack"));
    }

    @Test
    void settingsChangesApplyOnTheNextCatalogReload() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        var manager = new BetterMusicConfigManager(configDirectory);
        manager.initialize();
        var catalogs = List.of(document(validCatalog("better_cobblemon_music:music.one")));
        manager.reloadCatalogs(catalogs);
        String settings = Files.readString(configDirectory.resolve("settings.json"));
        Files.writeString(
            configDirectory.resolve("settings.json"),
            settings.replace("\"volume\": 1.0", "\"volume\": 0.4"),
            StandardCharsets.UTF_8
        );

        var result = manager.reloadCatalogs(catalogs);

        assertEquals(BetterMusicConfigManager.Outcome.APPLIED, result.outcome());
        assertEquals(2, result.revision());
        assertEquals(0.4, manager.activeConfiguration().orElseThrow().playlists().get("better_cobblemon_music:one").volume());
    }

    @Test
    void exposesDiscoveredBasePacksEvenWhenTheSelectedPackCannotActivate() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("config");
        var manager = new BetterMusicConfigManager(configDirectory);
        manager.initialize();
        String settings = Files.readString(configDirectory.resolve("settings.json"));
        Files.writeString(
            configDirectory.resolve("settings.json"),
            settings.replace("better_cobblemon_music:official", "missing:selected"),
            StandardCharsets.UTF_8
        );

        var result = manager.reloadCatalogs(List.of(document(validCatalog("better_cobblemon_music:music.one"))));

        assertEquals(BetterMusicConfigManager.Outcome.NO_VALID_CONFIG, result.outcome());
        assertEquals(java.util.Set.of("better_cobblemon_music:official"), manager.availableBasePackIds());
    }

    private static BetterMusicConfigManager.CatalogDocument document(String json) {
        return new BetterMusicConfigManager.CatalogDocument("test-pack", json);
    }

    private static String validCatalog(String event) {
        return """
            {
              "schemaVersion": 1,
              "packId": "better_cobblemon_music:official",
              "kind": "base",
              "tracks": {
                "better_cobblemon_music:one": {"event": "%s", "title": "One", "legacyPaths": ["one.ogg"]}
              },
              "playlists": {
                "better_cobblemon_music:one": {"tracks": ["better_cobblemon_music:one"]}
              },
              "mappings": {
                "field": {"default": "better_cobblemon_music:one"},
                "battle": {
                  "wild": "better_cobblemon_music:one",
                  "trainer": "better_cobblemon_music:one",
                  "pvp": "better_cobblemon_music:one"
                }
              },
              "audioEvents": {
                "hitNormal": "better_cobblemon_music:hit.normal",
                "hitSuperEffective": "better_cobblemon_music:hit.super",
                "hitNotVeryEffective": "better_cobblemon_music:hit.weak",
                "heartbeat": "better_cobblemon_music:heartbeat"
              }
            }
            """.formatted(event);
    }
}
