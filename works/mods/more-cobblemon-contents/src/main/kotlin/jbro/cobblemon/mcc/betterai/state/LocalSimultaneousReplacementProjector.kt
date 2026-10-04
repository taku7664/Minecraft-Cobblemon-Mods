package jbro.cobblemon.mcc.betterai.state

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder

/** Both trainers finish choosing replacements before any entering ability observes the new board. */
internal object LocalSimultaneousReplacementProjector {
    fun project(
        state: BattleStateView,
        ally: List<BattleActionCandidate>,
        opponent: List<BattleActionCandidate>,
        source: BattleDecisionContext,
    ): List<LocalTraitStateBranch> {
        val moves = ally.map { BattleSide.ALLY to it } + opponent.map { BattleSide.OPPONENT to it }
        val arrived = moves.fold(state) { current, (side, raw) ->
            val calculated = PublicBattleTacticalCalculator.calculate(source.copy(state = current,
                candidates = listOf(raw)), side).candidates.single()
            LocalSwitchStateProjector.project(current, side, calculated, entryAbility = false)
        }
        val entering = moves.mapNotNull { it.second.switchPokemonId }.filter { id ->
            arrived.pokemon.any { it.battlePokemonId == id && !it.fainted && it.hpFraction > 0.0 }
        }
        val orders = permutations(entering).mapNotNull { order ->
            var probability = 1.0
            for (first in order.indices) for (second in first + 1 until order.size) {
                val a = arrived.pokemon.single { it.battlePokemonId == order[first] }
                val b = arrived.pokemon.single { it.battlePokemonId == order[second] }
                // Switch-in callbacks run fastest first even under Trick Room.
                val speedA = LocalPublicTurnOrder.effectiveSpeed(arrived, a)
                val speedB = LocalPublicTurnOrder.effectiveSpeed(arrived, b)
                probability *= if (speedA == null || speedB == null) 0.5 else
                    LocalPublicTurnOrder.uniformGreaterProbability(speedA, speedB)
            }
            if (probability <= 0.0) null else order to probability
        }
        val mass = orders.sumOf { it.second }
        return orders.flatMap { (order, weight) ->
            order.fold(listOf(LocalTraitStateBranch(arrived, weight / mass))) { branches, incomingId ->
                branches.flatMap { previous -> LocalEntryAbilityProjector.projectOutcomes(previous.state, incomingId)
                    .map { next -> LocalTraitStateBranch(next.state, previous.probability * next.probability) } }
            }
        }
    }

    private fun permutations(ids: List<UUID>): List<List<UUID>> = if (ids.isEmpty()) listOf(emptyList()) else
        ids.flatMap { id -> permutations(ids.filterNot { it == id }).map { listOf(id) + it } }
}
