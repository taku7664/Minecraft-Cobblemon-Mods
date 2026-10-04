package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BattlecamModeTest {
    @Test
    void cyclesOffAutoManualAndBackToOff() {
        assertEquals(BattlecamMode.AUTO, BattlecamMode.OFF.next());
        assertEquals(BattlecamMode.MANUAL, BattlecamMode.AUTO.next());
        assertEquals(BattlecamMode.OFF, BattlecamMode.MANUAL.next());
    }
}
