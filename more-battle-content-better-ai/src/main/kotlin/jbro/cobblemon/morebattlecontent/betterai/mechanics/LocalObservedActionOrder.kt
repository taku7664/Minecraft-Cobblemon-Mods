package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceBasis
import jbro.cobblemon.morebattlecontent.api.ai.BattleInferenceConfidence
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/**
 * Interprets only public, same-priority action-order observations.
 *
 * An observation says who moved first *under the speed conditions that held when it was seen*. It is
 * not a standing fact about the pair. The projector consults it only when the public stat ranges
 * overlap, which is exactly the position a speed-control move is played from, so treating it as
 * permanent made the search believe the order could never change: Icy Wind, Thunder Wave, Rock Tomb,
 * Sticky Web and Tailwind all projected into a future where the opponent still moved first, and a
 * one-stage drop - the drop that is usually not wide enough to separate the ranges on its own - could
 * never pay for itself. The one thing that would have refuted the observation was the move the
 * observation stopped the search from ever choosing.
 *
 * So the relation is scoped to the conditions it was observed in. When the projected state carries a
 * different speed context for either Pokemon than the state the inference was drawn from, the answer
 * is withheld and the projector branches both orders instead. That is a weaker claim, not a
 * fabricated one: the search stops asserting an order it has no evidence for and prices both.
 */
internal object LocalObservedActionOrder {
    fun before(
        observedState: BattleStateView,
        projectedState: BattleStateView,
        firstPokemonId: UUID,
        secondPokemonId: UUID,
    ): Boolean? {
        if (!speedContextUnchanged(observedState, projectedState, firstPokemonId)) return null
        if (!speedContextUnchanged(observedState, projectedState, secondPokemonId)) return null
        val relations = observedState.inferences.asSequence()
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

    /**
     * Everything public that scales a Pokemon's speed, as it stands in one state.
     *
     * Trick Room is deliberately absent: the projector resolves it before it ever asks for an
     * observation, so a room that appears or expires mid-search never reaches this comparison.
     */
    private data class SpeedContext(val stage: Int, val paralysed: Boolean, val tailwind: Boolean)

    private fun speedContextUnchanged(
        observedState: BattleStateView,
        projectedState: BattleStateView,
        pokemonId: UUID,
    ): Boolean {
        val observed = speedContext(observedState, pokemonId) ?: return false
        val projected = speedContext(projectedState, pokemonId) ?: return false
        return observed == projected
    }

    private fun speedContext(state: BattleStateView, pokemonId: UUID): SpeedContext? {
        val pokemon = state.pokemon.firstOrNull { it.battlePokemonId == pokemonId } ?: return null
        return SpeedContext(
            stage = pokemon.statStages.entries.firstOrNull { normalize(it.key) in SPEED_ALIASES }
                ?.value?.coerceIn(-6, 6) ?: 0,
            paralysed = normalize(pokemon.statusId) in PARALYSIS_IDS,
            tailwind = tailwind(state, pokemon.side),
        )
    }

    private fun tailwind(state: BattleStateView, side: BattleSide): Boolean =
        state.field.sideConditions.getValue(side).any { effect ->
            val remainingTurns = effect.remainingTurns
            normalize(effect.effectId) == TAILWIND && (remainingTurns == null || remainingTurns > 0)
        }

    /**
     * Drops a namespace prefix and every separator so ids compare across sources.
     *
     * `substringAfter` returns the whole string when there is no colon, so the inference category and
     * candidate ids - which never carry one - normalize exactly as they did before.
     */
    private fun normalize(value: String?): String = value.orEmpty()
        .substringAfter(':')
        .filter(Char::isLetterOrDigit)
        .lowercase()

    private const val OBSERVED_ACTION_ORDER = "observedactionorder"
    private const val BEFORE_AT_SAME_BASE_PRIORITY = "beforeatsamebasepriority"
    private const val AFTER_AT_SAME_BASE_PRIORITY = "afteratsamebasepriority"
    private const val TAILWIND = "tailwind"
    private val SPEED_ALIASES = setOf("speed", "spe")
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
}
