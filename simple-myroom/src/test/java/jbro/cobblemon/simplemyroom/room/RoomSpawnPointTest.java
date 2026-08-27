package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomSpawnPointTest {
    private static final RoomArea AREA = new RoomArea(0, 0, 99, 64, 0, 99);

    @Test
    void acceptsFinitePointInsideRoomFootprint() {
        assertTrue(new RoomSpawnPoint(10.5, 65.0, 20.5, 180.0f, 20.0f).isInside(AREA));
        assertFalse(new RoomSpawnPoint(100.0, 65.0, 20.5, 0.0f, 0.0f).isInside(AREA));
    }

    @Test
    void rejectsNonFiniteCoordinatesAndRotation() {
        assertThrows(IllegalArgumentException.class, () -> new RoomSpawnPoint(Double.NaN, 65.0, 20.5, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new RoomSpawnPoint(10.5, 65.0, 20.5, Float.POSITIVE_INFINITY, 0.0f));
    }
}
