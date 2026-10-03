package jbro.cobblemon.bettermusic.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real pack builder, catalog compiler and runtime resolver together. */
final class OfficialMusicLineupTest {
    @TempDir
    static Path temporaryDirectory;
    static BattleMusicConfig battle;
    static BattlePlaylistResolver resolver;

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
        }
    }

    @Test
    void ordinaryWildUsesDiamondPearlAndLugiaKeepsItsExistingTrack() {
        assertEquals(List.of("cobleserver:battle/wild/sinnoh_wild_pokemon_battle"), battle.wild().tracks());
        expect("lugia", "legendary/hgss_lugia_battle");
        assertEquals(List.of("cobleserver:battle/trainer/sinnoh_trainer_battle"), battle.trainer().tracks());
        assertEquals(4, battle.pvp().tracks().size());
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
