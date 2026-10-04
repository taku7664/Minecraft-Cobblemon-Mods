package jbro.minecraft.roundingblock.client.render.fluid;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import org.junit.jupiter.api.Test;

final class RoundedWaterRenderHandlerContractTest {
    @Test
    void supportedFabricApiCanClearItsOverrideMarkerWhenDisabled() throws Exception {
        Class<?> implementation = Class.forName(
            "net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderHandlerRegistryImpl"
        );
        assertNotNull(implementation.getDeclaredField("modHandlers"));
        assertNotNull(FluidRenderHandlerRegistry.class.getMethod("getOverride", net.minecraft.world.level.material.Fluid.class));
    }

    @Test
    void wrapperUsesThePublicFluidGeometryHook() throws Exception {
        assertNotNull(FluidRenderHandler.class.getMethod(
            "renderFluid",
            net.minecraft.core.BlockPos.class,
            net.minecraft.world.level.BlockAndTintGetter.class,
            com.mojang.blaze3d.vertex.VertexConsumer.class,
            net.minecraft.world.level.block.state.BlockState.class,
            net.minecraft.world.level.material.FluidState.class
        ));
    }

    @Test
    void neighboringSolidCoordinatesStayRelativeToTheWatersSection() {
        assertEquals(16, RoundedWaterRenderHandler.sectionBaseCoordinate(15, 16));
        assertEquals(-1, RoundedWaterRenderHandler.sectionBaseCoordinate(16, 15));
        assertEquals(8, RoundedWaterRenderHandler.sectionBaseCoordinate(8, 8));
    }

    @Test
    void allocationFreeHeightCombinerKeepsVanillasWeightedSurfaceRule() {
        assertEquals(0.9F, RoundedWaterRenderHandler.averageHeightSamples(0.9F, -1.0F, -1.0F, -1.0F), 1.0e-6F);
        assertEquals(0.725F, RoundedWaterRenderHandler.averageHeightSamples(0.0F, 0.7F, 0.8F, -1.0F), 1.0e-6F);
        assertEquals(0.0F, RoundedWaterRenderHandler.averageHeightSamples(-1.0F, -1.0F, -1.0F, -1.0F), 1.0e-6F);
    }

}
