package jbro.cobblemon.simplemyroom.room;

import java.util.OptionalLong;

public record RoomLayout(int size, int spacing, int gridWidth, int floorY) {
    public RoomLayout {
        if (size <= 0 || spacing < size || gridWidth <= 0) {
            throw new IllegalArgumentException("Room layout dimensions are invalid.");
        }
    }

    public static RoomLayout legacyDefaults() {
        return new RoomLayout(100, 512, 1024, 64);
    }

    public RoomArea areaFor(long index) {
        if (index < 0) {
            throw new IllegalArgumentException("Room index must not be negative.");
        }
        long column = index % gridWidth;
        long row = index / gridWidth;
        int minX = Math.toIntExact(Math.multiplyExact(column, spacing));
        int minZ = Math.toIntExact(Math.multiplyExact(row, spacing));
        return new RoomArea(index, minX, minX + size - 1, floorY, minZ, minZ + size - 1);
    }

    public OptionalLong indexAt(int x, int z) {
        if (x < 0 || z < 0) {
            return OptionalLong.empty();
        }
        long column = x / spacing;
        long row = z / spacing;
        if (column >= gridWidth || x % spacing >= size || z % spacing >= size) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.addExact(Math.multiplyExact(row, gridWidth), column));
    }

    public OptionalLong boundaryIndexAt(int x, int z) {
        if (indexAt(x, z).isPresent()) {
            return OptionalLong.empty();
        }
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                OptionalLong adjacent = indexAt(x + offsetX, z + offsetZ);
                if (adjacent.isPresent()) {
                    return adjacent;
                }
            }
        }
        return OptionalLong.empty();
    }
}
