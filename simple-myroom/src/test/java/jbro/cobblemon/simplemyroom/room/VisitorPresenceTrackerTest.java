package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisitorPresenceTrackerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void detectsEnteringLeavingMovingAndDisconnecting() {
        var outside = Map.<UUID, VisitorPresenceTracker.Presence>of();
        var roomOne = Map.of(PLAYER, new VisitorPresenceTracker.Presence(1, "Guest"));
        var roomTwo = Map.of(PLAYER, new VisitorPresenceTracker.Presence(2, "Guest"));

        assertEquals(1, VisitorPresenceTracker.diff(outside, roomOne).size());
        assertEquals(null, VisitorPresenceTracker.diff(outside, roomOne).getFirst().from());
        assertEquals(1, VisitorPresenceTracker.diff(roomOne, outside).getFirst().from().roomIndex());
        assertEquals(2, VisitorPresenceTracker.diff(roomOne, roomTwo).getFirst().to().roomIndex());
        assertEquals(null, VisitorPresenceTracker.diff(roomOne, outside).getFirst().to());
    }

    @Test
    void ignoresPlayersWhoseRoomDidNotChange() {
        var before = Map.of(PLAYER, new VisitorPresenceTracker.Presence(1, "Guest"));
        var after = Map.of(PLAYER, new VisitorPresenceTracker.Presence(1, "RenamedGuest"));

        assertEquals(0, VisitorPresenceTracker.diff(before, after).size());
    }
}
