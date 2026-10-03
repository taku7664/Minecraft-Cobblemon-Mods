package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The official pack must contain only requested BGM and the four approved effects. */
final class ApprovedMusicAssetsTest {
    @TempDir Path directory;

    @Test
    void packedAssetsExactlyMatchReadmeAndSeparatelyApprovedBattleMusic() throws Exception {
        Path module = Files.isDirectory(Path.of("resource-pack")) ? Path.of(".") : Path.of("better-cobblemon-music");
        Path pack = directory.resolve("pack");
        MusicResourcePackBuildTool.build(module.resolve("resource-pack/src"),
            module.resolve("resource-pack/catalog-layout.json"), pack);
        JsonObject catalog = read(pack.resolve("assets/better_cobblemon_music/catalogs/base/cobleserver.json"));
        JsonObject playlists = catalog.getAsJsonObject("playlists");
        JsonObject battle = catalog.getAsJsonObject("mappings").getAsJsonObject("battle");

        Set<String> approved = new HashSet<>();
        for (JsonElement file : read(module.resolve("resource-pack/import-pokemusic-2026-10-03.json")).getAsJsonArray("files")) {
            approved.add(trackId(file.getAsJsonArray().get(1).getAsString()));
        }
        approved.add(trackId(read(module.resolve("resource-pack/import-myroom-2026-10-03.json")).get("target").getAsString()));
        for (String key : Set.of("wild", "alpha", "legendary", "ultraBeast")) {
            addPlaylistTracks(playlists, battle.get(key).getAsString(), approved);
        }
        for (JsonElement rule : battle.getAsJsonArray("pokemon")) {
            addPlaylistTracks(playlists, rule.getAsJsonObject().get("playlist").getAsString(), approved);
        }
        assertEquals(approved, catalog.getAsJsonObject("tracks").keySet(), "Unrequested BGM must not be packaged");

        Set<String> mapped = new HashSet<>();
        collectMappedTracks(catalog.get("mappings"), playlists, mapped);
        assertEquals(approved, mapped, "Every packaged BGM must have an active default mapping");

        Set<String> expectedAudio = new HashSet<>();
        approved.forEach(id -> expectedAudio.add("assets/cobleserver/sounds/music/" + id.substring("cobleserver:".length()) + ".ogg"));
        for (String effect : Set.of("hit/normal", "hit/super_effective", "hit/not_very_effective", "low_hp/alert")) {
            expectedAudio.add("assets/cobleserver/sounds/battle/" + effect + ".ogg");
        }
        try (var files = Files.walk(pack)) {
            Set<String> actualAudio = new HashSet<>();
            files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".ogg"))
                .forEach(path -> actualAudio.add(pack.relativize(path).toString().replace('\\', '/')));
            assertEquals(expectedAudio, actualAudio, "Removed music must not survive as orphaned audio");
        }
        JsonObject sounds = read(pack.resolve("assets/cobleserver/sounds.json"));
        Set<String> expectedEvents = new HashSet<>();
        catalog.getAsJsonObject("tracks").entrySet().forEach(entry -> expectedEvents.add(
            entry.getValue().getAsJsonObject().get("event").getAsString().substring("cobleserver:".length())));
        expectedEvents.addAll(Set.of("battle.hit.normal", "battle.hit.super_effective", "battle.hit.not_very_effective", "battle.low_hp.alert"));
        assertEquals(expectedEvents, sounds.keySet(), "Removed music events must not remain selectable");
    }

    private static JsonObject read(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String trackId(String ogg) {
        return "cobleserver:" + ogg.substring(0, ogg.length() - ".ogg".length());
    }

    private static void addPlaylistTracks(JsonObject playlists, String id, Set<String> tracks) {
        playlists.getAsJsonObject(id).getAsJsonArray("tracks").forEach(track -> tracks.add(track.getAsString()));
    }

    private static void collectMappedTracks(JsonElement value, JsonObject playlists, Set<String> tracks) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> collectMappedTracks(entry.getValue(), playlists, tracks));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(item -> collectMappedTracks(item, playlists, tracks));
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && playlists.has(value.getAsString())) {
            addPlaylistTracks(playlists, value.getAsString(), tracks);
        }
    }
}
