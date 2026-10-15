package jbro.cobblemon.bettermusic.integration.mcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.battle.BattlePlaylistResolver;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogCompiler;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.resource.MusicResourcePackBuildTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the real content key -> playlist -> event -> shared audio path chain. */
final class GalarOfficialMusicTest {
    private static final String LEAGUE = "more_cobblemon_contents:league_challenge";
    private static final String MUSIC = "better_cobblemon_music:";
    @TempDir Path output;

    @Test
    void normalAndHardEncountersShareExactlySevenPhysicalTracks() throws Exception {
        Path module = Files.isDirectory(Path.of("resource-pack")) ? Path.of(".")
            : Path.of("mods/better-cobblemon-music");
        MusicResourcePackBuildTool.build(module.resolve("resource-pack/src"),
            module.resolve("resource-pack/catalog-layout.json"), output);
        var catalog = MusicCatalogParser.parse(Files.newBufferedReader(output.resolve(
            "assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json")));
        var compiled = MusicCatalogCompiler.compile(MUSIC + "official", List.of(catalog),
            MusicCatalogSettings.defaults(MUSIC + "official"), MusicMappingOverrides.empty());
        var resolver = new BattlePlaylistResolver(compiled.snapshot().battle());
        JsonObject sounds = JsonParser.parseString(Files.readString(output.resolve(
            "assets/better_cobblemon_music/sounds.json"))).getAsJsonObject();
        assertEquals(42, compiled.snapshot().battle().content().keySet().stream()
            .filter(key -> key.startsWith(LEAGUE + "/galar/")).count());
        Set<String> selected = new java.util.HashSet<>();
        for (String difficulty : List.of("", "hard_")) {
            for (String trainer : List.of("milo", "nessa", "kabu", "bea", "allister", "opal",
                "gordie", "melony", "piers", "raihan")) {
                check(resolver, compiled.trackEvents(), sounds, selected, difficulty, "gym", trainer,
                    trainer.equals("piers") ? "gym/galar_piers" : "gym/galar_gym_leader");
            }
            for (String trainer : List.of("marnie", "hop", "bede", "nessa", "bea", "allister", "raihan")) {
                check(resolver, compiled.trackEvents(), sounds, selected, difficulty, "tournament", "cup_" + trainer,
                    List.of("marnie", "hop", "bede").contains(trainer)
                        ? "trainer/galar_" + trainer : "tournament/galar_champion_cup");
            }
            check(resolver, compiled.trackEvents(), sounds, selected, difficulty, "champion", "leon",
                "champion/galar_leon");
        }
        assertEquals(7, selected.size());
        try (var files = Files.walk(output.resolve("assets/better_cobblemon_music/sounds/music/battle"))) {
            assertEquals(7, files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("galar_")).count());
        }
    }

    private void check(BattlePlaylistResolver resolver, java.util.Map<String, String> events,
        JsonObject sounds, Set<String> selected, String difficulty, String stage, String trainer,
        String expected) throws Exception {
        String suffix = difficulty.isEmpty() ? "" : "_hard";
        var keys = MccMusicKeys.battle(LEAGUE, difficulty + stage, "league:galar/" + trainer + suffix);
        var selection = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.TRAINER,
            Set.of("cobblemon:charizard"), Set.of(), keys));
        String track = MUSIC + "battle/" + expected;
        assertEquals(List.of(track), selection.playlist().tracks(), keys.toString());
        String event = events.get(track).substring(MUSIC.length());
        var definition = sounds.getAsJsonObject(event).getAsJsonArray("sounds");
        assertEquals(1, definition.size());
        var sound = definition.get(0).getAsJsonObject();
        assertTrue(sound.get("stream").getAsBoolean());
        assertEquals(0.713, sound.get("volume").getAsDouble());
        String audio = sound.get("name").getAsString();
        assertEquals(MUSIC + "music/battle/" + expected, audio);
        assertTrue(Files.isRegularFile(output.resolve("assets/better_cobblemon_music/sounds/"
            + audio.substring(MUSIC.length()) + ".ogg")));
        selected.add(audio);
    }
}
