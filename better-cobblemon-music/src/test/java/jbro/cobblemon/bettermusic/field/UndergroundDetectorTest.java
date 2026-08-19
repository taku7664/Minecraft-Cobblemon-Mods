package jbro.cobblemon.bettermusic.field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class UndergroundDetectorTest {
    @Test
    void requiresBothSkyOcclusionAndEightBlocksOfCover() {
        assertFalse(UndergroundDetector.isUnderground(true, 80, 60));
        assertFalse(UndergroundDetector.isUnderground(false, 80, 73));
        assertTrue(UndergroundDetector.isUnderground(false, 80, 72));
    }
}
