package jbro.cobblemon.mcc.internal.battle.rules

import java.util.UUID

/** Which players of an ordinary battle have their gimmicks locked, and what a locked player may not submit. */
internal object GimmickLockRule {
    /**
     * The players among [playersBySide] (each side's player IDs) that [locked] holds. A battle with players on more
     * than one side is between players and locks nobody.
     */
    fun lockedPlayers(playersBySide: List<Set<UUID>>, locked: (UUID) -> Boolean): Set<UUID> =
        if (playersBySide.count { it.isNotEmpty() } > 1) emptySet() else playersBySide.flatten().filterTo(LinkedHashSet(), locked)

    /** A locked player may submit no gimmick at all, recognised or not. */
    fun rejects(submission: ManagedActionSubmission): Boolean = submission.mechanics.isNotEmpty()

    const val REJECTION = "Battle gimmicks are locked until the League Champion title is earned"
}
