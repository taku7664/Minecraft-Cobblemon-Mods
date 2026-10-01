package jbro.cobblemon.ui.extended.transition

/**
 * What kind of battle is starting, which sets how the entry transition flashes and how long it takes to cover the
 * screen. A legendary flashes twice before its pattern weaves over the screen, as the games mark those encounters.
 */
enum class BattleEntryKind(val id: String, val flashes: Int, val coverMillis: Long, val revealMillis: Long) {
    LEGENDARY("legendary", 2, 620, 480),
    WILD("wild", 1, 420, 380),
    TRAINER("trainer", 1, 480, 400);

    companion object {
        fun fromId(id: String?): BattleEntryKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The transition's timing, apart from drawing: white flashes, then the pattern covering the screen from its center
 * outward, a hold while the battle starts, then the pattern clearing from the center outward to show the battle.
 */
object BattleEntryTimeline {
    const val FLASH_MILLIS = 140L
    const val FLASH_GAP_MILLIS = 80L
    /** How far behind the center a pattern piece at the screen's corner starts, as a share of the cover. */
    const val SPREAD = 1.1f
    /** A hold with no battle in sight ends on its own, so a refused battle never leaves the screen covered. */
    const val HOLD_TIMEOUT_MILLIS = 6000L

    fun flashEnd(kind: BattleEntryKind): Long =
        kind.flashes * FLASH_MILLIS + (kind.flashes - 1).coerceAtLeast(0) * FLASH_GAP_MILLIS

    /** The white flash's strength, 0 to 1, rising and falling within each flash. */
    fun flash(kind: BattleEntryKind, elapsed: Long): Float {
        if (elapsed < 0 || elapsed >= flashEnd(kind)) return 0f
        val period = FLASH_MILLIS + FLASH_GAP_MILLIS
        val within = elapsed % period
        if (within >= FLASH_MILLIS) return 0f
        val half = FLASH_MILLIS / 2f
        return 1f - kotlin.math.abs(within - half) / half
    }

    /** How far the pattern has covered the screen, 0 to 1. */
    fun cover(kind: BattleEntryKind, elapsed: Long): Float =
        ((elapsed - flashEnd(kind)).toFloat() / kind.coverMillis).coerceIn(0f, 1f)

    fun covered(kind: BattleEntryKind, elapsed: Long): Boolean = cover(kind, elapsed) >= 1f

    /** How far the pattern has cleared, 0 to 1, since the reveal began. */
    fun reveal(kind: BattleEntryKind, sinceReveal: Long): Float =
        (sinceReveal.toFloat() / kind.revealMillis).coerceIn(0f, 1f)

    /**
     * One pattern piece's share at an overall [progress], for a piece [distance] (0 at the center, 1 at the far
     * corner) from the screen's center: the center leads, the corners follow.
     */
    fun piece(progress: Float, distance: Float): Float =
        (progress * (1f + SPREAD) - distance.coerceIn(0f, 1f) * SPREAD).coerceIn(0f, 1f)

    /** A piece's size while covering, then clearing: it grows in from the center and shrinks away from it. */
    fun pieceSize(cover: Float, reveal: Float, distance: Float): Float =
        smooth(piece(cover, distance)) * (1f - smooth(piece(reveal, distance)))

    fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}
