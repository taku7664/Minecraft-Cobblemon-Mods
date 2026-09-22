package jbro.cobblemon.bettermusic.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class DefaultMusicConfigResourceTest {
    @Test
    void bundledDefaultsPreserveTheLegacyMultiTrackThemesInVisiblePlaylists() throws Exception {
        var stream = getClass().getResourceAsStream(
            "/assets/better_cobblemon_music/config_defaults/music.json"
        );
        assertNotNull(stream);
        BetterMusicConfigSnapshot config;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            config = MusicConfigParser.parse(reader);
        }

        assertEquals(3, config.field().dimensions().get("cobblemon_policy:plaza").tracks().size());
        assertEquals(4, config.battle().pvp().tracks().size());
        assertEquals(
            config.battle().trainer().tracks(),
            config.battle().content().get("cobblemon_more_battle_content:battle_tower").tracks()
        );
        assertEquals(
            config.battle().trainer().tracks(),
            config.battle().content().get("cobblemon_more_battle_content:battle_factory").tracks()
        );
        assertEquals(
            config.battle().pvp().tracks(),
            config.battle().content().get("cobblemon_more_battle_content:pvp").tracks()
        );
        assertEquals(
            java.util.List.of("battle/legendary/generic_legendary_battle.ogg"),
            config.battle().legendary().orElseThrow().tracks()
        );
        assertEquals(
            java.util.Set.of("cobblemon:hooh"),
            pokemonRule(config, "cobblemon:hooh").species()
        );
        assertTrack(config, "cobblemon:mewtwo", "battle/legendary/frlg_mewtwo_battle.ogg");
        assertTrack(config, "cobblemon:raikou", "battle/legendary/hgss_raikou_battle.ogg");
        assertTrack(config, "cobblemon:rayquaza", "battle/legendary/rse_super_ancient_pokemon_battle.ogg");
        assertTrack(config, "cobblemon:rayquaza#mega", "battle/legendary/oras_mega_rayquaza_battle.ogg");
        assertTrack(config, "cobblemon:cresselia", "battle/wild/sinnoh_wild_pokemon_battle.ogg");
        assertTrack(config, "cobblemon:shaymin", "battle/wild/sinnoh_wild_pokemon_battle.ogg");
        assertTrack(config, "cobblemon:kyurem", "battle/legendary/bw_kyurem_battle.ogg");
        assertTrack(config, "cobblemon:reshiram", "battle/legendary/bw_reshiram_zekrom_battle.ogg");
        assertTrack(config, "cobblemon:zekrom", "battle/legendary/bw_reshiram_zekrom_battle.ogg");
        assertEquals(
            java.util.Set.of("cobblemon:reshiram", "cobblemon:zekrom"),
            pokemonRule(config, "cobblemon:reshiram").species()
        );
        assertTrack(config, "cobblemon:xerneas", "battle/legendary/xy_xerneas_yveltal_zygarde_battle.ogg");
        assertTrack(config, "cobblemon:tapukoko", "battle/legendary/sm_tapu_battle.ogg");
        assertTrack(config, "cobblemon:zacian", "battle/legendary/swsh_zacian_zamazenta_battle.ogg");
        assertTrack(config, "cobblemon:wochien", "battle/legendary/sv_treasures_of_ruin_battle.ogg");
        assertTrack(config, "cobblemon:necrozma#ultra", "battle/legendary/usum_ultra_necrozma_battle.ogg");
        assertTrack(config, "cobblemon:terapagos#stellar", "battle/legendary/sv_stellar_terapagos_battle.ogg");
        assertTrack(config, "cobblemon:eternatus#eternamax", "battle/legendary/swsh_decisive_eternatus_battle.ogg");
        assertTrack(config, "cobblemon:kubfu", "battle/legendary/generic_legendary_battle.ogg");
        for (String species : java.util.List.of(
            "cobblemon:jirachi",
            "cobblemon:manaphy",
            "cobblemon:phione",
            "cobblemon:keldeo",
            "cobblemon:meloetta",
            "cobblemon:genesect",
            "cobblemon:diancie",
            "cobblemon:hoopa",
            "cobblemon:volcanion",
            "cobblemon:cosmog",
            "cobblemon:cosmoem",
            "cobblemon:magearna",
            "cobblemon:marshadow",
            "cobblemon:zeraora",
            "cobblemon:typenull",
            "cobblemon:silvally"
        )) {
            assertTrack(config, species, "battle/legendary/generic_legendary_battle.ogg");
        }
        assertEquals(
            pokemonRule(config, "cobblemon:groudon").playlist().tracks(),
            pokemonRule(config, "cobblemon:rayquaza").playlist().tracks()
        );
        assertTrack(config, "cobblemon:regigigas", "battle/legendary/dppt_legendary_pokemon_battle.ogg");
        assertTrack(config, "cobblemon:latias", "battle/wild/hoenn_wild_pokemon_battle.ogg");
        assertTrack(config, "cobblemon:latios", "battle/wild/hoenn_wild_pokemon_battle.ogg");
    }

    private static BattleMusicConfig.PokemonRule pokemonRule(
        BetterMusicConfigSnapshot config,
        String species
    ) {
        return config.battle().pokemon().stream()
            .filter(rule -> rule.species().contains(species))
            .findFirst()
            .orElseThrow();
    }

    private static void assertTrack(BetterMusicConfigSnapshot config, String species, String track) {
        assertEquals(java.util.List.of(track), pokemonRule(config, species).playlist().tracks(), species);
    }
}
