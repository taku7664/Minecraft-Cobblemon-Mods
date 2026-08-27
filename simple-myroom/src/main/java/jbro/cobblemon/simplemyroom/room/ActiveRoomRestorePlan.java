package jbro.cobblemon.simplemyroom.room;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class ActiveRoomRestorePlan {
    private ActiveRoomRestorePlan() {
    }

    public static List<RoomRecord> select(Collection<RoomRecord> rooms, int maxActiveRooms) {
        if (maxActiveRooms < 1) throw new IllegalArgumentException("Active room limit must be positive.");
        return rooms.stream()
            .filter(room -> room.initialized() && room.keepActive())
            .sorted(Comparator.comparingLong(RoomRecord::index))
            .limit(maxActiveRooms)
            .toList();
    }
}
