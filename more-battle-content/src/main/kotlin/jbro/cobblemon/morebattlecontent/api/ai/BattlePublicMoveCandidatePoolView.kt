package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections
import java.util.UUID

/**
 * Partial public species/form learnset candidates, not observed moves or legal action templates.
 * No probability is implied; absence cannot rule out special, legacy, transformed or addon moves.
 * Species/form identify the source rules, not necessarily a Pokemon's future projected form.
 * An empty pool means the source was available but yielded no candidates; a missing pool makes no claim.
 */
class BattlePublicMoveCandidatePoolView(
    val battlePokemonId: UUID,
    val speciesId: String,
    val formId: String?,
    moveIds: Set<String>,
    val sourceId: String,
) {
    val moveIds: Set<String> = Collections.unmodifiableSet(moveIds.toSortedSet())

    init {
        require(speciesId.isNotBlank())
        require(formId == null || formId.isNotBlank())
        require(sourceId.isNotBlank())
        require(this.moveIds.all { it.isNotBlank() })
    }
}
