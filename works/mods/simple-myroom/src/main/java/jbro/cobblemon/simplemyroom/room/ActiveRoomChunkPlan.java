package jbro.cobblemon.simplemyroom.room;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public final class ActiveRoomChunkPlan {
    private ActiveRoomChunkPlan() {
    }

    public static Set<ChunkPos> chunksFor(RoomArea area, int maxChunks) {
        int minChunkX = SectionPos.blockToSectionCoord(area.minX());
        int maxChunkX = SectionPos.blockToSectionCoord(area.maxX());
        int minChunkZ = SectionPos.blockToSectionCoord(area.minZ());
        int maxChunkZ = SectionPos.blockToSectionCoord(area.maxZ());
        long count = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (maxChunks < 1 || count > maxChunks) {
            throw new IllegalArgumentException("Room requires " + count + " ticking chunks, exceeding the configured limit of " + maxChunks + ".");
        }
        Set<ChunkPos> chunks = new LinkedHashSet<>((int) count);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return Set.copyOf(chunks);
    }
}
