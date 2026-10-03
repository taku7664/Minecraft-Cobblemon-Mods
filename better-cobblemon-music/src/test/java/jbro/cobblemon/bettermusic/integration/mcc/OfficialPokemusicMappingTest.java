package jbro.cobblemon.bettermusic.integration.mcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.battle.BattlePlaylistResolver;
import jbro.cobblemon.bettermusic.catalog.CompiledMusicConfiguration;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogCompiler;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;
import jbro.cobblemon.bettermusic.field.FieldMusicContext;
import jbro.cobblemon.bettermusic.field.FieldPlaylistResolver;
import jbro.cobblemon.bettermusic.resource.MusicResourcePackBuildTool;
import jbro.cobblemon.bettermusic.screen.MenuMusicKeys;
import jbro.cobblemon.bettermusic.screen.ScreenPlaylistResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OfficialPokemusicMappingTest {
    @TempDir static Path directory;
    static Path module;
    static Path pack;
    static CompiledMusicConfiguration compiled;
    static FieldPlaylistResolver field;

    @BeforeAll
    static void buildAndCompileTheRealPack() throws Exception {
        module = Files.isDirectory(Path.of("resource-pack")) ? Path.of(".") : Path.of("better-cobblemon-music");
        pack = directory.resolve("pack");
        MusicResourcePackBuildTool.build(module.resolve("resource-pack/src"), module.resolve("resource-pack/catalog-layout.json"), pack);
        try (var reader = Files.newBufferedReader(pack.resolve("assets/better_cobblemon_music/catalogs/base/cobleserver.json"))) {
            compiled = MusicCatalogCompiler.compile("cobleserver:official", List.of(MusicCatalogParser.parse(reader)),
                MusicCatalogSettings.defaults("cobleserver:official"), MusicMappingOverrides.empty());
        }
        assertTrue(compiled.inactiveOverrides().isEmpty());
        field = new FieldPlaylistResolver(compiled.snapshot().field());
    }

    @Test
    void allThirtyImportedAudioFilesReachTheBuiltPackWithoutChangingTheApprovedAlert() throws Exception {
        try (var reader = Files.newBufferedReader(module.resolve("resource-pack/import-pokemusic-report-2026-10-03.json"))) {
            var files = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("files");
            assertEquals(30, files.size());
            for (var element : files) {
                var report = element.getAsJsonObject();
                Path audio = pack.resolve("assets/cobleserver/sounds/music/" + report.get("target").getAsString());
                assertEquals(report.get("outputSha256").getAsString(), sha256(audio), audio.toString());
                assertEquals(report.get("output_bytes").getAsLong(), Files.size(audio));
                assertEquals(1, report.get("logical_streams").getAsInt());
                assertEquals(2, report.get("channels").getAsInt());
                assertEquals(44100, report.get("sample_rate").getAsInt());
            }
        }
        assertEquals("3a3c66d0732c94cbce6628547b7fcaa6cad5691340f65371654b65429ee23e6e",
            sha256(pack.resolve("assets/cobleserver/sounds/battle/low_hp/alert.ogg")));
        assertEquals(112, compiled.trackEvents().size());
    }

    @Test
    void myRoomUsesEternaForestAtBothTimesRegardlessOfBiomeOrUnderground() throws Exception {
        for (var time : FieldMusicContext.TimeOfDay.values()) {
            for (String biome : List.of("minecraft:plains", "minecraft:forest", "minecraft:deep_dark")) {
                for (boolean underground : new boolean[]{false, true}) {
                    var selection = field.select(new FieldMusicContext("myroom:rooms", biome, Set.of(), underground, time));
                    assertEquals("field.dimension:myroom:rooms", selection.id());
                    expectTracks(selection.playlist(), "field/myroom/eterna_forest");
                }
            }
        }
        expectField("minecraft:forest", false, "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        try (var reader = Files.newBufferedReader(module.resolve("resource-pack/import-myroom-2026-10-03.json"))) {
            var report = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals(report.get("outputSha256").getAsString(), sha256(pack.resolve(
                "assets/cobleserver/sounds/music/field/myroom/eterna_forest.ogg")));
        }
    }

    @Test
    void everyTwoSongReadmeGroupUsesExplicitRandomSelectionRatherThanTheGlobalDefault() {
        for (String group : List.of("screen_title", "field_deep_dark", "field_cave", "field_river", "field_ocean", "field_mountain", "field_forest")) {
            var playlist = compiled.playlists().get("cobleserver:" + group);
            assertEquals(2, playlist.tracks().size(), group);
            assertEquals(PlaylistDefinition.Selection.RANDOM, playlist.selection(), group);
        }
    }

    @Test
    void readmeFieldGroupsResolveThroughExactBiomePathAndUndergroundSelectors() {
        expectField("minecraft:deep_dark", false, "field/deep_dark/sinnoh_old_chateau", "field/deep_dark/union_cave");
        for (String biome : List.of("minecraft:lush_caves", "minecraft:dripstone_caves")) {
            expectField(biome, true, "field/cave/sinnoh_oreburgh_mine", "field/cave/sinnoh_lake_caverns");
        }
        expectField("minecraft:plains", true, "field/cave/sinnoh_oreburgh_mine", "field/cave/sinnoh_lake_caverns");
        expectField("minecraft:frozen_river", false, "field/river/sinnoh_lake_theme", "field/river/sealed_chamber");
        expectField("minecraft:deep_ocean", false, "field/ocean/route_47", "field/ocean/underground_ruins");
        expectField("minecraft:mangrove_swamp", false, "field/swamp/road_to_reversal_mountain");
        expectField("minecraft:bamboo_jungle", false, "field/jungle/route_210");
        for (String biome : List.of("minecraft:ice_spikes", "minecraft:grove", "minecraft:snowy_plains", "minecraft:frozen_peaks")) {
            expectField(biome, false, "field/snow/sinnoh_route_205_night");
        }
        expectField("minecraft:jagged_peaks", false, "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
        for (String biome : List.of("minecraft:forest", "minecraft:cherry_grove", "minecraft:old_growth_birch_forest", "minecraft:taiga")) {
            expectField(biome, false, "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        }
        expectField("minecraft:plains", false, "field/plains/sinnoh_route_201_night");
        expectField("minecraft:desert", false, "field/desert/route_111");
        expectField("minecraft:wooded_badlands", false, "field/badlands/abandoned_ship");
        expectField("minecraft:beach", false, "field/beach/pmd_beach_at_dusk");
        for (var time : FieldMusicContext.TimeOfDay.values()) {
            expectTracks(field.select(context("minecraft:the_nether", time)).playlist(), "field/nether/sinnoh_stark_mountain");
            expectTracks(field.select(context("minecraft:the_end", time)).playlist(), "field/end/distortion_world");
        }
    }

    @Test
    void readmePriorityAppliesAcrossExactBiomesTagsPathsAndUnderground() {
        for (String biome : List.of("minecraft:cherry_grove", "minecraft:stony_shore", "minecraft:deep_ocean")) {
            expectField(biome, true, "field/cave/sinnoh_oreburgh_mine", "field/cave/sinnoh_lake_caverns");
        }
        expectField("minecraft:deep_dark", true, "field/deep_dark/sinnoh_old_chateau", "field/deep_dark/union_cave");
        expectField("example:forest_desert", false, "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        expectField("example:desert_badlands", false, "field/desert/route_111");
        expectField("example:plains_desert", false, "field/plains/sinnoh_route_201_night");
        expectField("example:river_ocean", false, "field/river/sinnoh_lake_theme", "field/river/sealed_chamber");
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", "example:snowy_ridge",
            Set.of("minecraft:is_mountain", "minecraft:is_forest"), false)).playlist(), "field/snow/sinnoh_route_205_night");
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", "example:wooded_ridge",
            Set.of("minecraft:is_mountain", "minecraft:is_forest"), false)).playlist(),
            "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
    }

    @Test
    void personalPlaylistRemappingDoesNotChangeThePacksFieldPriority() throws Exception {
        var overrides = jbro.cobblemon.bettermusic.catalog.MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion":1,"field":{"underground":"cobleserver:track/field/myroom/eterna_forest",
              "biomes":{"minecraft:cherry_grove":"cobleserver:track/field/desert/route_111"}}}
            """));
        try (var reader = Files.newBufferedReader(pack.resolve("assets/better_cobblemon_music/catalogs/base/cobleserver.json"))) {
            var custom = MusicCatalogCompiler.compile("cobleserver:official", List.of(MusicCatalogParser.parse(reader)),
                MusicCatalogSettings.defaults("cobleserver:official"), overrides);
            var resolver = new FieldPlaylistResolver(custom.snapshot().field());
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of(), true)).playlist(), "field/myroom/eterna_forest");
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of(), false)).playlist(), "field/desert/route_111");
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of("minecraft:is_mountain"), false)).playlist(), "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
            assertEquals(compiled.snapshot().field().ruleOrder(), custom.snapshot().field().ruleOrder());
            assertTrue(custom.inactiveOverrides().isEmpty());
        }
    }

    @Test
    void theActualAndLegacyPlazaIdsHaveDifferentDayAndNightCues() {
        for (String dimension : List.of("jbro_policy:plaza", "cobblemon_policy:plaza")) {
            var day = field.select(context(dimension, FieldMusicContext.TimeOfDay.DAY));
            var night = field.select(context(dimension, FieldMusicContext.TimeOfDay.NIGHT));
            expectTracks(day.playlist(), "field/plaza/route_1");
            expectTracks(night.playlist(), "field/plaza/pallet_town");
            assertTrue(!day.id().equals(night.id()));
        }
    }

    @Test
    void titleHubAndShopUseTheirMostSpecificScreenMappings() {
        var screens = new ScreenPlaylistResolver(compiled.snapshot().screens());
        expectTracks(screens.select(MenuMusicKeys.resolve(false, true)).orElseThrow().playlist(),
            "screen/title/sinnoh_introduction", "screen/title/sinnoh_league_night");
        expectTracks(screens.select(MccMusicKeys.hub("more_cobblemon_contents:shop")).orElseThrow().playlist(), "screen/mcc/poke_mart");
        expectTracks(screens.select(MccMusicKeys.hub("more_cobblemon_contents:league_challenge")).orElseThrow().playlist(), "screen/mcc/boutique");
    }

    @Test
    void leagueStageAndCynthiaMappingsDoNotReplaceTheBlankDefaultChampionOrTowerMemo() {
        var resolver = new BattlePlaylistResolver(compiled.snapshot().battle());
        for (String stage : List.of("wild_trainer", "wild_trainer_ace")) {
            expectBattle(resolver, stage, "example:trainer", "battle/trainer/sinnoh_trainer_battle");
        }
        for (String stage : List.of("gym", "hard_gym")) {
            expectBattle(resolver, stage, "example:leader", "battle/gym/sinnoh_gym_leader_battle");
        }
        for (String stage : List.of("elite_four", "hard_elite_four")) {
            expectBattle(resolver, stage, "example:elite", "battle/elite/sinnoh_elite_four_battle");
        }
        expectBattle(resolver, "champion", "more_cobblemon_contents_league_challenge:cynthia", "battle/champion/sinnoh_cynthia_battle");
        expectBattle(resolver, "hard_champion", "more_cobblemon_contents_league_challenge:cynthia_hard", "battle/champion/sinnoh_cynthia_battle");
        expectBattle(resolver, "champion", "example:other", "battle/champion/oras_champion_battle");
        expectBattle(resolver, "hard_champion", "example:other", "battle/champion/oras_champion_battle");
        expectTracks(compiled.snapshot().battle().content().get("more_cobblemon_contents:battle_tower"), "battle/trainer/sinnoh_trainer_battle");
    }

    private static void expectBattle(BattlePlaylistResolver resolver, String stage, String opponent, String path) {
        var keys = MccMusicKeys.battle("more_cobblemon_contents:league_challenge", stage, opponent);
        expectTracks(resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.TRAINER, Set.of(), Set.of(), keys)).playlist(), path);
    }

    private static void expectField(String biome, boolean underground, String... paths) {
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", biome, Set.of(), underground)).playlist(), paths);
    }

    private static FieldMusicContext context(String dimension, FieldMusicContext.TimeOfDay time) {
        return new FieldMusicContext(dimension, "minecraft:plains", Set.of(), false, time);
    }

    private static void expectTracks(PlaylistDefinition playlist, String... paths) {
        assertEquals(Arrays.stream(paths).map(path -> "cobleserver:" + path).toList(), playlist.tracks());
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
