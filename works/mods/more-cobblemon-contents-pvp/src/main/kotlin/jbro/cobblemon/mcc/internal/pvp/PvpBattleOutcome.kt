package jbro.cobblemon.mcc.internal.pvp

import java.util.UUID

/** Who won a finished PvP battle, as winner and loser. */
internal data class PvpBattleOutcome(val winnerId: UUID, val loserId: UUID)

/**
 * The result of a PvP battle between [firstId] and [secondId], or null when it ends without one.
 *
 * Cobblemon names the winner when the battle is played out. A player who dies or logs out instead ends it as a
 * forced tie with nobody winning; [forfeits] (from `ManagedBattleForfeits`) names who left, and when exactly one of
 * the two did, the other wins. Both or neither leaving stays a no-contest.
 */
internal fun resolvePvpBattleOutcome(
    firstId: UUID,
    secondId: UUID,
    firstWon: Boolean,
    secondWon: Boolean,
    forfeits: Set<UUID>,
): PvpBattleOutcome? = when {
    firstWon && !secondWon -> PvpBattleOutcome(firstId, secondId)
    secondWon && !firstWon -> PvpBattleOutcome(secondId, firstId)
    firstWon || secondWon -> null
    firstId in forfeits && secondId !in forfeits -> PvpBattleOutcome(secondId, firstId)
    secondId in forfeits && firstId !in forfeits -> PvpBattleOutcome(firstId, secondId)
    else -> null
}
