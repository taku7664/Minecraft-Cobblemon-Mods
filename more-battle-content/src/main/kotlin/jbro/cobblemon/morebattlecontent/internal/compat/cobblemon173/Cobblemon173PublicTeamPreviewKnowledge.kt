package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewMovePoolView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonFormStateView
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesMoveKnowledge

internal data class Cobblemon173PublicPreviewFacts(
    val knownTypeIds: Set<String>,
    val combatStats: BattleCombatStatRangesView?,
    val knownFormStates: Map<String, BattlePokemonFormStateView>,
    val showdownSpeciesId: String? = null,
    val buildCandidatePool: jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentPreviewBuildPoolView? = null,
)

/** Enriches opaque preview slots without accepting any live or registered Pokemon object. */
internal object Cobblemon173PublicTeamPreviewKnowledge {
    fun enrich(
        preview: BattleOpponentTeamPreviewView,
        facts: (speciesId: String, formId: String?, level: Int?) -> Cobblemon173PublicPreviewFacts? =
            Cobblemon173PublicSpeciesInferenceKnowledge::publicPreviewFacts,
        moveKnowledge: PublicSpeciesMoveKnowledge = Cobblemon173PublicSpeciesInferenceKnowledge,
        moveDetails: (String) -> BattleMoveCandidateView? =
            Cobblemon173ActionCandidateAdapter::publicMoveDetails,
    ): BattleOpponentTeamPreviewView = BattleOpponentTeamPreviewView(
        selectionSize = preview.selectionSize,
        pokemon = preview.pokemon.map { pokemon ->
            val publicFacts = facts(pokemon.speciesId, pokemon.formId, pokemon.level)
            val movePool = pokemon.moveCandidatePool ?: moveKnowledge
                .possibleMoves(pokemon.speciesId, pokemon.formId)
                ?.let { source ->
                    BattleOpponentPreviewMovePoolView(
                        speciesId = pokemon.speciesId,
                        formId = pokemon.formId,
                        moveIds = source.moveIds,
                        sourceId = source.sourceId,
                        moveDetails = source.moveIds.mapNotNull { moveId ->
                            moveDetails(moveId)?.let { moveId to it }
                        }.toMap(),
                    )
                }
            val knownTypes = publicFacts?.knownTypeIds ?: pokemon.knownTypeIds
            val combatStats = publicFacts?.combatStats ?: pokemon.combatStats
            val formStates = publicFacts?.knownFormStates ?: pokemon.knownFormStates
            val buildPool = pokemon.buildCandidatePool ?: publicFacts?.buildCandidatePool
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = pokemon.previewSlotId,
                speciesId = pokemon.speciesId,
                formId = pokemon.formId,
                level = pokemon.level,
                knownTypeIds = knownTypes,
                combatStats = combatStats,
                knownFormStates = formStates,
                moveCandidatePool = movePool,
                buildCandidatePool = buildPool,
                showdownSpeciesId = publicFacts?.showdownSpeciesId ?: pokemon.showdownSpeciesId,
            )
        },
    )
}
