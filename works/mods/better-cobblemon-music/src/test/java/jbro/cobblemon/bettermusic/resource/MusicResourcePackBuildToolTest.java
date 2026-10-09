package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MusicResourcePackBuildToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesOneEventAndOneImplicitPlaylistPerMusicOgg() throws IOException {
        Path source = temporaryDirectory.resolve("source");
        Path output = temporaryDirectory.resolve("output");
        write(source.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}");
        writeOgg(source.resolve("assets/better_cobblemon_music/sounds/music/field/plains/theme.ogg"));
        writeOgg(source.resolve("assets/better_cobblemon_music/sounds/battle/low_hp/alert.ogg"));
        writeOgg(source.resolve("assets/better_cobblemon_music/sounds/battle/hit/normal.ogg"));
        writeOgg(source.resolve("assets/better_cobblemon_music/sounds/battle/hit/super_effective.ogg"));
        writeOgg(source.resolve("assets/better_cobblemon_music/sounds/battle/hit/not_very_effective.ogg"));
        Path layout = temporaryDirectory.resolve("catalog-layout.json");
        write(layout, layoutJson());

        MusicResourcePackBuildTool.build(source, layout, output);

        var sounds = JsonParser.parseString(Files.readString(
            output.resolve("assets/better_cobblemon_music/sounds.json"), StandardCharsets.UTF_8
        )).getAsJsonObject();
        assertEquals(5, sounds.size());
        assertEquals(1, sounds.getAsJsonObject("music.track.field.plains.theme").getAsJsonArray("sounds").size());
        String catalogJson = Files.readString(
            output.resolve("assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json"),
            StandardCharsets.UTF_8
        );
        var catalog = MusicCatalogParser.parse(new StringReader(catalogJson));
        assertEquals(
            "better_cobblemon_music:music.track.field.plains.theme",
            catalog.tracks().get("better_cobblemon_music:field/plains/theme").eventId()
        );
        assertEquals(
            java.util.List.of("field/plains/theme.ogg"),
            catalog.tracks().get("better_cobblemon_music:field/plains/theme").legacyPaths()
        );
        assertTrue(catalog.playlists().containsKey("better_cobblemon_music:track/field/plains/theme"));
    }

    @Test
    void battleSoundVolumeDoesNotChangeFieldMusicOrEffects() throws IOException {
        Path source = temporaryDirectory.resolve("volume-source");
        Path output = temporaryDirectory.resolve("volume-output");
        write(source.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}");
        for (String file : java.util.List.of("music/field/plains/theme.ogg", "music/battle/wild/theme.ogg",
            "battle/low_hp/alert.ogg", "battle/hit/normal.ogg", "battle/hit/super_effective.ogg",
            "battle/hit/not_very_effective.ogg")) {
            writeOgg(source.resolve("assets/better_cobblemon_music/sounds/" + file));
        }
        var layout = JsonParser.parseString(layoutJson()).getAsJsonObject();
        layout.addProperty("battleSoundVolume", 0.713);
        Path layoutPath = temporaryDirectory.resolve("volume-layout.json");
        write(layoutPath, layout.toString());

        MusicResourcePackBuildTool.build(source, layoutPath, output);

        var sounds = JsonParser.parseString(Files.readString(
            output.resolve("assets/better_cobblemon_music/sounds.json"))).getAsJsonObject();
        assertEquals(0.713, sounds.getAsJsonObject("music.track.battle.wild.theme")
            .getAsJsonArray("sounds").get(0).getAsJsonObject().get("volume").getAsDouble());
        assertTrue(!sounds.getAsJsonObject("music.track.field.plains.theme")
            .getAsJsonArray("sounds").get(0).getAsJsonObject().has("volume"));
        assertTrue(!sounds.getAsJsonObject("battle.low_hp.alert")
            .getAsJsonArray("sounds").get(0).getAsJsonObject().has("volume"));
        var catalog = JsonParser.parseString(Files.readString(
            output.resolve("assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json")))
            .getAsJsonObject();
        assertTrue(!catalog.has("battleSoundVolume"));
    }

    @Test
    void customTrackTitlesReachTheCatalogAndInvalidTitlesCannotReplaceAGoodPack() throws IOException {
        Path source = temporaryDirectory.resolve("title-source");
        Path output = temporaryDirectory.resolve("title-output");
        write(source.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}");
        for (String file : java.util.List.of("music/field/plains/theme.ogg", "battle/low_hp/alert.ogg",
            "battle/hit/normal.ogg", "battle/hit/super_effective.ogg", "battle/hit/not_very_effective.ogg")) {
            writeOgg(source.resolve("assets/better_cobblemon_music/sounds/" + file));
        }
        Path layout = temporaryDirectory.resolve("titles-layout.json");
        var root = JsonParser.parseString(layoutJson()).getAsJsonObject();
        var titles = new com.google.gson.JsonObject();
        titles.addProperty("better_cobblemon_music:field/plains/theme", "한글 곡명 \"Forest\"");
        root.add("trackTitles", titles);
        write(layout, root.toString());
        MusicResourcePackBuildTool.build(source, layout, output);
        Path catalog = output.resolve("assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json");
        String good = Files.readString(catalog);
        assertEquals("한글 곡명 \"Forest\"", MusicCatalogParser.parse(new StringReader(good)).tracks()
            .get("better_cobblemon_music:field/plains/theme").title());
        for (var invalid : java.util.List.of(JsonParser.parseString("42"), JsonParser.parseString("\"  \""))) {
            titles.add("better_cobblemon_music:field/plains/theme", invalid);
            write(layout, root.toString());
            assertThrows(IOException.class, () -> MusicResourcePackBuildTool.build(source, layout, output));
            assertEquals(good, Files.readString(catalog));
        }
        titles.remove("better_cobblemon_music:field/plains/theme");
        titles.addProperty("better_cobblemon_music:missing", "Unknown");
        write(layout, root.toString());
        assertThrows(IOException.class, () -> MusicResourcePackBuildTool.build(source, layout, output));
        assertEquals(good, Files.readString(catalog));
    }

    @Test
    void rejectsInvalidOggBeforePublishingOutput() throws IOException {
        Path source = temporaryDirectory.resolve("bad-source");
        Path output = temporaryDirectory.resolve("bad-output");
        write(source.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}");
        Files.createDirectories(source.resolve("assets/better_cobblemon_music/sounds/music"));
        Files.write(source.resolve("assets/better_cobblemon_music/sounds/music/bad.ogg"), new byte[] {1, 2, 3, 4});
        Path layout = temporaryDirectory.resolve("bad-layout.json");
        write(layout, layoutJson().replace("better_cobblemon_music:field/plains/theme", "better_cobblemon_music:bad"));

        IOException exception = assertThrows(
            IOException.class,
            () -> MusicResourcePackBuildTool.build(source, layout, output)
        );

        assertTrue(exception.getMessage().contains("bad.ogg"));
        assertTrue(Files.notExists(output));
    }

    private static String layoutJson() {
        return """
            {
              "schemaVersion": 1,
              "packId": "better_cobblemon_music:official",
              "kind": "base",
              "playlists": {},
              "mappings": {
                "field": {
                  "default": "better_cobblemon_music:track/field/plains/theme",
                  "dimensions": {},
                  "biomes": {},
                  "biomePathContains": {}
                },
                "battle": {
                  "wild": "better_cobblemon_music:track/field/plains/theme",
                  "trainer": "better_cobblemon_music:track/field/plains/theme",
                  "pvp": "better_cobblemon_music:track/field/plains/theme",
                  "content": {},
                  "pokemon": []
                }
              },
              "audioEvents": {
                "hitNormal": "better_cobblemon_music:battle.hit.normal",
                "hitSuperEffective": "better_cobblemon_music:battle.hit.super_effective",
                "hitNotVeryEffective": "better_cobblemon_music:battle.hit.not_very_effective",
                "lowHpAlert": "better_cobblemon_music:battle.low_hp.alert"
              }
            }
            """;
    }

    private static void writeOgg(Path path) throws IOException {
        byte[] bytes = new byte[] {'O', 'g', 'g', 'S', 0, 1, 'v', 'o', 'r', 'b', 'i', 's'};
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
