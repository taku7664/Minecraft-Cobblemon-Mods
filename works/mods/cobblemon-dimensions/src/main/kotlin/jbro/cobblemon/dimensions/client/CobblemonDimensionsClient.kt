package jbro.cobblemon.dimensions.client

import jbro.cobblemon.dimensions.CobblemonDimensions
import jbro.cobblemon.dimensions.portal.PortalBlocks
import jbro.cobblemon.dimensions.wormhole.Wormholes
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers

/** Registers each dimension's look; the dimension types name them in `effects`. */
object CobblemonDimensionsClient : ClientModInitializer {
    override fun onInitializeClient() {
        BlockEntityRenderers.register(PortalBlocks.PORTAL_ENTITY, ::TintedPortalRenderer)
        EntityRendererRegistry.register(Wormholes.TYPE, ::UltraWormholeRenderer)
        DimensionCaptureHarness.installIfRequested()
        // Ultra Space: thick fog, no clouds; each Ultra biome brings its own sky and fog colors.
        DimensionRenderingRegistry.registerDimensionEffects(CobblemonDimensions.id("ultra_space"),
            DimensionLook(Float.NaN, foggy = true))
        // The ancient past: an amber haze and low clouds.
        DimensionRenderingRegistry.registerDimensionEffects(CobblemonDimensions.id("ancient"),
            DimensionLook(150f, foggy = false, skyTint = 0xF0A060, fogTint = 0xE8904A, strength = 0.35))
        // The far future: a violet sky over cyan fog, no clouds.
        DimensionRenderingRegistry.registerDimensionEffects(CobblemonDimensions.id("future"),
            DimensionLook(Float.NaN, foggy = false, skyTint = 0x7E5CFF, fogTint = 0x3FE0E8, strength = 0.4))
    }
}
