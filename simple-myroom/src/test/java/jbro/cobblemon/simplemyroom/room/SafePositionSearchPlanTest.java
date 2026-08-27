package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class SafePositionSearchPlanTest {
    @Test
    void startsAtRequestedPositionAndNeverRepeatsOffsets() {
        var candidates = SafePositionSearchPlan.offsets(2, 1);

        assertEquals(new SafePositionSearchPlan.Offset(0, 0, 0), candidates.getFirst());
        assertEquals(candidates.size(), new HashSet<>(candidates).size());
        assertTrue(candidates.contains(new SafePositionSearchPlan.Offset(2, 1, -2)));
        assertTrue(candidates.contains(new SafePositionSearchPlan.Offset(-2, -1, 2)));
    }

    @Test
    void zeroRangesOnlyCheckExactPosition() {
        assertEquals(1, SafePositionSearchPlan.offsets(0, 0).size());
    }
}
