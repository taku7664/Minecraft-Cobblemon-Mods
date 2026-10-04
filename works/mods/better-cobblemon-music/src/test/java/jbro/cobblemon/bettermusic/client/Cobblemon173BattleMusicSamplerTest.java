package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import org.junit.jupiter.api.Test;

final class Cobblemon173BattleMusicSamplerTest {
    @Test
    void detectsTheServerTransmittedAlphaAspectWithoutGuessingFromSizeOrName() {
        var labels = new LinkedHashSet<BattleMusicContext.Label>();
        Cobblemon173BattleMusicSampler.collectAlphaLabel(BattleMusicConfig.BattleType.WILD,
            Set.of("male", "alpha"), labels);
        assertEquals(Set.of(BattleMusicContext.Label.ALPHA), labels);
    }

    @Test
    void alphaLikeNamesAndMissingAspectAreNotAlpha() {
        var labels = new LinkedHashSet<BattleMusicContext.Label>();
        Cobblemon173BattleMusicSampler.collectAlphaLabel(BattleMusicConfig.BattleType.WILD,
            Set.of("alpha_eyes", "alpha=true", "not-alpha"), labels);
        Cobblemon173BattleMusicSampler.collectAlphaLabel(BattleMusicConfig.BattleType.WILD, Set.of(), labels);
        assertTrue(labels.isEmpty());
    }

    @Test
    void ignoresAlphaOnPlayerAndNpcTeams() {
        var labels = new LinkedHashSet<BattleMusicContext.Label>();
        for (var type : java.util.List.of(BattleMusicConfig.BattleType.PVP, BattleMusicConfig.BattleType.TRAINER)) {
            Cobblemon173BattleMusicSampler.collectAlphaLabel(type, Set.of("alpha"), labels);
        }
        assertTrue(labels.isEmpty());
    }

    @Test
    void ignoresAnActiveSlotWhoseBattlePokemonHasNotArrivedYet() {
        var species = new LinkedHashSet<String>();
        var labels = new LinkedHashSet<BattleMusicContext.Label>();

        assertDoesNotThrow(() -> Cobblemon173BattleMusicSampler.collectBattlePokemon(
            null,
            BattleMusicConfig.BattleType.WILD,
            species,
            labels
        ));

        assertTrue(species.isEmpty());
        assertTrue(labels.isEmpty());
    }

    @Test
    void producesBaseAndFormQualifiedKeysWithoutAssumingAFormExists() {
        assertEquals(
            java.util.List.of("cobblemon:necrozma#dusk-mane", "cobblemon:necrozma"),
            Cobblemon173BattleMusicSampler.battleMusicSpeciesKeys(
                "cobblemon:necrozma",
                "Dusk-Mane"
            )
        );
        assertEquals(
            java.util.List.of("cobblemon:articuno"),
            Cobblemon173BattleMusicSampler.battleMusicSpeciesKeys("cobblemon:articuno", null)
        );
    }
}
