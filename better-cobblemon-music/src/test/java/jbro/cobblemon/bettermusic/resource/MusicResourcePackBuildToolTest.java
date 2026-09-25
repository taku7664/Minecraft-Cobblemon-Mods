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
        writeOgg(source.resolve("assets/cobleserver/sounds/music/field/plains/theme.ogg"));
        writeOgg(source.resolve("assets/cobleserver/sounds/battle/hit/normal.ogg"));
        writeOgg(source.resolve("assets/cobleserver/sounds/battle/hit/super_effective.ogg"));
        writeOgg(source.resolve("assets/cobleserver/sounds/battle/hit/not_very_effective.ogg"));
        Path layout = temporaryDirectory.resolve("catalog-layout.json");
        write(layout, layoutJson());

        MusicResourcePackBuildTool.build(source, layout, output);

        var sounds = JsonParser.parseString(Files.readString(
            output.resolve("assets/cobleserver/sounds.json"), StandardCharsets.UTF_8
        )).getAsJsonObject();
        assertEquals(4, sounds.size());
        assertEquals(1, sounds.getAsJsonObject("music.track.field.plains.theme").getAsJsonArray("sounds").size());
        String catalogJson = Files.readString(
            output.resolve("assets/better_cobblemon_music/catalogs/base/cobleserver.json"),
            StandardCharsets.UTF_8
        );
        var catalog = MusicCatalogParser.parse(new StringReader(catalogJson));
        assertEquals(
            "cobleserver:music.track.field.plains.theme",
            catalog.tracks().get("cobleserver:field/plains/theme").eventId()
        );
        assertTrue(catalog.playlists().containsKey("cobleserver:track/field/plains/theme"));
    }

    @Test
    void rejectsInvalidOggBeforePublishingOutput() throws IOException {
        Path source = temporaryDirectory.resolve("bad-source");
        Path output = temporaryDirectory.resolve("bad-output");
        write(source.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}");
        Files.createDirectories(source.resolve("assets/cobleserver/sounds/music"));
        Files.write(source.resolve("assets/cobleserver/sounds/music/bad.ogg"), new byte[] {1, 2, 3, 4});
        Path layout = temporaryDirectory.resolve("bad-layout.json");
        write(layout, layoutJson().replace("cobleserver:field/plains/theme", "cobleserver:bad"));

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
              "packId": "cobleserver:official",
              "kind": "base",
              "playlists": {},
              "mappings": {
                "field": {
                  "default": "cobleserver:track/field/plains/theme",
                  "dimensions": {},
                  "biomes": {},
                  "biomePathContains": {}
                },
                "battle": {
                  "wild": "cobleserver:track/field/plains/theme",
                  "trainer": "cobleserver:track/field/plains/theme",
                  "pvp": "cobleserver:track/field/plains/theme",
                  "content": {},
                  "pokemon": []
                }
              },
              "audioEvents": {
                "hitNormal": "cobleserver:battle.hit.normal",
                "hitSuperEffective": "cobleserver:battle.hit.super_effective",
                "hitNotVeryEffective": "cobleserver:battle.hit.not_very_effective",
                "heartbeat": "minecraft:entity.warden.heartbeat"
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
