package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GlobalMusicSettingsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void updatesOnlyGlobalPlaybackDefaultsAndPreservesMusicMappings() throws Exception {
        Path config = temporaryDirectory.resolve("music.json");
        String original = minimalConfig();
        Files.writeString(config, original);
        var before = JsonParser.parseString(original).getAsJsonObject();
        var settings = new GlobalMusicSettings(
            2.0,
            5.0,
            3.0,
            0.5,
            1.5,
            PlaylistDefinition.Selection.SEQUENTIAL,
            0.75
        );

        GlobalMusicSettingsStore.save(config, settings);

        assertEquals(settings, GlobalMusicSettingsStore.load(config));
        var after = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertEquals(before.get("field"), after.get("field"));
        assertEquals(before.get("battle"), after.get("battle"));
        assertEquals(2, after.get("schemaVersion").getAsInt());
    }

    private static String minimalConfig() {
        return """
            {
              "schemaVersion": 2,
              "scanIntervalSeconds": 1.0,
              "fieldChangeDelaySeconds": 4.0,
              "betweenTracksSeconds": 0.0,
              "fadeInSeconds": 1.0,
              "fadeOutSeconds": 1.0,
              "selection": "shuffle",
              "volume": 1.0,
              "field": {
                "default": "field/default.ogg",
                "dimensions": {},
                "biomes": {},
                "biomeTags": [],
                "biomePathContains": []
              },
              "battle": {
                "wild": "battle/wild.ogg",
                "trainer": "battle/trainer.ogg",
                "pvp": "battle/pvp.ogg",
                "pokemon": []
              }
            }
            """;
    }
}
