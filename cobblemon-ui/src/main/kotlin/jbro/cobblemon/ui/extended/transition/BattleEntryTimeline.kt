package jbro.cobblemon.ui.extended.transition

import kotlin.math.abs

/**
 * What kind of battle is starting, which sets the entry transition's beats: when it flashes, how long the pattern
 * takes to cover the screen, how long the covered screen pulses, how long it takes to turn white ([revealMillis]) and
 * how long the white takes to give way to the battle ([fadeMillis]). The screen is fully white before the battle is
 * started, so the hitch of a battle opening happens behind the white.
 *
 * Wild and trainer battles run about four seconds. A legendary runs about six: it darkens the world, flashes three
 * times faster and faster with a shockwave on each, a slash splits the screen and two black plates close on it under
 * focus lines in its colour, the slash swells into light over the whole screen, which holds white for a second,
 * cracks, stays still for half a second, and comes apart in shards that drift out from the center and fade.
 */
enum class BattleEntryKind(
    val id: String,
    val flashStarts: List<Long>,
    val flashMillis: Long,
    val coverMillis: Long,
    val holdMillis: Long,
    val revealMillis: Long,
    val fadeMillis: Long,
) {
    LEGENDARY("legendary", listOf(450L, 850L, 1150L), 130, 1200, 450, 650, 800),
    WILD("wild", listOf(0L, 300L, 600L), 160, 1500, 500, 350, 1000),
    TRAINER("trainer", listOf(0L, 300L), 160, 1700, 500, 350, 1000);

    companion object {
        fun fromId(id: String?): BattleEntryKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The transition's timing, apart from drawing, in milliseconds since it started: flashes, the pattern covering the
 * screen, a pulse, white rising over the still covered screen until it is full ([readyAt], when the battle may start),
 * then, once the battle has opened ([revealAt], null until it has), the white giving way to it.
 */
object BattleEntryTimeline {
    /** How far behind the first piece the last one starts, as a share of the cover. */
    const val SPREAD = 1.1f
    /** A hold with no battle in sight past [readyAt] ends on its own, so a lost battle never leaves the screen covered. */
    const val HOLD_TIMEOUT_MILLIS = 6000L
    /** How long a legendary's shockwave takes to reach past the screen's corners. */
    const val RING_MILLIS = 450L
    /** How long the white stays after the battle opens before it gives way, so the opening's hitch stays hidden. */
    const val SETTLE_MILLIS = 150L
    /** How long a legendary's screen stays white, counted from when it is full, before it cracks. */
    const val WHITE_HOLD_MILLIS = 1000L
    /** How long a legendary's cracks take to run, and how long the cracked white then stays still. */
    const val CRACK_MILLIS = 300L
    const val STILL_MILLIS = 500L

    fun flashEnd(kind: BattleEntryKind): Long = kind.flashStarts.last() + kind.flashMillis

    fun coverEnd(kind: BattleEntryKind): Long = flashEnd(kind) + kind.coverMillis

    /** When the covered screen starts turning white. */
    fun riseStart(kind: BattleEntryKind): Long = coverEnd(kind) + kind.holdMillis

    /** When the screen is fully white and the battle may start. */
    fun readyAt(kind: BattleEntryKind): Long = riseStart(kind) + kind.revealMillis

    fun ready(kind: BattleEntryKind, elapsed: Long): Boolean = elapsed >= readyAt(kind)

    /**
     * The covered screen's breathing, 0 to 1, while it waits: a slow swell for wild and trainer battles, a double
     * heartbeat for a legendary. It eases in from the moment the cover closes.
     */
    fun pulse(kind: BattleEntryKind, elapsed: Long): Float {
        val since = elapsed - coverEnd(kind)
        if (since < 0) return 0f
        val ease = (since / 250f).coerceAtMost(1f)
        val wave = if (kind == BattleEntryKind.LEGENDARY) {
            val beat = (since % 700L) / 700f
            maxOf(1f - abs(beat - .12f) / .1f, .7f * (1f - abs(beat - .34f) / .1f), 0f)
        } else {
            .5f - .5f * kotlin.math.cos(since / 650f * Math.PI.toFloat())
        }
        return ease * wave
    }

    /** The white flash's strength, 0 to 1, rising and falling within each flash. */
    fun flash(kind: BattleEntryKind, elapsed: Long): Float {
        val start = kind.flashStarts.lastOrNull { it <= elapsed } ?: return 0f
        val within = elapsed - start
        if (within >= kind.flashMillis) return 0f
        val half = kind.flashMillis / 2f
        return 1f - abs(within - half) / half
    }

    /** How far the pattern has covered the screen, 0 to 1. */
    fun cover(kind: BattleEntryKind, elapsed: Long): Float =
        ((elapsed - flashEnd(kind)).toFloat() / kind.coverMillis).coerceIn(0f, 1f)

    fun covered(kind: BattleEntryKind, elapsed: Long): Boolean = cover(kind, elapsed) >= 1f

    /** How far the white (or a legendary's light) has risen over the covered screen, 0 to 1. */
    fun rise(kind: BattleEntryKind, elapsed: Long): Float =
        ((elapsed - riseStart(kind)).toFloat() / kind.revealMillis).coerceIn(0f, 1f)

    /** Whether the pattern is gone: it stays whole under the rising white and goes once the white is full. */
    fun patternGone(kind: BattleEntryKind, elapsed: Long): Boolean = elapsed >= readyAt(kind)

    /** When a wild or trainer battle's white starts to fade, once the battle has opened at [revealAt]. */
    fun fadeStart(kind: BattleEntryKind, revealAt: Long?): Long? =
        revealAt?.let { maxOf(it, readyAt(kind)) + SETTLE_MILLIS }

    /** A wild or trainer battle's white: rising over the covered screen, full until the battle opens, then fading. */
    fun white(kind: BattleEntryKind, elapsed: Long, revealAt: Long?): Float {
        if (elapsed < readyAt(kind)) return rise(kind, elapsed)
        val fade = fadeStart(kind, revealAt) ?: return 1f
        return 1f - smooth(((elapsed - fade).toFloat() / kind.fadeMillis).coerceIn(0f, 1f))
    }

    /** How wide a legendary's slash has swelled toward covering the screen, 0 to 1: slow to start, then flooding. */
    fun beam(kind: BattleEntryKind, elapsed: Long): Float {
        val share = rise(kind, elapsed)
        return share * share * share
    }

    /** When a legendary's white cracks: a second after it is full, and not before the battle has opened and settled. */
    fun crackStart(kind: BattleEntryKind, revealAt: Long?): Long? =
        revealAt?.let { maxOf(readyAt(kind) + WHITE_HOLD_MILLIS, maxOf(it, readyAt(kind)) + SETTLE_MILLIS) }

    /** How far a legendary's cracks have run across the white, 0 to 1. */
    fun crack(kind: BattleEntryKind, elapsed: Long, revealAt: Long?): Float {
        val start = crackStart(kind, revealAt) ?: return 0f
        val share = ((elapsed - start).toFloat() / CRACK_MILLIS).coerceIn(0f, 1f)
        return 1f - (1f - share) * (1f - share)
    }

    /** When a legendary's cracked white comes apart, after its cracks have run and it has stayed still. */
    fun shatterStart(kind: BattleEntryKind, revealAt: Long?): Long? =
        crackStart(kind, revealAt)?.let { it + CRACK_MILLIS + STILL_MILLIS }

    /** How far a legendary's white has come apart and faded away, 0 to 1. */
    fun shatter(kind: BattleEntryKind, elapsed: Long, revealAt: Long?): Float {
        val start = shatterStart(kind, revealAt) ?: return 0f
        return ((elapsed - start).toFloat() / kind.fadeMillis).coerceIn(0f, 1f)
    }

    /** When nothing is left on screen, for a battle that opened at [revealAt]. */
    fun finishedAt(kind: BattleEntryKind, revealAt: Long?): Long? =
        if (kind == BattleEntryKind.LEGENDARY) shatterStart(kind, revealAt)?.let { it + kind.fadeMillis }
        else fadeStart(kind, revealAt)?.let { it + kind.fadeMillis }

    fun revealed(kind: BattleEntryKind, elapsed: Long, revealAt: Long?): Boolean =
        finishedAt(kind, revealAt)?.let { elapsed >= it } ?: false

    /** A legendary's darkening of the world before its pattern, 0 to 1, gone with the pattern. */
    fun dim(kind: BattleEntryKind, elapsed: Long): Float {
        if (kind != BattleEntryKind.LEGENDARY || patternGone(kind, elapsed)) return 0f
        return smooth((elapsed / kind.flashStarts.first().toFloat()).coerceIn(0f, 1f))
    }

    /** Each legendary shockwave's progress, 0 to 1, for the flashes it has followed so far. */
    fun rings(kind: BattleEntryKind, elapsed: Long): List<Float> {
        if (kind != BattleEntryKind.LEGENDARY) return emptyList()
        return kind.flashStarts.mapNotNull { start ->
            val progress = (elapsed - start).toFloat() / RING_MILLIS
            progress.takeIf { it in 0f..1f }
        }
    }

    /** A legendary's black bars, 0 (gone) to 1 (in): they slam in on the last flash and go with the pattern. */
    fun bars(kind: BattleEntryKind, elapsed: Long): Float {
        if (kind != BattleEntryKind.LEGENDARY || patternGone(kind, elapsed)) return 0f
        val slam = ((elapsed - kind.flashStarts.last()) / 120f).coerceIn(0f, 1f)
        return 1f - (1f - slam) * (1f - slam) * (1f - slam)
    }

    /** How hard a legendary shakes, 0 to 1: a jolt on each flash, then a rumble that settles as the cover closes. */
    fun shake(kind: BattleEntryKind, elapsed: Long): Float {
        if (kind != BattleEntryKind.LEGENDARY) return 0f
        val jolt = kind.flashStarts.maxOf { start ->
            val since = elapsed - start
            if (since < 0) 0f else (1f - since / 180f).coerceAtLeast(0f)
        }
        val cover = cover(kind, elapsed)
        val rumble = if (elapsed >= flashEnd(kind)) .45f * (1f - cover) else 0f
        return maxOf(jolt, rumble)
    }

    /**
     * One pattern piece's share at an overall [progress], for a piece [distance] (0 where the pattern starts, 1 where
     * it ends) along it: the first pieces lead, the last follow.
     */
    fun piece(progress: Float, distance: Float): Float =
        (progress * (1f + SPREAD) - distance.coerceIn(0f, 1f) * SPREAD).coerceIn(0f, 1f)

    /** How far a legendary's slash has crossed the screen, 0 to 1: it leads the plates by a quarter of the cover. */
    fun slash(cover: Float): Float = smooth((cover / .25f).coerceIn(0f, 1f))

    /** How far a legendary's two plates have closed on the slash, 0 to 1, starting once the slash is across. */
    fun plates(cover: Float): Float {
        val closing = ((cover - .2f) / .8f).coerceIn(0f, 1f)
        return 1f - (1f - closing) * (1f - closing) * (1f - closing)
    }

    /** A piece's size while covering, then clearing. */
    fun pieceSize(cover: Float, reveal: Float, distance: Float): Float =
        smooth(piece(cover, distance)) * (1f - smooth(piece(reveal, distance)))

    fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}
