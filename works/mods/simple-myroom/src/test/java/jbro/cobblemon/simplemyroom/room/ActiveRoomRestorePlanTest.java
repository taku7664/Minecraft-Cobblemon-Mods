package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveRoomRestorePlanTest {
    @Test
    void selectsInitializedActiveRoomsByIndexUpToLimit() {
        RoomRecord later = room(2, true, true);
        RoomRecord earlier = room(0, true, true);
        RoomRecord inactive = room(1, true, false);
        RoomRecord notReady = room(3, false, true);

        assertEquals(List.of(earlier, later), ActiveRoomRestorePlan.select(
            List.of(later, inactive, notReady, earlier), 2
        ));
        assertEquals(List.of(earlier), ActiveRoomRestorePlan.select(List.of(later, earlier), 1));
    }

    private static RoomRecord room(long index, boolean initialized, boolean active) {
        RoomRecord room = RoomRecord.create(new UUID(0, index + 1), "owner" + index, index, true, Set.of(), Set.of(), active);
        return initialized ? room.asInitialized(2) : room;
    }
}
