package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BattlecamActivationPolicyTest {
    @Test
    void activatesForAnyEnabledBattleWithSubjectsAndAnEnabledMode() {
        assertTrue(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.AUTO));
        assertFalse(BattlecamActivationPolicy.shouldActivate(false, true, BattlecamMode.AUTO));
        assertFalse(BattlecamActivationPolicy.shouldActivate(true, false, BattlecamMode.AUTO));
        assertFalse(BattlecamActivationPolicy.shouldActivate(true, true, BattlecamMode.OFF));
    }
}
