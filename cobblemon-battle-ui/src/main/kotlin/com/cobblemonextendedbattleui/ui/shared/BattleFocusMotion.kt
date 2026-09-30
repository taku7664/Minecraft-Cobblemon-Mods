package jbro.cobblemon.battleui.extended.ui.shared

import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Time-based motion for battle focus, shared by mouse hover and keyboard focus. Each focusable element keeps its own
 * emphasis that eases in and out, and each group of elements keeps one cursor that glides to whichever element is
 * focused, so moving the focus reads as motion from one choice to the next instead of a state that snaps.
 * Keys are the widgets themselves (or any stable object for previews) and are held weakly.
 */
object BattleFocusMotion {
    /** Emphasis eases toward its target with this time constant: about 90% of the way in 0.2 s. */
    private const val EMPHASIS_SECONDS = 0.085
    /** The cursor follows a critically damped spring of this angular frequency. */
    private const val CURSOR_OMEGA = 26.0
    private const val PULSE_SECONDS = 1.6
    private const val BOB_SECONDS = 0.9

    private class Emphasis(var value: Double, var nanos: Long)
    private class Cursor(var position: Double, var velocity: Double, var target: Double, var nanos: Long)

    private val emphases = WeakHashMap<Any, Emphasis>()
    private val cursors = WeakHashMap<Any, Cursor>()

    /** The eased emphasis of [key], 0 at rest and 1 fully focused, moving toward [focused] since the last call. */
    @JvmStatic
    fun emphasis(key: Any, focused: Boolean): Float {
        val now = System.nanoTime()
        val state = emphases.getOrPut(key) { Emphasis(if (focused) 1.0 else 0.0, now) }
        state.value = step(state.value, if (focused) 1.0 else 0.0, seconds(now - state.nanos))
        state.nanos = now
        return state.value.toFloat()
    }

    /**
     * Where [group]'s cursor is now on its way to [target]. A new group, or one not drawn for a while, starts at its
     * target instead of flying in from where it was last left.
     */
    @JvmStatic
    fun cursor(group: Any, target: Float): Float {
        val now = System.nanoTime()
        val state = cursors[group]
        if (state == null || now - state.nanos > STALE_NANOS) {
            cursors[group] = Cursor(target.toDouble(), 0.0, target.toDouble(), now)
            return target
        }
        state.target = target.toDouble()
        var remaining = seconds(now - state.nanos)
        state.nanos = now
        // Small fixed steps keep the spring stable through a slow frame.
        while (remaining > 0) {
            val dt = minOf(remaining, 1.0 / 240)
            val acceleration = CURSOR_OMEGA * CURSOR_OMEGA * (state.target - state.position) - 2 * CURSOR_OMEGA * state.velocity
            state.velocity += acceleration * dt
            state.position += state.velocity * dt
            remaining -= dt
        }
        return state.position.toFloat()
    }

    /** A slow 0..1 breathing wave for focus light. */
    @JvmStatic
    fun pulse(): Float = wave(PULSE_SECONDS)

    /** A quicker -1..1 wave for the cursor's nudge toward its choice. */
    @JvmStatic
    fun bob(): Float = wave(BOB_SECONDS) * 2f - 1f

    /** Ease-out with a small overshoot, so a focused tile pops out and settles. */
    @JvmStatic
    fun overshoot(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f) - 1f
        val tension = 1.9f
        return 1f + t * t * ((tension + 1f) * t + tension)
    }

    internal fun step(value: Double, target: Double, seconds: Double): Double {
        if (seconds <= 0) return value
        return target + (value - target) * exp(-seconds / EMPHASIS_SECONDS)
    }

    private fun wave(period: Double): Float {
        val phase = (System.nanoTime() / 1_000_000_000.0) / period
        return ((sin(phase * 2 * PI) + 1) / 2).toFloat()
    }

    private fun seconds(nanos: Long) = (nanos / 1_000_000_000.0).coerceIn(0.0, 0.1)

    private const val STALE_NANOS = 250_000_000L
}
