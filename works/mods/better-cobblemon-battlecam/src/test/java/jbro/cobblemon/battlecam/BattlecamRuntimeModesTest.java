package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.batmite2b.battlecam.client.BattleCamState;
import org.junit.jupiter.api.Test;

final class BattlecamRuntimeModesTest {
    @Test
    void configuredModeAppliesUntilThePlayerPicksOne() {
        var modes = new BattlecamRuntimeModes();

        assertEquals(BattleCamState.Mode.AUTO, modes.resolve(BattlecamBattleType.WILD, BattleCamState.Mode.AUTO));
    }

    @Test
    void pickedModeOutlivesTheBattleForItsTypeOnly() {
        var modes = new BattlecamRuntimeModes();
        modes.remember(BattlecamBattleType.WILD, BattleCamState.Mode.CLASSIC);

        assertEquals(BattleCamState.Mode.CLASSIC, modes.resolve(BattlecamBattleType.WILD, BattleCamState.Mode.AUTO));
        assertEquals(BattleCamState.Mode.OFF, modes.resolve(BattlecamBattleType.PVP, BattleCamState.Mode.OFF));
    }

    @Test
    void unknownBattleTypeNeitherRemembersNorOverrides() {
        var modes = new BattlecamRuntimeModes();
        modes.remember(null, BattleCamState.Mode.CLASSIC);

        assertEquals(BattleCamState.Mode.MANUAL, modes.resolve(null, BattleCamState.Mode.MANUAL));
        assertEquals(BattleCamState.Mode.AUTO, modes.resolve(BattlecamBattleType.WILD, BattleCamState.Mode.AUTO));
    }

    @Test
    void clearingReturnsToTheConfiguredModes() {
        var modes = new BattlecamRuntimeModes();
        modes.remember(BattlecamBattleType.PVE, BattleCamState.Mode.OFF);
        modes.clear();

        assertEquals(BattleCamState.Mode.AUTO, modes.resolve(BattlecamBattleType.PVE, BattleCamState.Mode.AUTO));
    }
}
