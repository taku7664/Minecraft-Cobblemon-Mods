package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/**
 * Publishes type-chart mechanics for a legal switch without assigning utility, rank, or advice.
 * Every input comes from the same public battle view available to both Better AI brains.
 */
internal object PublicSwitchTypeFactsCalculator {
    fun forCandidate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): PublicSwitchTargetFacts? {
        if (candidate.kind != BattleActionKind.SWITCH) return null
        val targetId = candidate.switchPokemonId ?: return null
        val target = context.state.pokemon.firstOrNull {
            it.battlePokemonId == targetId && it.side == BattleSide.ALLY && !it.fainted
        } ?: return null
        return PublicSwitchTargetFacts(
            hpFraction = target.hpFraction,
            knownTypeIds = target.knownTypeIds.sorted(),
            activeOpponentTypeChartMultipliers = activeOpponentTypeChartMultipliers(
                defenderTypeIds = target.knownTypeIds,
                context = context,
            ),
        )
    }

    fun activeOpponentTypeChartMultipliers(
        defenderTypeIds: Set<String>,
        context: BattleDecisionContext,
    ): List<PublicActiveOpponentTypeChartMultiplier> {
        if (defenderTypeIds.isEmpty()) return emptyList()
        return context.state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
            .sortedBy { it.activeSlot }
            .flatMap { opponent ->
                opponent.knownTypeIds.asSequence().sorted().map { attackingTypeId ->
                    PublicActiveOpponentTypeChartMultiplier(
                        opponentActiveSlot = requireNotNull(opponent.activeSlot),
                        attackingTypeId = attackingTypeId,
                        multiplier = StandardTypeEffectiveness.multiplier(attackingTypeId, defenderTypeIds),
                    )
                }
            }
            .toList()
    }
}

internal data class PublicSwitchTargetFacts(
    val hpFraction: Double,
    val knownTypeIds: List<String>,
    val activeOpponentTypeChartMultipliers: List<PublicActiveOpponentTypeChartMultiplier>,
)

internal data class PublicActiveOpponentTypeChartMultiplier(
    val opponentActiveSlot: Int,
    val attackingTypeId: String,
    val multiplier: Double,
)
