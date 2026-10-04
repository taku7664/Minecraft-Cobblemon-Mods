package jbro.minecraft.roundingblock.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jbro.minecraft.roundingblock.mesh.MicroBlockShape;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RoundingBlockModelPluginTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyVanillaStairVariantMapsToExpectedMicroCellCount() {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) {
                assertCellCount(direction, half, StairsShape.STRAIGHT, 6);
                assertCellCount(direction, half, StairsShape.INNER_LEFT, 7);
                assertCellCount(direction, half, StairsShape.INNER_RIGHT, 7);
                assertCellCount(direction, half, StairsShape.OUTER_LEFT, 5);
                assertCellCount(direction, half, StairsShape.OUTER_RIGHT, 5);
            }
        }
    }

    @Test
    void slabAndFullCubeKeepTheirNonComplexProfiles() {
        MicroBlockShape slab = RoundingBlockModelPlugin.shapeFor(Blocks.STONE_SLAB.defaultBlockState());
        MicroBlockShape cube = RoundingBlockModelPlugin.shapeFor(Blocks.STONE.defaultBlockState());

        assertEquals(MicroBlockShape.BOTTOM_HALF, slab);
        assertEquals(MicroBlockShape.FULL, cube);
        assertTrue(!slab.isComplex() && !cube.isComplex());
    }

    private static void assertCellCount(Direction direction, Half half, StairsShape stairShape, int expected) {
        BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, direction)
            .setValue(BlockStateProperties.HALF, half)
            .setValue(BlockStateProperties.STAIRS_SHAPE, stairShape);
        MicroBlockShape shape = RoundingBlockModelPlugin.shapeFor(state);

        assertEquals(expected, shape.occupiedCellCount(), () -> direction + " " + half + " " + stairShape);
        assertTrue(shape.isComplex());
    }
}
