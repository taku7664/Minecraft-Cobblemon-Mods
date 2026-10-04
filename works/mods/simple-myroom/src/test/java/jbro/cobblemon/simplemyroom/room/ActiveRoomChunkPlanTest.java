package jbro.cobblemon.simplemyroom.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ActiveRoomChunkPlanTest {
    @Test
    void hundredByHundredAlignedRoomCoversFortyNineChunks() {
        var chunks = ActiveRoomChunkPlan.chunksFor(new RoomArea(0, 0, 99, 64, 0, 99), 64);

        assertEquals(49, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(0, 0)));
        assertTrue(chunks.contains(new ChunkPos(6, 6)));
    }

    @Test
    void negativeCoordinatesUseMinecraftChunkFlooring() {
        var chunks = ActiveRoomChunkPlan.chunksFor(new RoomArea(0, -17, -1, 64, -17, -1), 8);

        assertEquals(4, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(-2, -2)));
        assertTrue(chunks.contains(new ChunkPos(-1, -1)));
    }

    @Test
    void configuredChunkLimitRejectsOversizedRooms() {
        assertThrows(IllegalArgumentException.class,
            () -> ActiveRoomChunkPlan.chunksFor(new RoomArea(0, 0, 99, 64, 0, 99), 48));
    }
}
