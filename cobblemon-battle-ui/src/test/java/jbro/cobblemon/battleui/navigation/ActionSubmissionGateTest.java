package jbro.cobblemon.battleui.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionSubmissionGateTest {
    @Test
    void acceptsOneActionUntilServerStateUnlocksIt() {
        ActionSubmissionGate gate = new ActionSubmissionGate();

        assertTrue(gate.tryLock());
        assertFalse(gate.tryLock());
        gate.unlock();
        assertTrue(gate.tryLock());
    }
}
