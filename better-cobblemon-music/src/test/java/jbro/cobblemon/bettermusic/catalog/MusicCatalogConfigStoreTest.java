package jbro.cobblemon.bettermusic.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        var json = com.google.gson.JsonParser.parseString(Files.readString(store.settingsFile())).getAsJsonObject();
        assertTrue(json.get("underwaterEffectsEnabled").getAsBoolean());
        assertEquals(0.35, json.get("underwaterEffectStrength").getAsDouble(), 0.0001);
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
    void acceptsAndPreservesTheNowPlayingToggleWithoutChangingOtherSettings() throws Exception {
        var store = new MusicCatalogConfigStore(temporaryDirectory);
        var original = store.initializeSettings("cobleserver:official");
        Path file = store.settingsFile();
        String json = Files.readString(file).replace("\"volume\": 1.0", "\"volume\": 1.75");
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("nowPlayingEnabled", false);
        Files.writeString(file, root.toString());

        var loaded = store.loadSettings();
        store.saveSettings(loaded);

        var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertFalse(saved.get("nowPlayingEnabled").getAsBoolean());
        assertEquals(1.75, loaded.volume());
        assertEquals(original.playback(), loaded.playback());
        assertEquals(original.audioEffects(), loaded.audioEffects());
    }

    @Test
    void olderSettingsDefaultAnnouncementsOnAndRejectNonBooleanValues() throws Exception {
        var store = new MusicCatalogConfigStore(temporaryDirectory);
        store.initializeSettings("cobleserver:official");
        var root = com.google.gson.JsonParser.parseString(Files.readString(store.settingsFile())).getAsJsonObject();
        root.remove("nowPlayingEnabled");
        Files.writeString(store.settingsFile(), root.toString());
        assertTrue(store.loadSettings().nowPlayingEnabled());
        for (var invalid : java.util.List.of(com.google.gson.JsonParser.parseString("0"),
            com.google.gson.JsonParser.parseString("\"false\""), com.google.gson.JsonNull.INSTANCE)) {
            root.add("nowPlayingEnabled", invalid);
            Files.writeString(store.settingsFile(), root.toString());
            assertThrows(CatalogValidationException.class, store::loadSettings);
        }
    }

    @Test
    void olderSettingsDefaultUnderwaterEffectsAndSavedValuesRoundTrip() throws Exception {
        var store = new MusicCatalogConfigStore(temporaryDirectory);
        store.initializeSettings("cobleserver:official");
        var root = com.google.gson.JsonParser.parseString(Files.readString(store.settingsFile())).getAsJsonObject();
        root.remove("underwaterEffectsEnabled");
        root.remove("underwaterEffectStrength");
        Files.writeString(store.settingsFile(), root.toString());
        var original = store.loadSettings();
        assertTrue(original.audioEffects().underwaterEffectsEnabled());
        assertEquals(0.35, original.audioEffects().underwaterEffectStrength(), 0.0001);

        root.addProperty("underwaterEffectsEnabled", false);
        root.addProperty("underwaterEffectStrength", 0.7);
        Files.writeString(store.settingsFile(), root.toString());
        var changed = store.loadSettings();
        store.saveSettings(changed);
        assertFalse(store.loadSettings().audioEffects().underwaterEffectsEnabled());
        assertEquals(0.7, store.loadSettings().audioEffects().underwaterEffectStrength(), 0.0001);
        assertEquals(original.playback(), changed.playback());

        root.addProperty("underwaterEffectStrength", 1.01);
        Files.writeString(store.settingsFile(), root.toString());
        assertThrows(CatalogValidationException.class, store::loadSettings);
        root.addProperty("underwaterEffectStrength", 0.7);
        root.addProperty("underwaterEffectsEnabled", "false");
        Files.writeString(store.settingsFile(), root.toString());
        assertThrows(CatalogValidationException.class, store::loadSettings);
    }

    @Test
    void roundTripsOverridesAtomically() throws Exception {
        MusicCatalogConfigStore store = new MusicCatalogConfigStore(temporaryDirectory);
        MusicMappingOverrides overrides = new MusicMappingOverrides(
            MusicMappingOverrides.Field.empty(),
            new MusicMappingOverrides.Battle(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("more_cobblemon_contents:battle_tower", "cobleserver:track/battle/trainer"),
                Optional.empty(), Optional.empty(), java.util.List.of()
            ),
            Map.of("more_cobblemon_contents:hub/shop", "cobleserver:field_plaza")
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
