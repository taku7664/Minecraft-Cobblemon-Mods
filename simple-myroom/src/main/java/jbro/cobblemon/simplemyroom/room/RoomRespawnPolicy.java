package jbro.cobblemon.simplemyroom.room;

final class RoomRespawnPolicy {
    private RoomRespawnPolicy() {
    }

    static Decision decide(boolean diedInRoom, boolean respawnedInRoom, boolean targetRoomAllowsPlayer) {
        if (!respawnedInRoom) {
            return new Decision(true, false);
        }
        if (!targetRoomAllowsPlayer) {
            return new Decision(true, true);
        }
        return new Decision(!diedInRoom, false);
    }

    record Decision(boolean clearReturnPoint, boolean ejectToOverworld) {
    }
}
