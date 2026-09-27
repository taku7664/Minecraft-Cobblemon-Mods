package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SwitchHealthTest {
    @Test
    void parsesShowdownHealthWithoutTrustingMalformedOrFaintedValues() {
        assertEquals(0.75f, SwitchHealth.ratio("90/120", 120));
        assertEquals(0f, SwitchHealth.ratio("0 fnt", 120));
        assertEquals(0f, SwitchHealth.ratio("fnt", 120));
        assertEquals(0f, SwitchHealth.ratio("?", 120));
        assertEquals(1f, SwitchHealth.ratio("300/120", 120));
        assertEquals(0f, SwitchHealth.ratio("90/120", 0));
    }
}
