package jbro.cobblemon.bettermusic.integration.mcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        module = Files.isDirectory(Path.of("resource-pack")) ? Path.of(".") : Path.of("mods/better-cobblemon-music");
        pack = directory.resolve("pack");
        MusicResourcePackBuildTool.build(module.resolve("resource-pack/src"), module.resolve("resource-pack/catalog-layout.json"), pack);
        try (var reader = Files.newBufferedReader(pack.resolve("assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json"))) {
            compiled = MusicCatalogCompiler.compile("better_cobblemon_music:official", List.of(MusicCatalogParser.parse(reader)),
                MusicCatalogSettings.defaults("better_cobblemon_music:official"), MusicMappingOverrides.empty());
        }
        assertTrue(compiled.inactiveOverrides().isEmpty());
        field = new FieldPlaylistResolver(compiled.snapshot().field());
    }

    @Test
    void preservedImportsAndTheNewPlazaSongReachTheBuiltPackWithApprovedEffectGains() throws Exception {
        try (var reader = Files.newBufferedReader(module.resolve("resource-pack/import-pokemusic-report-2026-10-03.json"))) {
            var files = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("files");
            assertEquals(30, files.size());
            int preserved = 0;
            for (var element : files) {
                var report = element.getAsJsonObject();
                if (report.get("target").getAsString().startsWith("battle/") || Set.of("field/plaza/route_1.ogg", "field/badlands/abandoned_ship.ogg",
                    "field/cave/sinnoh_lake_caverns.ogg", "field/river/sealed_chamber.ogg",
                    "field/swamp/road_to_reversal_mountain.ogg", "field/snow/sinnoh_route_205_night.ogg",
                    "field/jungle/route_210.ogg")
                    .contains(report.get("target").getAsString())) {
                    continue; // Historical imports superseded by later field-song changes.
                }
                Path audio = pack.resolve("assets/better_cobblemon_music/sounds/music/" + report.get("target").getAsString());
                var gain = volumeTrack("music/" + report.get("target").getAsString());
                assertEquals(report.get("outputSha256").getAsString(), gain.get("beforeSha256").getAsString(), audio.toString());
                assertEquals(gain.get("afterSha256").getAsString(), sha256(audio), audio.toString());
                assertEquals(1, report.get("logical_streams").getAsInt());
                assertEquals(2, report.get("channels").getAsInt());
                assertEquals(44100, report.get("sample_rate").getAsInt());
                preserved++;
            }
            assertEquals(19, preserved);
        }
        Path newPlazaSong = pack.resolve("assets/better_cobblemon_music/sounds/music/field/plaza/jubilife_city_day.ogg");
        assertScaledImport(newPlazaSong, "music/field/plaza/jubilife_city_day.ogg",
            "eee2e22e674c3d89d158efb8d795d3d509c9dd5708d0cb08a1b59275a7e9f105");
        assertFalse(Files.exists(pack.resolve("assets/better_cobblemon_music/sounds/music/field/plaza/route_1.ogg")));
        Path newBadlandsSong = pack.resolve("assets/better_cobblemon_music/sounds/music/field/badlands/mt_pyre_exterior.ogg");
        assertScaledImport(newBadlandsSong, "music/field/badlands/mt_pyre_exterior.ogg",
            "eb72dd859b0414a561a3bc5a698acb207e3884bf7841310503b1214df78f586d");
        assertFalse(Files.exists(pack.resolve("assets/better_cobblemon_music/sounds/music/field/badlands/abandoned_ship.ogg")));
        Path newJungleSong = pack.resolve("assets/better_cobblemon_music/sounds/music/field/jungle/southern_jungle.ogg");
        assertScaledImport(newJungleSong, "music/field/jungle/southern_jungle.ogg",
            "c5c56db75348d7b484a96af4313ec2e0ce8904d8a9be2107fc9efb605820f851");
        assertFalse(Files.exists(pack.resolve("assets/better_cobblemon_music/sounds/music/field/jungle/route_210.ogg")));
        assertEquals("3a0b977babf57f6ea5d3da04f0da85596d1fa2d50fe69f7f1c1064a91aa5666b",
            sha256(pack.resolve("assets/better_cobblemon_music/sounds/battle/hit/normal.ogg")));
        assertEquals("d4dbddb4c776544feb6d83e83dd1f89a27c7c1261b0cb5cb9477f0a6b9ad44bd",
            sha256(pack.resolve("assets/better_cobblemon_music/sounds/battle/hit/not_very_effective.ogg")));
        assertEquals("ac569053487563e8f037e4e984f29f5ad1b34fd5b78cf8339e0a2313a43e5b8b",
            sha256(pack.resolve("assets/better_cobblemon_music/sounds/battle/hit/super_effective.ogg")));
        assertScaledImport(pack.resolve("assets/better_cobblemon_music/sounds/battle/low_hp/alert.ogg"),
            "battle/low_hp/alert.ogg", "b2628913d17ee549a3dd8d3b75034892d4d5746bef419d9e9f91a0e93b1494ad");
        assertEquals(88, compiled.trackEvents().size());
    }

    @Test
    void myRoomUsesSeparateValorLakefrontThemesAtBothTimesRegardlessOfBiomeOrUnderground() throws Exception {
        for (var time : FieldMusicContext.TimeOfDay.values()) {
            for (String biome : List.of("minecraft:plains", "minecraft:forest", "minecraft:deep_dark")) {
                for (boolean underground : new boolean[]{false, true}) {
                    var selection = field.select(new FieldMusicContext("myroom:rooms", biome, Set.of(), underground, time));
                    assertEquals("field.dimension." + (time == FieldMusicContext.TimeOfDay.DAY ? "day:" : "night:") + "myroom:rooms", selection.id());
                    expectTracks(selection.playlist(), time == FieldMusicContext.TimeOfDay.DAY
                        ? "field/myroom/valor_lakefront_day" : "field/myroom/valor_lakefront_night");
                }
            }
        }
        expectFieldWithTags("minecraft:forest", Set.of("minecraft:is_forest"), false,
            "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        assertFalse(Files.exists(pack.resolve("assets/better_cobblemon_music/sounds/music/field/myroom/eterna_forest.ogg")));
    }

    @Test
    void everyTwoSongReadmeGroupUsesExplicitRandomSelectionRatherThanTheGlobalDefault() {
        for (String group : List.of("screen_title", "field_deep_dark", "field_mountain", "field_forest")) {
            var playlist = compiled.playlists().get("better_cobblemon_music:" + group);
            assertEquals(2, playlist.tracks().size(), group);
            assertEquals(PlaylistDefinition.Selection.RANDOM, playlist.selection(), group);
        }
    }

    @Test
    void readmeFieldGroupsResolveThroughExactBiomeTagsAndUndergroundSelectors() {
        expectField("minecraft:deep_dark", false, "field/deep_dark/sinnoh_old_chateau", "field/deep_dark/union_cave");
        for (String biome : List.of("minecraft:lush_caves", "minecraft:dripstone_caves")) {
            expectField(biome, true, "field/cave/sinnoh_oreburgh_mine");
        }
        expectField("minecraft:plains", true, "field/cave/sinnoh_oreburgh_mine");
        expectFieldWithTags("minecraft:frozen_river", Set.of("minecraft:is_river"), false, "field/river/sinnoh_lake_theme");
        expectFieldWithTags("minecraft:ocean", Set.of("minecraft:is_ocean"), false, "field/ocean/route_47");
        expectField("minecraft:deep_ocean", false, "field/ocean/underground_ruins");
        expectField("minecraft:mangrove_swamp", false, "field/swamp/route_120");
        expectFieldWithTags("minecraft:bamboo_jungle", Set.of("minecraft:is_jungle"), false,
            "field/jungle/southern_jungle");
        for (String biome : List.of("minecraft:ice_spikes", "minecraft:grove", "minecraft:snowy_plains", "minecraft:frozen_peaks")) {
            expectField(biome, false, "field/snow/sinnoh_route_216_night");
        }
        expectFieldWithTags("minecraft:jagged_peaks", Set.of("minecraft:is_mountain"), false,
            "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
        expectField("minecraft:cherry_grove", false, "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        for (String biome : List.of("minecraft:forest", "minecraft:old_growth_birch_forest")) {
            expectFieldWithTags(biome, Set.of("minecraft:is_forest"), false,
                "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        }
        expectFieldWithTags("minecraft:taiga", Set.of("minecraft:is_taiga"), false,
            "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        expectField("minecraft:plains", false, "field/plains/sinnoh_route_201_night");
        expectField("minecraft:desert", false, "field/desert/route_111");
        expectField("minecraft:wooded_badlands", false, "field/badlands/mt_pyre_exterior");
        for (String biome : List.of("minecraft:beach", "minecraft:stony_shore")) {
            expectField(biome, false, "field/plains/sinnoh_route_201_night");
        }
        expectField("minecraft:snowy_beach", false, "field/snow/sinnoh_route_216_night");
        for (var time : FieldMusicContext.TimeOfDay.values()) {
            expectTracks(field.select(context("minecraft:the_nether", time)).playlist(), "field/nether/sinnoh_stark_mountain");
            expectTracks(field.select(context("minecraft:the_end", time)).playlist(), "field/end/distortion_world");
        }
    }

    @Test
    void readmePriorityAppliesAcrossExactBiomesTagsAndUnderground() {
        for (String biome : List.of("minecraft:cherry_grove", "minecraft:stony_shore", "minecraft:deep_ocean")) {
            expectField(biome, true, "field/cave/sinnoh_oreburgh_mine");
        }
        expectField("minecraft:deep_dark", true, "field/deep_dark/sinnoh_old_chateau", "field/deep_dark/union_cave");
        expectFieldWithTags("example:forest_desert", Set.of("c:is_forest", "c:is_desert"), false,
            "field/forest/sinnoh_route_203_day", "field/forest/viridian_forest");
        expectFieldWithTags("example:desert_badlands", Set.of("c:is_desert", "c:is_badlands"), false,
            "field/desert/route_111");
        expectFieldWithTags("example:plains_desert", Set.of("c:is_plains", "c:is_desert"), false,
            "field/plains/sinnoh_route_201_night");
        expectFieldWithTags("example:river_ocean", Set.of("c:is_river", "minecraft:is_ocean"), false,
            "field/river/sinnoh_lake_theme");
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", "example:snowy_ridge",
            Set.of("c:is_snowy", "minecraft:is_mountain", "minecraft:is_forest"), false)).playlist(),
            "field/snow/sinnoh_route_216_night");
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", "example:wooded_ridge",
            Set.of("minecraft:is_mountain", "minecraft:is_forest"), false)).playlist(),
            "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
    }

    @Test
    void personalPlaylistRemappingDoesNotChangeThePacksFieldPriority() throws Exception {
        var overrides = jbro.cobblemon.bettermusic.catalog.MusicMappingOverridesParser.parse(new StringReader("""
            {"schemaVersion":1,"field":{"underground":"better_cobblemon_music:track/field/myroom/valor_lakefront_day",
              "biomes":{"minecraft:cherry_grove":"better_cobblemon_music:track/field/desert/route_111"}}}
            """));
        try (var reader = Files.newBufferedReader(pack.resolve("assets/better_cobblemon_music/catalogs/base/better_cobblemon_music.json"))) {
            var custom = MusicCatalogCompiler.compile("better_cobblemon_music:official", List.of(MusicCatalogParser.parse(reader)),
                MusicCatalogSettings.defaults("better_cobblemon_music:official"), overrides);
            var resolver = new FieldPlaylistResolver(custom.snapshot().field());
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of(), true)).playlist(), "field/myroom/valor_lakefront_day");
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of(), false)).playlist(), "field/desert/route_111");
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:cherry_grove",
                Set.of("minecraft:is_mountain"), false)).playlist(), "field/desert/route_111");
            expectTracks(resolver.select(new FieldMusicContext("minecraft:overworld", "minecraft:stony_peaks",
                Set.of("minecraft:is_mountain"), false)).playlist(),
                "field/mountain/sinnoh_route_205_day", "field/mountain/route_3");
            assertEquals(compiled.snapshot().field().ruleOrder(), custom.snapshot().field().ruleOrder());
            assertTrue(custom.inactiveOverrides().isEmpty());
        }
    }

    @Test
    void theActualAndLegacyPlazaIdsHaveDifferentDayAndNightCues() {
        for (String dimension : List.of("jbro_policy:plaza", "cobblemon_policy:plaza")) {
            var day = field.select(context(dimension, FieldMusicContext.TimeOfDay.DAY));
            var night = field.select(context(dimension, FieldMusicContext.TimeOfDay.NIGHT));
            expectTracks(day.playlist(), "field/plaza/jubilife_city_day");
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
    void leagueStageAndCynthiaUseRequestedMusicAndUnassignedBattlesUseTheTrainerDefault() {
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
        expectBattle(resolver, "champion", "example:other", "battle/trainer/sinnoh_trainer_battle");
        expectBattle(resolver, "hard_champion", "example:other", "battle/trainer/sinnoh_trainer_battle");
        expectTracks(compiled.snapshot().battle().pvp(), "battle/pvp/pokemon_champions_arena_battle");
        expectTracks(compiled.snapshot().battle().content().get("more_cobblemon_contents:pvp"),
            "battle/pvp/pokemon_champions_arena_battle");
        var pvpKeys = MccMusicKeys.battle("more_cobblemon_contents:pvp", "single", null);
        expectTracks(resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.PVP,
            Set.of(), Set.of(), pvpKeys)).playlist(), "battle/pvp/pokemon_champions_arena_battle");
        assertTrue(!compiled.snapshot().battle().content().containsKey("more_cobblemon_contents:league_challenge/champion"));
        assertTrue(!compiled.snapshot().battle().content().containsKey("more_cobblemon_contents:league_challenge/hard_champion"));
        expectTracks(compiled.snapshot().battle().content().get("more_cobblemon_contents:battle_tower"), "battle/trainer/sinnoh_trainer_battle");
    }

    private static void expectBattle(BattlePlaylistResolver resolver, String stage, String opponent, String path) {
        var keys = MccMusicKeys.battle("more_cobblemon_contents:league_challenge", stage, opponent);
        expectTracks(resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.TRAINER, Set.of(), Set.of(), keys)).playlist(), path);
    }

    private static void expectField(String biome, boolean underground, String... paths) {
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", biome, Set.of(), underground)).playlist(), paths);
    }

    private static void expectFieldWithTags(String biome, Set<String> tags, boolean underground, String... paths) {
        expectTracks(field.select(new FieldMusicContext("minecraft:overworld", biome, tags, underground)).playlist(), paths);
    }

    private static FieldMusicContext context(String dimension, FieldMusicContext.TimeOfDay time) {
        return new FieldMusicContext(dimension, "minecraft:plains", Set.of(), false, time);
    }

    private static void expectTracks(PlaylistDefinition playlist, String... paths) {
        assertEquals(Arrays.stream(paths).map(path -> "better_cobblemon_music:" + path).toList(), playlist.tracks());
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static com.google.gson.JsonObject volumeTrack(String target) throws Exception {
        var volumeReport = JsonParser.parseString(Files.readString(module.resolve(
            "resource-pack/all-bgm-alert-volume-2026-10-11.json"))).getAsJsonObject();
        return java.util.stream.StreamSupport.stream(volumeReport.getAsJsonArray("tracks").spliterator(), false)
            .map(item -> item.getAsJsonObject())
            .filter(item -> item.get("target").getAsString().equals(target))
            .findFirst().orElseThrow();
    }

    private static void assertScaledImport(Path audio, String target, String originalHash) throws Exception {
        var gain = volumeTrack(target);
        assertEquals(originalHash, gain.get("beforeSha256").getAsString(), target);
        assertEquals(gain.get("afterSha256").getAsString(), sha256(audio), target);
    }
}
