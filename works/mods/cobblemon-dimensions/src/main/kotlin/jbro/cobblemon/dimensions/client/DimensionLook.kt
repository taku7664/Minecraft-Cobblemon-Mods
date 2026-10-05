package jbro.cobblemon.dimensions.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * How a dimension's sky and fog look, a first draft. Biome colors still come through; [tint] pulls the sky and fog
 * toward one hue by [strength] so the whole dimension reads as one place. [foggy] is the nether's thick near fog.
 */
class DimensionLook(
    cloudLevel: Float,
    private val foggy: Boolean,
    private val skyTint: Int? = null,
    private val fogTint: Int? = null,
    private val strength: Double = 0.0,
) : DimensionSpecialEffects(cloudLevel, true, SkyType.NORMAL, false, false) {

    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 {
        // The overworld's day-night darkening, then the tint.
        val lit = fogColor.multiply(brightness * 0.94 + 0.06, brightness * 0.94 + 0.06, brightness * 0.91 + 0.09)
        return tinted(lit, fogTint, brightness)
    }

    override fun isFoggyAt(x: Int, y: Int): Boolean = foggy

    fun tintSky(sky: Vec3, brightness: Float): Vec3 = tinted(sky, skyTint, brightness)

    private fun tinted(color: Vec3, tint: Int?, brightness: Float): Vec3 {
        if (tint == null || strength <= 0.0) return color
        // The tint dims with the sky so nights stay dark.
        val target = Vec3((tint shr 16 and 0xFF) / 255.0, (tint shr 8 and 0xFF) / 255.0, (tint and 0xFF) / 255.0).scale(brightness.toDouble())
        return color.lerp(target, strength)
    }
}
