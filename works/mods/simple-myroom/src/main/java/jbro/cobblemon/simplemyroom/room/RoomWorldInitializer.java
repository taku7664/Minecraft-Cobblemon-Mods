package jbro.cobblemon.simplemyroom.room;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class RoomWorldInitializer {
    public PreparationTask createTask(ServerLevel level, RoomArea area, boolean preserveExisting) {
        var config = SimpleMyRoom.config().layout;
        int minY = config.boundaryFromMinToMaxBuildHeight ? level.getMinBuildHeight() : area.floorY();
        int maxY = config.boundaryFromMinToMaxBuildHeight ? level.getMaxBuildHeight() : area.floorY() + 4;
        return new PreparationTask(
            level,
            area,
            config.createPlatform,
            config.createBoundary,
            preserveExisting && config.preserveExistingBlocksOnUpgrade,
            resolveBlock(config.platformBlock, Blocks.GRASS_BLOCK).defaultBlockState(),
            resolveBlock(config.boundaryBlock, Blocks.BARRIER).defaultBlockState(),
            minY,
            maxY
        );
    }

    public void prepareRoom(ServerLevel level, RoomArea area, boolean preserveExisting) {
        PreparationTask task = createTask(level, area, preserveExisting);
        while (!task.done()) task.process(Integer.MAX_VALUE);
    }

    private static Block resolveBlock(String idText, Block fallback) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        return id == null ? fallback : BuiltInRegistries.BLOCK.getOptional(id).orElse(fallback);
    }

    public static final class PreparationTask {
        private final ServerLevel level;
        private final RoomArea area;
        private final boolean preserveExisting;
        private final BlockState platform;
        private final BlockState boundary;
        private final int minY;
        private final int maxY;
        private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        private Phase phase;
        private int x;
        private int y;
        private int z;
        private boolean secondSide;

        private PreparationTask(
            ServerLevel level,
            RoomArea area,
            boolean createPlatform,
            boolean createBoundary,
            boolean preserveExisting,
            BlockState platform,
            BlockState boundary,
            int minY,
            int maxY
        ) {
            this.level = level;
            this.area = area;
            this.preserveExisting = preserveExisting;
            this.platform = platform;
            this.boundary = boundary;
            this.minY = minY;
            this.maxY = maxY;
            if (createPlatform) {
                phase = Phase.PLATFORM;
                x = area.minX();
                z = area.minZ();
            } else if (createBoundary) {
                startBoundary();
            } else {
                phase = Phase.DONE;
            }
            this.createBoundary = createBoundary;
        }

        private final boolean createBoundary;

        public int process(int budget) {
            if (budget < 1) throw new IllegalArgumentException("Room preparation budget must be positive.");
            int processed = 0;
            while (processed < budget && phase != Phase.DONE) {
                switch (phase) {
                    case PLATFORM -> placePlatform();
                    case NORTH_SOUTH -> placeNorthSouthBoundary();
                    case EAST_WEST -> placeEastWestBoundary();
                    case DONE -> {
                    }
                }
                processed++;
            }
            return processed;
        }

        public boolean done() {
            return phase == Phase.DONE;
        }

        private void placePlatform() {
            position.set(x, area.floorY(), z);
            BlockState existing = level.getBlockState(position);
            if (!preserveExisting || existing.isAir() || existing.is(Blocks.GRASS_BLOCK)) {
                level.setBlock(position, platform, Block.UPDATE_CLIENTS);
            }
            z++;
            if (z > area.maxZ()) {
                z = area.minZ();
                x++;
                if (x > area.maxX()) {
                    if (createBoundary) startBoundary(); else phase = Phase.DONE;
                }
            }
        }

        private void startBoundary() {
            if (minY >= maxY) {
                phase = Phase.DONE;
                return;
            }
            phase = Phase.NORTH_SOUTH;
            y = minY;
            x = area.minX() - 1;
            secondSide = false;
        }

        private void placeNorthSouthBoundary() {
            int targetZ = secondSide ? area.maxZ() + 1 : area.minZ() - 1;
            level.setBlock(position.set(x, y, targetZ), boundary, Block.UPDATE_CLIENTS);
            if (!secondSide) {
                secondSide = true;
                return;
            }
            secondSide = false;
            x++;
            if (x > area.maxX() + 1) {
                phase = Phase.EAST_WEST;
                z = area.minZ();
            }
        }

        private void placeEastWestBoundary() {
            int targetX = secondSide ? area.maxX() + 1 : area.minX() - 1;
            level.setBlock(position.set(targetX, y, z), boundary, Block.UPDATE_CLIENTS);
            if (!secondSide) {
                secondSide = true;
                return;
            }
            secondSide = false;
            z++;
            if (z > area.maxZ()) {
                y++;
                if (y >= maxY) {
                    phase = Phase.DONE;
                } else {
                    phase = Phase.NORTH_SOUTH;
                    x = area.minX() - 1;
                }
            }
        }

        private enum Phase {
            PLATFORM,
            NORTH_SOUTH,
            EAST_WEST,
            DONE
        }
    }
}
