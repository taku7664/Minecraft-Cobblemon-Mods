package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.battle.BattlePlaylistResolver;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogCompiler;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogParser;
import jbro.cobblemon.bettermusic.catalog.MusicCatalogSettings;
import jbro.cobblemon.bettermusic.catalog.MusicMappingOverrides;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real pack builder, catalog compiler and runtime resolver together. */
final class OfficialMusicLineupTest {
    @TempDir
    static Path temporaryDirectory;
    static BattleMusicConfig battle;
    static BattlePlaylistResolver resolver;
    static Map<String, String> eventTitles;
    static Map<String, String> trackEvents;

    @BeforeAll
    static void compileOfficialPack() throws Exception {
        Path module = Files.isDirectory(Path.of("resource-pack")) ? Path.of(".") : Path.of("better-cobblemon-music");
        MusicResourcePackBuildTool.build(module.resolve("resource-pack/src"),
            module.resolve("resource-pack/catalog-layout.json"), temporaryDirectory.resolve("pack"));
        try (var reader = Files.newBufferedReader(temporaryDirectory.resolve(
            "pack/assets/better_cobblemon_music/catalogs/base/cobleserver.json"))) {
            var catalog = MusicCatalogParser.parse(reader);
            var compiled = MusicCatalogCompiler.compile("cobleserver:official", List.of(catalog),
                MusicCatalogSettings.defaults("cobleserver:official"), MusicMappingOverrides.empty());
            assertTrue(compiled.inactiveOverrides().isEmpty());
            battle = compiled.snapshot().battle();
            resolver = new BattlePlaylistResolver(battle);
            eventTitles = compiled.eventTitles();
            trackEvents = compiled.trackEvents();
        }
    }

