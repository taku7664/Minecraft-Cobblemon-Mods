package jbro.cobblemon.policy.chat

import jbro.cobblemon.policy.JbroPolicy
import kotlin.math.PI
import kotlin.math.sin
import net.minecraft.resources.ResourceLocation

/** The hard Champion rank's red gradient: a band of light sweeping across the letters, one pass every [PERIOD_MILLIS]. */
object HardChampionGradient {
    /** The default glyphs under another name, so the client knows to draw this text in the gradient. */
    @JvmField
    val FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(JbroPolicy.MOD_ID, "hard_champion")
    const val PERIOD_MILLIS = 2_000L
    private const val DARK = 0xB00000
    private const val LIGHT = 0xFF7070
    /** Phase between neighbouring letters, so the band spans a few of them. */
    private const val LETTER_STEP = 0.55

    /** The colour of the letter at [index] at time [millis], as 0xRRGGBB. */
    @JvmStatic
    fun color(index: Int, millis: Long): Int {
        val phase = (millis % PERIOD_MILLIS).toDouble() / PERIOD_MILLIS * 2 * PI - index * LETTER_STEP
        val t = (sin(phase) + 1) / 2
        return mix(DARK, LIGHT, t)
    }

    private fun mix(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }
}
