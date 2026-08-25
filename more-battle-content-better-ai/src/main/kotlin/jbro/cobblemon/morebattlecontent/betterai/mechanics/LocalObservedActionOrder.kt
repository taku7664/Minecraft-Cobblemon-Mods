package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** Interprets only public, same-priority action-order observations. */
internal object LocalObservedActionOrder {
    fun before(
        state: BattleStateView,
        firstPokemonId: UUID,
        secondPokemonId: UUID,
    ): Boolean? {
        val relations = state.inferences.asSequence()
            .filter { inference ->
                normalize(inference.categoryId) == OBSERVED_ACTION_ORDER &&
                    inference.confidence == BattleInferenceConfidence.CONFIRMED &&
                    BattleInferenceBasis.ACTION_ORDER in inference.basis &&
                    setOf(inference.subjectPokemonId, inference.relatedPokemonId) ==
                    setOf(firstPokemonId, secondPokemonId)
            }
            .mapNotNull { inference ->
                val subjectBeforeRelated = when (normalize(inference.candidateId)) {
                    BEFORE_AT_SAME_BASE_PRIORITY -> true
                    AFTER_AT_SAME_BASE_PRIORITY -> false
                    else -> return@mapNotNull null
                }
                if (inference.subjectPokemonId == firstPokemonId) {
                    subjectBeforeRelated
                } else {
                    !subjectBeforeRelated
                }
            }
            .distinct()
            .toList()
        return relations.singleOrNull()
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .filter(Char::isLetterOrDigit)
        .lowercase()

    private const val OBSERVED_ACTION_ORDER = "observedactionorder"
    private const val BEFORE_AT_SAME_BASE_PRIORITY = "beforeatsamebasepriority"
    private const val AFTER_AT_SAME_BASE_PRIORITY = "afteratsamebasepriority"
}
