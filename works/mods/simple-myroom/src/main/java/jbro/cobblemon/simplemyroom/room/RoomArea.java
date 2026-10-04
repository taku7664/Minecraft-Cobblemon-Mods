package jbro.cobblemon.simplemyroom.room;

public record RoomArea(long index, int minX, int maxX, int floorY, int minZ, int maxZ) {
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int blockCount() {
        return Math.multiplyExact(maxX - minX + 1, maxZ - minZ + 1);
    }

    public double spawnX(double offset) {
        return (minX + maxX + 1) / 2.0 + offset;
    }

    public double spawnY(double offset) {
        return floorY + offset;
    }

    public double spawnZ(double offset) {
        return (minZ + maxZ + 1) / 2.0 + offset;
    }
}
