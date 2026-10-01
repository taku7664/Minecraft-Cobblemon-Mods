package jbro.cobblemon.bettermusic.integration.mcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MccMusicKeysTest {
    private static final String LEAGUE = "more_cobblemon_contents:league_challenge";

    @Test
    void battleKeysGoFromTheOpponentToTheStageToTheContent() {
        assertEquals(List.of(
            LEAGUE + "/champion/cynthia", LEAGUE + "/champion", LEAGUE
        ), MccMusicKeys.battle(LEAGUE, "champion", "more_cobblemon_contents_league_challenge:cynthia"));
        assertEquals(List.of(
            "more_cobblemon_contents:pvp/double", "more_cobblemon_contents:pvp"
        ), MccMusicKeys.battle("more_cobblemon_contents:pvp", "double", null));
        assertEquals(List.of("more_cobblemon_contents:ai_test"), MccMusicKeys.battle("more_cobblemon_contents:ai_test", null, null));
    }

    @Test
    void invalidPartsAreSkippedRatherThanBreakingTheBattle() {
        assertEquals(List.of(LEAGUE + "/gym", LEAGUE), MccMusicKeys.battle(LEAGUE, "gym", "x:Roark Leader"));
        assertEquals(List.of(), MccMusicKeys.battle("not namespaced", "gym", null));
    }

    @Test
    void hubKeysNameTheTabThenTheHub() {
        assertEquals(List.of("more_cobblemon_contents:hub/shop", "more_cobblemon_contents:hub"),
            MccMusicKeys.hub("more_cobblemon_contents:shop"));
        assertEquals(List.of("more_cobblemon_contents:hub/other_mod/arena", "more_cobblemon_contents:hub"),
            MccMusicKeys.hub("other_mod:arena"));
        assertTrue(MccMusicKeys.KNOWN_SCREEN_KEYS.containsAll(List.of(
            MccMusicKeys.hub("more_cobblemon_contents:dashboard").getFirst(),
            MccMusicKeys.hub("more_cobblemon_contents:battle_tower").getFirst()
        )));
        assertTrue(MccMusicKeys.KNOWN_BATTLE_KEYS.containsAll(List.of(
            LEAGUE + "/hard_champion", LEAGUE + "/wild_trainer_ace", "more_cobblemon_contents:battle_factory/factory_head"
        )));
    }

    @Test
    void theIntegrationLoadsWithoutMoreCobblemonContentsOnTheClasspath() throws Exception {
        // The test classpath has no MCC, as a player without it would not.
        Class.forName("jbro.cobblemon.bettermusic.integration.mcc.MoreCobblemonContentsIntegration");
        Class.forName("jbro.cobblemon.bettermusic.integration.mcc.MccMusicKeys");
        assertTrue(MccMusicKeys.KNOWN_BATTLE_KEYS.contains(LEAGUE + "/gym"));
    }
}
