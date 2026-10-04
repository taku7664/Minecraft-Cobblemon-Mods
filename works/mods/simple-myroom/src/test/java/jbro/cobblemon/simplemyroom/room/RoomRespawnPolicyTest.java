package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoomRespawnPolicyTest {
    @Test
    void respawnOutsideRoomClearsStaleReturnPoint() {
        assertEquals(new RoomRespawnPolicy.Decision(true, false), RoomRespawnPolicy.decide(true, false, true));
    }

    @Test
    void unauthorizedRoomRespawnIsEjected() {
        assertEquals(new RoomRespawnPolicy.Decision(true, true), RoomRespawnPolicy.decide(true, true, false));
    }

    @Test
    void validRoomRespawnAfterRoomDeathKeepsReturnPoint() {
        assertEquals(new RoomRespawnPolicy.Decision(false, false), RoomRespawnPolicy.decide(true, true, true));
    }
}
