package jbro.cobblemon.simplemyroom.room;

public record RoomSpawnPoint(double x, double y, double z, float yaw, float pitch) {
    public RoomSpawnPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Room spawn point must contain finite values.");
        }
    }

    public boolean isInside(RoomArea area) {
        return area.contains((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
