package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigSnapshot;
import jbro.cobblemon.bettermusic.config.FieldMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedMusicResourcePackTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOnlyExistingConfiguredTracksAsStreamingSoundEvents() throws Exception {
        Path config = temporaryDirectory.resolve("config");
        Path music = config.resolve("music");
        Files.createDirectories(music.resolve("field"));
        Files.write(music.resolve("field/forest.ogg"), new byte[] {1, 2, 3});
        var pack = new GeneratedMusicResourcePack(config, temporaryDirectory.resolve("resourcepacks"));

        var result = pack.generate(snapshot(List.of("field/forest.ogg", "field/missing.ogg")));

        assertEquals(List.of("field/missing.ogg"), result.missingTracks());
        Path root = pack.packDirectory();
        assertTrue(Files.isRegularFile(root.resolve("pack.mcmeta")));
        assertTrue(Files.isRegularFile(root.resolve(
            "assets/better_cobblemon_music/sounds/custom/field/forest.ogg"
        )));
        assertFalse(Files.exists(root.resolve(
            "assets/better_cobblemon_music/sounds/custom/field/missing.ogg"
        )));
        var sounds = JsonParser.parseString(Files.readString(root.resolve(
            "assets/better_cobblemon_music/sounds.json"
        ))).getAsJsonObject();
        var sound = sounds.getAsJsonObject("custom/field/forest")
            .getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals("better_cobblemon_music:custom/field/forest", sound.get("name").getAsString());
        assertTrue(sound.get("stream").getAsBoolean());
        assertFalse(sounds.has("custom/field/missing"));
    }

    @Test
    void includesTracksReferencedOnlyByBattleContentMappings() throws Exception {
        Path config = temporaryDirectory.resolve("config");
        Path music = config.resolve("music");
        Files.createDirectories(music.resolve("battle"));
        Files.write(music.resolve("battle/content_only.ogg"), new byte[] {4, 5, 6});
        var pack = new GeneratedMusicResourcePack(config, temporaryDirectory.resolve("resourcepacks"));
        var contentPlaylist = new PlaylistDefinition(
            PlaylistDefinition.Selection.SHUFFLE,
            1.0,
            0.0,
            List.of("battle/content_only.ogg")
        );

        var result = pack.generate(snapshot(
            List.of("battle/missing_default.ogg"),
            Map.of("example:content", contentPlaylist)
        ));

        assertEquals(List.of("battle/missing_default.ogg"), result.missingTracks());
        assertTrue(Files.isRegularFile(pack.packDirectory().resolve(
            "assets/better_cobblemon_music/sounds/custom/battle/content_only.ogg"
        )));
        var sounds = JsonParser.parseString(Files.readString(pack.packDirectory().resolve(
            "assets/better_cobblemon_music/sounds.json"
        ))).getAsJsonObject();
        assertTrue(sounds.has("custom/battle/content_only"));
    }

    private static BetterMusicConfigSnapshot snapshot(List<String> tracks) {
        return snapshot(tracks, Map.of());
    }

    private static BetterMusicConfigSnapshot snapshot(
        List<String> tracks,
        Map<String, PlaylistDefinition> content
    ) {
        var playlist = new PlaylistDefinition(PlaylistDefinition.Selection.SHUFFLE, 1.0, 0.0, tracks);
        var playback = new PlaybackSettings(
            1.0, 4.0, 0.0, 1.0, 1.0, PlaybackSettings.MissingCueBehavior.KEEP_ORIGINAL
        );
        var field = new FieldMusicConfig(playlist, Map.of(), Map.of(), Map.of(), Optional.empty());
        var battle = new BattleMusicConfig(
            playlist, playlist, playlist,
            content,
            Map.of("champion", playlist), Optional.empty(), Optional.empty(),
            List.of()
        );
        return new BetterMusicConfigSnapshot(playback, field, battle);
    }
}
