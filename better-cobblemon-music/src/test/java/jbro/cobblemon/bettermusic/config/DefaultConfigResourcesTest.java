package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertEquals(13, config.battle().pokemon().size());
    }

    private static InputStreamReader resource(String name) {
        var stream = DefaultConfigResourcesTest.class.getResourceAsStream(
            "/assets/better_cobblemon_music/config_defaults/" + name
        );
        assertNotNull(stream, name + " default resource must exist");
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
