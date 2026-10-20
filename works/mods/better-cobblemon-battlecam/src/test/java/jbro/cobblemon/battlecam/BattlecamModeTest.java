package jbro.cobblemon.battlecam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BattlecamModeTest {
    @Test
    void cyclesOffAutoManualClassicAndBackToOff() {
        assertEquals(BattlecamMode.AUTO, BattlecamMode.OFF.next());
        assertEquals(BattlecamMode.MANUAL, BattlecamMode.AUTO.next());
        assertEquals(BattlecamMode.CLASSIC, BattlecamMode.MANUAL.next());
        assertEquals(BattlecamMode.OFF, BattlecamMode.CLASSIC.next());
    }
}
