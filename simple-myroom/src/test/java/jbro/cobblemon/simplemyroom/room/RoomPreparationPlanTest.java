package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoomPreparationPlanTest {
    @Test
    void countsPlatformAndFullHeightBoundaryWork() {
        RoomArea area = new RoomArea(0, 0, 99, 64, 0, 99);

        assertEquals(165_136L, RoomPreparationPlan.blockCount(area, -64, 320, true, true));
        assertEquals(41L, RoomPreparationPlan.batchCount(165_136L, 4096));
    }
}
