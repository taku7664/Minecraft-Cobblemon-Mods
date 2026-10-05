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
        // The ancient past: a warm, humid haze, thickest over the jungle and the volcanoes; low clouds; a huge red sun.
        DimensionRenderingRegistry.registerDimensionEffects(CobblemonDimensions.id("ancient"),
            DimensionLook(150f, foggy = false, skyTint = 0xF0A060, fogTint = 0xE8904A, strength = 0.35,
                haze = DimensionLook.Haze(0.15f, 0.9f, 64f, mapOf("ancient_jungle" to 0.7f, "ancient_volcano" to 0.6f)),
                sunTexture = CobblemonDimensions.id("textures/environment/ancient_sun.png"), sunSize = 60f))
        // The far future: a violet sky over cyan fog, no clouds. Clear near by, glowing far off and through the night,
        // stars by day and a ring across the sky.
        DimensionRenderingRegistry.registerDimensionEffects(CobblemonDimensions.id("future"),
            DimensionLook(Float.NaN, foggy = false, skyTint = 0x7E5CFF, fogTint = 0x3FE0E8, strength = 0.4,
                haze = DimensionLook.Haze(0.3f, 0.9f, 64f, mapOf("future_neon_forest" to 0.65f)),
                nightGlow = 0.18, dayStars = 0.45f, ring = true))
    }
}
