package jbro.cobblemon.bettermusic.field;

public final class UndergroundDetector {
    private static final int MINIMUM_COVER_BLOCKS = 8;

    private UndergroundDetector() {
    }

    public static boolean isUnderground(boolean canSeeSky, int surfaceY, int playerY) {
        return !canSeeSky && surfaceY - playerY >= MINIMUM_COVER_BLOCKS;
    }
}
