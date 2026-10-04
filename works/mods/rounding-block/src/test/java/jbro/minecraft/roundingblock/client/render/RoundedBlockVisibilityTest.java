package jbro.minecraft.roundingblock.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jbro.minecraft.roundingblock.mesh.MicroBlockShape;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoundedBlockVisibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void opaqueBlockKeepsItsFaceBehindCutoutLeaves() {
        assertFalse(RoundedBlockModel.connectsForMeshing(
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            MicroBlockShape.FULL
        ));
    }

    @Test
    void leavesStillJoinOpaqueTrunksAndOtherLeavesFromTheirOwnSide() {
        assertTrue(RoundedBlockModel.connectsForMeshing(
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(),
            MicroBlockShape.FULL
        ));
        assertTrue(RoundedBlockModel.connectsForMeshing(
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            MicroBlockShape.FULL
        ));
    }

    @Test
    void partialNeighborsRemainOnTheSharedMicroLattice() {
        MicroBlockShape stair = MicroBlockShape.builder()
            .occupy(0, 0, 0).occupy(1, 0, 0)
            .occupy(0, 0, 1).occupy(1, 0, 1)
            .occupy(0, 1, 0).occupy(1, 1, 0)
            .build();

        assertTrue(RoundedBlockModel.connectsForMeshing(
            Blocks.OAK_LOG.defaultBlockState(),
            Blocks.OAK_STAIRS.defaultBlockState(),
            stair
        ));
    }

    @Test
    void axialNeighborOffsetsMapToTheSixFacesOnly() {
        assertTrue(RoundedBlockModel.directionForNeighborOffset(-1, 0, 0) == Direction.WEST);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(1, 0, 0) == Direction.EAST);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(0, -1, 0) == Direction.DOWN);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(0, 1, 0) == Direction.UP);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(0, 0, -1) == Direction.NORTH);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(0, 0, 1) == Direction.SOUTH);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(1, 1, 0) == null);
        assertTrue(RoundedBlockModel.directionForNeighborOffset(0, 0, 0) == null);
    }
}
