package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jbro.cobblemon.bettermusic.field.UndergroundDetector;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

final class MinecraftFieldMusicSamplerTest {
    @Test
    void caveCoverIgnoresTheLeafCanopyButStillCountsAStoneRoof() {
        assertEquals(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            MinecraftFieldMusicSampler.CAVE_COVER_HEIGHTMAP);

        // The visible canopy may be high, but the leaf-free surface is at the player's feet.
        assertFalse(UndergroundDetector.isUnderground(false, 84, 84));
        assertTrue(UndergroundDetector.isUnderground(false, 100, 84));
    }
}
