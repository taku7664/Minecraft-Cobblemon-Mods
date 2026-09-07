package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.Collections

/** Public species/form rules only. Null means unavailable, not that no move is possible. */
internal fun interface PublicSpeciesMoveKnowledge {
    fun possibleMoves(speciesId: String, formId: String?): PublicSpeciesMovePool?
}

/**
 * A partial candidate pool, not a known moveset, usage distribution, or proof of current legality.
 * Absence cannot exclude a move: special, legacy, transformed and addon rules may be outside the source.
 */
internal class PublicSpeciesMovePool(moveIds: Set<String>, val sourceId: String) {
    val moveIds: Set<String> = Collections.unmodifiableSet(moveIds.toSortedSet())

    init {
        require(sourceId.isNotBlank())
        require(this.moveIds.all { it.isNotBlank() })
    }
}
