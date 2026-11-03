package jbro.cobblemon.battlespeed.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpeedScopeTest {
    @Test
    void innerScopeWinsAndPopRestoresTheOuterOne() {
        Object tickBattle = new Object();
        Object effectBattle = new Object();
        SpeedScope.push(tickBattle, SpeedScope.Kind.BATTLE_TICK);
        SpeedScope.push(effectBattle, SpeedScope.Kind.MOVE_EFFECT);
        assertSame(effectBattle, SpeedScope.current().battle());
        assertEquals(SpeedScope.Kind.MOVE_EFFECT, SpeedScope.current().kind());
        SpeedScope.pop();
        assertSame(tickBattle, SpeedScope.current().battle());
        SpeedScope.pop();
        assertNull(SpeedScope.current());
        SpeedScope.pop();
        assertNull(SpeedScope.current());
    }

    @Test
    void scopesDoNotLeakAcrossThreads() throws Exception {
        SpeedScope.push(new Object(), SpeedScope.Kind.BATTLE_TICK);
        try {
            var seen = new AtomicReference<SpeedScope.Frame>(new SpeedScope.Frame(null, null));
            var thread = new Thread(() -> seen.set(SpeedScope.current()));
            thread.start();
            thread.join();
            assertNull(seen.get());
        } finally {
            SpeedScope.pop();
        }
    }
}
