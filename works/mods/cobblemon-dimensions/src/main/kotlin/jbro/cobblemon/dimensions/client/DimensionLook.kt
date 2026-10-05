package jbro.cobblemon.dimensions.client

import net.minecraft.Util
import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * How a dimension's sky and fog look. Biome colors still come through; the tints pull the sky and fog toward one hue
 * by [strength] so the whole dimension reads as one place. [foggy] is the nether's thick near fog.
 *
 * - [haze]: terrain fog drawn closer than the overworld's, thicker in some biomes.
 * - [nightGlow]: how much of the tint stays on the sky and fog at night, so the horizon glows instead of going black.
 * - [sunTexture], [sunSize]: a different sun; the overworld's is 30.
 * - [dayStars]: stars stay at least this bright by day.
 * - [ring]: a ring arching across the sky, drawn by [SkyExtras].
 */
class DimensionLook(
    cloudLevel: Float,
    private val foggy: Boolean,
    private val skyTint: Int? = null,
    private val fogTint: Int? = null,
    private val strength: Double = 0.0,
    val haze: Haze? = null,
    private val nightGlow: Double = 0.0,
    val sunTexture: ResourceLocation? = null,
    val sunSize: Float = 30f,
    val dayStars: Float = 0f,
    val ring: Boolean = false,
) : DimensionSpecialEffects(cloudLevel, true, SkyType.NORMAL, false, false) {

    /**
     * Terrain fog as fractions of the render distance: it starts at [start] and is solid at [end], or at the [biomes]
     * value for the biome the camera stands in. The solid point never comes closer than [minEnd] blocks, so a short
     * render distance does not blind the player.
     */
    class Haze(val start: Float, val end: Float, val minEnd: Float, val biomes: Map<String, Float> = emptyMap())

    private var hazeEnd = Float.NaN
    private var hazeAt = 0L

    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 {
        // The overworld's day-night darkening, then the tint.
        val lit = fogColor.multiply(brightness * 0.94 + 0.06, brightness * 0.94 + 0.06, brightness * 0.91 + 0.09)
        return tinted(lit, fogTint, brightness)
    }

    override fun isFoggyAt(x: Int, y: Int): Boolean = foggy

    fun tintSky(sky: Vec3, brightness: Float): Vec3 = tinted(sky, skyTint, brightness)

    /** Where the fog starts and turns solid, in blocks, for a camera standing in [biome]. Eases across biome borders. */
    fun hazeRange(haze: Haze, biome: String?, renderDistance: Float): Pair<Float, Float> {
        val target = haze.biomes[biome] ?: haze.end
        val now = Util.getMillis()
        hazeEnd = if (hazeEnd.isNaN() || now - hazeAt > 1000) target
        else hazeEnd + (target - hazeEnd) * ((now - hazeAt) / 2000f).coerceIn(0f, 1f)
        hazeAt = now
        val end = (renderDistance * hazeEnd).coerceAtLeast(haze.minEnd).coerceAtMost(renderDistance)
        return (renderDistance * haze.start).coerceAtMost(end * 0.5f) to end
    }

    private fun tinted(color: Vec3, tint: Int?, brightness: Float): Vec3 {
        if (tint == null || strength <= 0.0) return color
        val rgb = Vec3((tint shr 16 and 0xFF) / 255.0, (tint shr 8 and 0xFF) / 255.0, (tint and 0xFF) / 255.0)
        // The tint dims with the sky so nights stay dark, all but the glow.
        val mixed = color.lerp(rgb.scale(brightness.toDouble()), strength)
        return if (nightGlow > 0.0) mixed.add(rgb.scale(nightGlow * (1 - brightness))) else mixed
    }
}
