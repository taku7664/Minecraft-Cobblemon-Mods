package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DamagePolicyTest {
    @Test
    void playerDamageAndUnauthorizedEntityDamageAreIndependent() {
        assertFalse(DamagePolicy.canPlayerAttack(true, true, false));
        assertTrue(DamagePolicy.canPlayerAttack(true, false, false));
        assertFalse(DamagePolicy.canPlayerAttack(false, false, true));
        assertTrue(DamagePolicy.canPlayerAttack(false, false, false));
    }
}
