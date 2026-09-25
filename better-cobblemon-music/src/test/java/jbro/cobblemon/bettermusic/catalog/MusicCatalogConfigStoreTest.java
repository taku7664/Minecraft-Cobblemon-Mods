package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MusicCatalogConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsSettingsButLeavesOverridesForCatalogAwareMigration() throws Exception {
        MusicCatalogConfigStore store = new MusicCatalogConfigStore(temporaryDirectory);

        MusicCatalogSettings settings = store.initializeSettings("cobleserver:official");

        assertEquals("cobleserver:official", settings.basePackId());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("settings.json")));
        assertFalse(Files.exists(temporaryDirectory.resolve("overrides.json")));
        assertEquals(MusicMappingOverrides.empty(), store.loadOverrides());
    }

    @Test
    void importsGlobalPlaybackValuesFromLegacyMusicJsonWithoutChangingIt() throws Exception {
        Path legacy = temporaryDirectory.resolve("music.json");
        String legacyJson = legacyJson().replace("\"volume\": 1.0", "\"volume\": 1.75")
            .replace("\"fadeInSeconds\": 1.0", "\"fadeInSeconds\": 2.5");
        Files.writeString(legacy, legacyJson, StandardCharsets.UTF_8);
        MusicCatalogConfigStore store = new MusicCatalogConfigStore(temporaryDirectory);

        MusicCatalogSettings settings = store.initializeSettings("cobleserver:official");

        assertEquals(1.75, settings.volume());
        assertEquals(2.5, settings.playback().fadeInSeconds());
        assertEquals(legacyJson, Files.readString(legacy, StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsOverridesAtomically() throws Exception {
        MusicCatalogConfigStore store = new MusicCatalogConfigStore(temporaryDirectory);
        MusicMappingOverrides overrides = new MusicMappingOverrides(
            MusicMappingOverrides.Field.empty(),
            new MusicMappingOverrides.Battle(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("cobblemon_more_battle_content:battle_tower", "cobleserver:track/battle/trainer"),
                Optional.empty(), Optional.empty(), java.util.List.of()
            )
        );

        assertTrue(store.saveOverridesIfMissing(overrides));
        assertFalse(store.saveOverridesIfMissing(MusicMappingOverrides.empty()));
        assertEquals(overrides, store.loadOverrides());
    }

    static String legacyJson() {
        return """
            {
              "schemaVersion": 1,
              "scanIntervalSeconds": 1.0,
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
                "biomePathContains": {},
                "underground": "field/cave.ogg"
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": "battle/trainer.ogg",
                "pvp": "battle/pvp.ogg",
                "content": {},
                "legendary": "battle/legendary.ogg",
                "ultraBeast": "battle/ultra_beast.ogg",
                "pokemon": []
              }
            }
            """;
    }
}
