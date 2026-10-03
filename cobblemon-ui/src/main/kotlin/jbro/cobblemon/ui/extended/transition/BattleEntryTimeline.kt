package jbro.cobblemon.ui.extended.transition

/**
 * What kind of battle is starting, which sets the entry transition's beats: when it flashes, how long the pattern
 * takes to cover the screen, how fast the pattern clears and how slowly the white fades into the battle. A legendary
 * first darkens the world, flashes three times faster and faster with a shockwave on each, and shakes as its pattern
 * closes in.
 */
enum class BattleEntryKind(
    val id: String,
    val flashStarts: List<Long>,
    val flashMillis: Long,
    val coverMillis: Long,
    val revealMillis: Long,
    val fadeMillis: Long,
) {
    LEGENDARY("legendary", listOf(260L, 470L, 620L), 110, 600, 320, 900),
    WILD("wild", listOf(0L), 140, 400, 280, 520),
    TRAINER("trainer", listOf(0L), 140, 440, 300, 560);

    companion object {
        fun fromId(id: String?): BattleEntryKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The transition's timing, apart from drawing: flashes, then the pattern covering the screen, a hold while the server
 * starts the battle, then white over the pattern that slowly fades to show the battle.
 */
object BattleEntryTimeline {
    /** How far behind the first piece the last one starts, as a share of the cover. */
    const val SPREAD = 1.1f
    /** A hold with no battle in sight ends on its own, so a lost battle never leaves the screen covered. */
    const val HOLD_TIMEOUT_MILLIS = 6000L
    /** How long a legendary's shockwave takes to reach past the screen's corners. */
    const val RING_MILLIS = 450L
    /** The share of the reveal spent turning the covered screen white, before the white starts to fade. */
    const val WHITE_RISE = .4f

    fun flashEnd(kind: BattleEntryKind): Long = kind.flashStarts.last() + kind.flashMillis

    fun coverEnd(kind: BattleEntryKind): Long = flashEnd(kind) + kind.coverMillis

    /** The white flash's strength, 0 to 1, rising and falling within each flash. */
    fun flash(kind: BattleEntryKind, elapsed: Long): Float {
        val start = kind.flashStarts.lastOrNull { it <= elapsed } ?: return 0f
        val within = elapsed - start
        if (within >= kind.flashMillis) return 0f
        val half = kind.flashMillis / 2f
        return 1f - kotlin.math.abs(within - half) / half
    }

    /** How far the pattern has covered the screen, 0 to 1. */
    fun cover(kind: BattleEntryKind, elapsed: Long): Float =
        ((elapsed - flashEnd(kind)).toFloat() / kind.coverMillis).coerceIn(0f, 1f)

    fun covered(kind: BattleEntryKind, elapsed: Long): Boolean = cover(kind, elapsed) >= 1f

    /** How far the pattern has cleared, 0 to 1, since the reveal began; it is gone by the time the white is full. */
    fun reveal(kind: BattleEntryKind, sinceReveal: Long): Float =
        (sinceReveal.toFloat() / (kind.revealMillis * WHITE_RISE)).coerceIn(0f, 1f)

    /** The white over the screen since the reveal began: it rises over the pattern, then slowly fades. */
    fun white(kind: BattleEntryKind, sinceReveal: Long): Float {
        if (sinceReveal < 0) return 0f
        val rise = kind.revealMillis * WHITE_RISE
        if (sinceReveal < rise) return sinceReveal / rise
        return 1f - smooth(((sinceReveal - rise) / kind.fadeMillis).coerceIn(0f, 1f))
    }

    /** Whether the reveal has finished and nothing is left on screen. */
    fun revealed(kind: BattleEntryKind, sinceReveal: Long): Boolean =
        sinceReveal >= kind.revealMillis * WHITE_RISE + kind.fadeMillis

    /** A legendary's darkening of the world before its pattern, 0 to 1. */
    fun dim(kind: BattleEntryKind, elapsed: Long): Float {
        if (kind != BattleEntryKind.LEGENDARY) return 0f
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

    /** A legendary's black bars, 0 (gone) to 1 (in): they slam in on the last flash and slide out with the white. */
    fun bars(kind: BattleEntryKind, elapsed: Long, sinceReveal: Long?): Float {
        if (kind != BattleEntryKind.LEGENDARY) return 0f
        val slam = ((elapsed - kind.flashStarts.last()) / 120f).coerceIn(0f, 1f)
        val eased = 1f - (1f - slam) * (1f - slam) * (1f - slam)
        val out = sinceReveal?.let { smooth((it / (kind.revealMillis + kind.fadeMillis * .5f)).coerceIn(0f, 1f)) } ?: 0f
        return eased * (1f - out)
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

    /** A piece's size while covering, then clearing. */
    fun pieceSize(cover: Float, reveal: Float, distance: Float): Float =
        smooth(piece(cover, distance)) * (1f - smooth(piece(reveal, distance)))

    fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}
