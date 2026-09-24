package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections

/**
 * Public species/form learnset candidates for one opaque team-preview slot.
 *
 * This is deliberately not keyed by a live battle Pokemon UUID. Candidate presence means only
 * that the public rules source admits the move; it is neither ownership evidence nor a complete
 * moveset. Missing details mean the compatibility layer could not create a projection template.
 */
class BattleOpponentPreviewMovePoolView @JvmOverloads constructor(
    val speciesId: String,
    val formId: String?,
    moveIds: Set<String>,
    val sourceId: String,
    moveDetails: Map<String, BattleMoveCandidateView> = emptyMap(),
) {
    val moveIds: Set<String> = Collections.unmodifiableSet(moveIds.toSortedSet())
    val moveDetails: Map<String, BattleMoveCandidateView> =
        Collections.unmodifiableMap(moveDetails.toSortedMap())

    init {
        require(speciesId.isNotBlank())
        require(formId == null || formId.isNotBlank())
        require(sourceId.isNotBlank())
        require(this.moveIds.all(String::isNotBlank))
        require(this.moveDetails.keys.all(this.moveIds::contains))
    }
}
