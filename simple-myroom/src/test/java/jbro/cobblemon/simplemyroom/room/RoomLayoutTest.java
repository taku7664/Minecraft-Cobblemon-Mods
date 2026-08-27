package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomLayoutTest {
    @Test
    void legacyDefaultsPreserveExistingRoomCoordinates() {
        RoomLayout layout = RoomLayout.legacyDefaults();

        assertEquals(new RoomArea(0, 0, 99, 64, 0, 99), layout.areaFor(0));
        assertEquals(new RoomArea(1, 512, 611, 64, 0, 99), layout.areaFor(1));
        assertEquals(new RoomArea(1024, 0, 99, 64, 512, 611), layout.areaFor(1024));
    }

    @Test
    void allocationAndBoundaryAreDistinct() {
        RoomLayout layout = RoomLayout.legacyDefaults();

        assertEquals(0L, layout.indexAt(99, 99).orElseThrow());
        assertFalse(layout.indexAt(100, 99).isPresent());
        assertEquals(0L, layout.boundaryIndexAt(100, 99).orElseThrow());
        assertFalse(layout.boundaryIndexAt(101, 99).isPresent());
    }

    @Test
    void invalidOrOverlappingLayoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RoomLayout(0, 512, 1024, 64));
        assertThrows(IllegalArgumentException.class, () -> new RoomLayout(100, 99, 1024, 64));
        assertThrows(IllegalArgumentException.class, () -> new RoomLayout(100, 512, 0, 64));
    }

    @Test
    void areaContainsEveryHeightAtOwnedXZOnly() {
        RoomArea area = RoomLayout.legacyDefaults().areaFor(0);

        assertTrue(area.contains(50, -64, 50));
        assertTrue(area.contains(50, 319, 50));
        assertFalse(area.contains(100, 64, 50));
    }
}
