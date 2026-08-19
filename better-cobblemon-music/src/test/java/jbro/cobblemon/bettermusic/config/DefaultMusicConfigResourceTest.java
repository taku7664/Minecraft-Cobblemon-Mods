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
        MusicConfig config;
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
        assertEquals(3, config.battle().legendary().orElseThrow().tracks().size());
        assertEquals(3, config.battle().roles().get("gym").tracks().size());
        assertEquals(1, config.battle().roles().get("rival").tracks().size());
        assertEquals(1, config.battle().roles().get("elite").tracks().size());
        assertEquals(1, config.battle().roles().get("champion").tracks().size());
        assertEquals(
            java.util.Set.of("cobblemon:hooh"),
            pokemonRule(config, "cobblemon:hooh").species()
        );
        assertEquals(2, pokemonRule(config, "cobblemon:groudon").playlist().tracks().size());
        assertEquals(2, pokemonRule(config, "cobblemon:regirock").playlist().tracks().size());
    }

    private static BattleMusicConfig.PokemonRule pokemonRule(MusicConfig config, String species) {
        return config.battle().pokemon().stream()
            .filter(rule -> rule.species().contains(species))
            .findFirst()
            .orElseThrow();
    }
}