    @Test
    void everyOfficialBgmHasAKoreanDisplayTitleAtThePlaybackBoundary() {
        assertEquals(87, eventTitles.size());
        eventTitles.forEach((event, title) -> assertTrue(
            title.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3),
            event + " still uses an untranslated filename: " + title));
        for (String game : List.of("레전즈", "하트골드", "소울실버", "브릴리언트", "샤이닝 펄",
            "오메가루비", "알파사파이어", "크리스탈", "블랙", "화이트", "스칼렛", "바이올렛",
            "소드", "실드", "금·은", "루비·사파이어", "파이어레드", "리프그린", "썬·문",
            "Pt 기라티나", "불가사의 던전", "포켓몬 챔피언스")) {
            eventTitles.forEach((event, title) -> assertTrue(!title.contains(game),
                event + " includes a full game name instead of English initials: " + title));
        }
    }

    @Test
    void localizedTitlesUseCheckedPlaceAndSoundtrackNamesInsteadOfTranslatingIds() {
        assertTitle("field/myroom/eterna_forest", "영원의숲");
        assertTitle("field/nether/sinnoh_stark_mountain", "하드마운틴");
        assertTitle("field/deep_dark/sinnoh_old_chateau", "숲의 양옥집");
        assertTitle("field/cave/sinnoh_oreburgh_mine", "무쇠탄갱");
        assertTitle("field/cave/sinnoh_lake_caverns", "호수의 공동");
        assertTitle("field/ocean/underground_ruins", "땅밑유적");
        assertTitle("field/river/sealed_chamber", "고시의 석실");
        assertTitle("field/swamp/road_to_reversal_mountain", "리버스마운틴으로 가는 길");
        assertTitle("field/plains/sinnoh_route_201_night", "201번도로 (밤)");
        assertTitle("screen/mcc/poke_mart", "프렌들리숍");
        assertTitle("battle/boss/pla_boss_battle", "승부: 우두머리 포켓몬 (PLA)");
        assertTitle("battle/boss/sv_leader_pokemon_battle", "전투! 주인 포켓몬 (S·V)");
        assertTitle("battle/legendary/oras_groudon_kyogre_battle", "전투! 초고대 포켓몬 (OR·AS)");
        assertTitle("battle/legendary/sv_stellar_terapagos_battle", "전투! 제로의 비보 테라파고스 (S·V)");
        assertTitle("battle/legendary/hgss_lugia_battle", "전투! 루기아 (HG·SS)");
        assertTitle("battle/legendary/bdsp_dialga_palkia_battle", "전투! 디아루가·펄기아 (BD·SP)");
        assertTitle("battle/legendary/dppt_dialga_palkia_battle", "전투! 디아루가·펄기아 (D·P)");
    }

    @Test
    void alphaBossTracksAreAvailableButNeverReplaceTheApprovedSpeciesThemes() {
        var alpha = Set.of(BattleMusicContext.Label.ALPHA);
        var ordinaryAlpha = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.WILD,
            Set.of("cobblemon:snorlax"), alpha));
        assertEquals("battle.alpha", ordinaryAlpha.id());
        assertEquals(List.of("cobleserver:battle/boss/pla_boss_battle", "cobleserver:battle/boss/sv_leader_pokemon_battle"),
            ordinaryAlpha.playlist().tracks());
        var lugiaAlpha = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.WILD,
            Set.of("cobblemon:lugia"), alpha));
        assertEquals(List.of("cobleserver:battle/legendary/hgss_lugia_battle"), lugiaAlpha.playlist().tracks());
    }

    @Test
    void ordinaryWildUsesDiamondPearlAndLugiaKeepsItsStableTrackId() {
        assertEquals(List.of("cobleserver:battle/wild/sinnoh_wild_pokemon_battle"), battle.wild().tracks());
        expect("lugia", "legendary/hgss_lugia_battle");
        assertEquals(List.of("cobleserver:battle/trainer/sinnoh_trainer_battle"), battle.trainer().tracks());
        assertEquals(battle.trainer(), battle.pvp());
    }

    @Test
    void lugiaResourceContainsTheApprovedFlacReplacement() throws Exception {
        byte[] audio = Files.readAllBytes(temporaryDirectory.resolve(
            "pack/assets/cobleserver/sounds/music/battle/legendary/hgss_lugia_battle.ogg"));
        assertEquals("b1e82d1430c823f9b195a4f1b3c4f835865f530de621a0a296ab3b699b8de278",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio)));
    }

    @Test
    void approvedLegendaryAndMythicalGroupsResolveWithoutSpeciesLabels() {
        expect("articuno zapdos moltres mew", "legendary/frlg_legendary_pokemon_battle");
        expect("mewtwo", "legendary/xy_mewtwo_battle");
        expect("raikou", "legendary/hgss_raikou_battle", "legendary/gs_beasts_battle");
        expect("entei", "legendary/hgss_entei_battle", "legendary/gs_beasts_battle");
        expect("suicune", "legendary/hgss_suicune_battle", "legendary/gs_beasts_battle");
        expect("hooh", "legendary/hgss_ho-oh_battle");
        expect("celebi", "wild/gs_johto_wild_pokemon_battle", "wild/johto_wild_pokemon_battle");
        expect("groudon kyogre rayquaza", "legendary/oras_groudon_kyogre_battle", "legendary/rse_groudon_kyogre_battle");
        expect("regirock regice registeel", "legendary/oras_regirock_regice_registeel_battle", "legendary/rse_regirock_regice_registeel_battle");
        expect("deoxys", "legendary/frlg_deoxys_battle_music", "legendary/oras_deoxys_battle");
        expect("latias latios jirachi", "wild/hoenn_wild_pokemon_battle");
        expect("dialga palkia", "legendary/dppt_dialga_palkia_battle", "legendary/bdsp_dialga_palkia_battle");
        expect("uxie mesprit azelf", "legendary/dppt_azelf_mesprit_uxie_battle", "legendary/bdsp_azelf_mesprit_uxie_battle");
        expect("giratina", "legendary/dppt_giratina_battle", "legendary/bdsp_giratina_battle");
        expect("arceus", "legendary/dppt_arceus_battle", "legendary/bdsp_arceus_battle");
        expect("heatran regigigas darkrai shaymin cresselia", "legendary/dppt_legendary_pokemon_battle", "legendary/bdsp_legendary_pokemon_battle");
        expect("phione manaphy", "wild/sinnoh_wild_pokemon_battle");
        expect("reshiram zekrom", "legendary/bw_reshiram_zekrom_battle");
        expect("kyurem", "legendary/bw_kyurem_battle");
        expect("cobalion terrakion virizion keldeo tornadus thundurus landorus genesect enamorus", "legendary/bw_legendary_pokemon_battle");
        expect("victini meloetta", "wild/bw_strong_wild_pokemon_battle");
        expect("xerneas yveltal zygarde", "legendary/xy_xerneas_yveltal_zygarde_battle");
        expect("diancie hoopa volcanion", "wild/xy_wild_pokemon_battle");
        expect("solgaleo lunala necrozma cosmog cosmoem", "legendary/sm_solgaleo_lunala_battle");
        expect("tapukoko tapulele tapubulu tapufini", "legendary/sm_tapu_battle");
        expect("nihilego buzzwole pheromosa xurkitree celesteela kartana guzzlord poipole naganadel stakataka blacephalon", "ultra_beast/alola_ultra_beast_battle");
        expect("magearna marshadow zeraora meltan melmetal typenull silvally", "wild/sm_wild_pokemon_battle");
        expect("zacian zamazenta", "legendary/swsh_zacian_zamazenta_battle");
        expect("eternatus", "legendary/swsh_eternatus_battle");
        expect("calyrex", "legendary/swsh_calyrex_battle");
        expect("glastrier spectrier", "legendary/swsh_glastrier_spectrier_battle");
        expect("zarude kubfu urshifu", "wild/swsh_wild_pokemon_battle");
        expect("wochien chienpao tinglu chiyu", "legendary/sv_treasures_of_ruin_battle");
        expect("okidogi munkidori fezandipiti", "legendary/sv_loyal_three_battle");
        expect("ogerpon", "legendary/sv_ogerpon_battle");
        expect("terapagos", "legendary/sv_terapagos_battle", "legendary/sv_stellar_terapagos_battle");
        expect("pecharunt", "legendary/sv_pecharunt_battle");
    }

    @Test
    void paradoxesAndSpecialFormsShareTheApprovedBaseSpeciesMusic() {
        expect("greattusk screamtail brutebonnet fluttermane slitherwing sandyshocks roaringmoon irontreads ironbundle ironhands ironjugulis ironmoth ironthorns ironvaliant walkingwake ironleaves gougingfire ragingbolt ironboulder ironcrown koraidon miraidon",
            "legendary/sv_area_zero_battle_1", "legendary/sv_area_zero_battle_2");
        for (String key : List.of("rayquaza#mega", "giratina#origin", "necrozma#ultra",
            "terapagos#stellar", "kyurem#black", "eternatus#eternamax")) {
            String species = "cobblemon:" + key.substring(0, key.indexOf('#'));
            var base = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.WILD, Set.of(species), Set.of()));
            var form = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.WILD,
                Set.of(species, "cobblemon:" + key), Set.of()));
            assertEquals(base.playlist(), form.playlist(), key);
        }
    }

    private static String title(String path) {
        return eventTitles.get(trackEvents.get("cobleserver:" + path));
    }

    private static void assertTitle(String path, String expected) {
        assertEquals(expected, title(path), path);
    }

    private static void expect(String speciesList, String... paths) {
        List<String> tracks = java.util.Arrays.stream(paths).map(path -> "cobleserver:battle/" + path).toList();
        for (String species : speciesList.split(" ")) {
            var selection = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.WILD,
                Set.of("cobblemon:" + species), Set.of()));
            assertEquals(tracks, selection.playlist().tracks(), species);
            assertTrue(selection.id().startsWith("battle.pokemon:"), species);
            var trainer = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.TRAINER,
                Set.of("cobblemon:" + species), Set.of()));
            assertEquals(battle.trainer(), trainer.playlist(), species + " trainer");
            var pvp = resolver.select(new BattleMusicContext(BattleMusicConfig.BattleType.PVP,
                Set.of("cobblemon:" + species), Set.of()));
            assertEquals(battle.pvp(), pvp.playlist(), species + " pvp");
        }
    }
}
