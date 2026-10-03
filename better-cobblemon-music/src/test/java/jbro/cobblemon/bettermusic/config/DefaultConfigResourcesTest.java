package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DefaultConfigResourcesTest {
    @Test
    void bundledDefaultsAreMutuallyConsistent() {
        var config = MusicConfigParser.parse(resource("music.json"));

        assertEquals(1.0, config.playback().scanIntervalSeconds());
        assertEquals(4.0, config.playback().fieldChangeDelaySeconds());
        assertEquals(1.0, config.playback().fadeInSeconds());
        assertEquals(1.0, config.playback().fadeOutSeconds());
        assertEquals(3, config.field().dimensions().size());
        assertEquals(15, config.field().biomePathContains().size());
        assertEquals(48, config.battle().pokemon().size());
    }

    @Test
    void announcementToggleAndTooltipAreLocalizedInBothLanguages() throws Exception {
        for (String language : java.util.List.of("ko_kr", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/better_cobblemon_music/lang/" + language + ".json")) {
                assertNotNull(stream);
                var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String suffix : java.util.List.of("", ".tooltip")) {
                    assertTrue(!root.get("better_cobblemon_music.config.now_playing_enabled" + suffix).getAsString().isBlank());
                }
            }
        }
    }

    @Test
    void underwaterAudioControlsAreLocalizedInBothLanguages() throws Exception {
        for (String language : java.util.List.of("ko_kr", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/better_cobblemon_music/lang/" + language + ".json")) {
                assertNotNull(stream);
                var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : java.util.List.of("underwater_effects_enabled", "underwater_effect_strength")) {
                    for (String suffix : java.util.List.of("", ".tooltip")) {
                        assertTrue(!root.get("better_cobblemon_music.config." + key + suffix).getAsString().isBlank());
                    }
                }
            }
        }
    }

    @Test
    void currentMusicCommandMessagesAreLocalizedWithTheMusicHeader() throws Exception {
        for (String language : java.util.List.of("ko_kr", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/better_cobblemon_music/lang/" + language + ".json")) {
                assertNotNull(stream);
                var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                var playing = root.get("better_cobblemon_music.command.now_playing").getAsString();
                var absent = root.get("better_cobblemon_music.command.no_music").getAsString();
                assertTrue(playing.startsWith("[Music] "));
                assertTrue(playing.contains("%s"));
                assertTrue(absent.startsWith("[Music] "));
                assertTrue(!absent.contains("%s"));
            }
        }
    }

    @Test
    void bundledMusicConfigUsesSchemaTwoOrderedBiomeRules() {
        var root = JsonParser.parseReader(resource("music.json")).getAsJsonObject();

        assertEquals(2, root.get("schemaVersion").getAsInt());
        var field = root.getAsJsonObject("field");
        assertTrue(field.get("biomeTags").isJsonArray());
        assertTrue(field.get("biomePathContains").isJsonArray());
    }

    private static InputStreamReader resource(String name) {
        var stream = DefaultConfigResourcesTest.class.getResourceAsStream(
            "/assets/better_cobblemon_music/legacy_defaults/" + name
        );
        assertNotNull(stream, name + " default resource must exist");
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
