package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BattlecamActivationPolicyTest {
    @Test
    void activatesOnlyForSpectatorsWithSubjectsAndAnEnabledMode() {
        assertTrue(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.AUTO, false, false));
        assertFalse(BattlecamActivationPolicy.shouldActivate(false, true, BattlecamMode.AUTO, false, false));
        assertFalse(BattlecamActivationPolicy.shouldActivate(true, false, BattlecamMode.AUTO, false, false));
        assertFalse(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.OFF, false, false));
    }

    @Test
    void optionalBattleScreenBoundaryReturnsControlOutsideTheScreen() {
        assertFalse(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.AUTO, true, false));
        assertTrue(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.AUTO, true, true));
    }
}
