package jbro.cobblemon.mcc.internal.tower

/**
 * The Tower's two challenges. Normal stays at level 50 with Tower Aces and Champions as its bosses and ends with a
 * clear at the 20th win; Endless climbs a level every 5 wins, brings a Champion at every boss and goes on. Endless
 * opens once the challenger has cleared Normal.
 */
internal enum class TowerMode(val id: String) {
    NORMAL("normal"),
    ENDLESS("endless"),
}

/** The win that clears Normal: the run ends there and the next one starts again from the first battle. */
internal const val TOWER_NORMAL_CLEAR_WINS = 20

/** A battle format in a mode: what a run's progress and record belong to. */
internal data class TowerTrack(val format: TowerBattleFormat, val mode: TowerMode) {
    /** Endless keeps the record IDs the Tower had before Normal existed, whose streaks had no end either. */
    val recordId: String
        get() = if (mode == TowerMode.ENDLESS) format.recordId else "${format.recordId}_${mode.id}"

    companion object {
        val entries: List<TowerTrack> =
            TowerMode.entries.flatMap { mode -> TowerBattleFormat.entries.map { format -> TowerTrack(format, mode) } }

        fun forRecordId(recordId: String): TowerTrack? = entries.singleOrNull { it.recordId == recordId }

        /**
         * Endless opens after a Normal clear in either format; a challenger who already reached the 20th win in the
         * Tower before Normal existed keeps it open too.
         */
        fun endlessUnlocked(progress: Map<TowerTrack, TowerProgress>): Boolean =
            progress.values.any { it.bestWinStreak >= TOWER_NORMAL_CLEAR_WINS }
    }
}
