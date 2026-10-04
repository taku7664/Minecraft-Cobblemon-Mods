package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.batmite2b.battlecam.client.BattleCamState;
import org.junit.jupiter.api.Test;

final class BattlecamModePolicyTest {
    @Test
    void disablesOnlyTheSelectedBattleType() {
        var config = new BattlecamConfig(
            false, BattlecamMode.AUTO,
            true, BattlecamMode.MANUAL,
            true, BattlecamMode.AUTO
        );

        assertEquals(BattleCamState.Mode.OFF, BattlecamModePolicy.modeFor(config, BattlecamBattleType.WILD));
        assertEquals(BattleCamState.Mode.MANUAL, BattlecamModePolicy.modeFor(config, BattlecamBattleType.PVE));
        assertEquals(BattleCamState.Mode.AUTO, BattlecamModePolicy.modeFor(config, BattlecamBattleType.PVP));
    }

    @Test
    void mapsEveryConfiguredModeToThePortedEngine() {
        for (BattlecamMode mode : BattlecamMode.values()) {
            var config = new BattlecamConfig(true, mode, true, mode, true, mode);
            assertEquals(
                BattleCamState.Mode.valueOf(mode.name()),
                BattlecamModePolicy.modeFor(config, BattlecamBattleType.WILD)
            );
        }
    }
}
