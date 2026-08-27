package jbro.cobblemon.simplemyroom.room;

public final class RoomPreparationPlan {
    private RoomPreparationPlan() {
    }

    public static long blockCount(RoomArea area, int minY, int maxY, boolean platform, boolean boundary) {
        long count = platform ? area.blockCount() : 0L;
        if (boundary) {
            long perimeter = 2L * (area.maxX() - area.minX() + 3L) + 2L * (area.maxZ() - area.minZ() + 1L);
            count += perimeter * Math.max(0L, (long) maxY - minY);
        }
        return count;
    }

    public static long batchCount(long blocks, int blocksPerTick) {
        if (blocks < 0 || blocksPerTick < 1) throw new IllegalArgumentException("Room preparation budget is invalid.");
        return (blocks + blocksPerTick - 1L) / blocksPerTick;
    }
}
