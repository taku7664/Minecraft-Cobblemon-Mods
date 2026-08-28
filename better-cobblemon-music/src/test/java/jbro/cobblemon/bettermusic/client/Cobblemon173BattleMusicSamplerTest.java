package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import org.junit.jupiter.api.Test;

final class Cobblemon173BattleMusicSamplerTest {
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
