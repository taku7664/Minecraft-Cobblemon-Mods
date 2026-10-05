package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID

/** Public preview slots are hypothetical opponents; no hidden move, ability or build is filled in. */
internal object LocalDoubleLeadPairEvaluation {
    fun scores(
        context: BattleLeadChoiceContext,
        candidates: List<BattlePokemonStateView>,
        foes: List<BattleOpponentTeamPreviewPokemonView>,
        detailed: Boolean,
    ): Map<List<UUID>, Double> {
        val opposingPairs = if (context.opponentTeamPreview.selectionSize == 1) foes.map { listOf(it) } else
            foes.flatMapIndexed { first, foe ->
                foes.drop(first + 1).map { partner -> listOf(foe, partner) }
            }.ifEmpty { listOf(foes.take(1)) }
        return candidates.flatMap { first -> candidates.filter { it.battlePokemonId != first.battlePokemonId }.map { second ->
            val pair = listOf(first, second)
            pair.map { it.battlePokemonId } to opposingPairs.map { opposing -> evaluate(pair, opposing, context, detailed) }.average()
        } }.toMap()
    }

    private fun evaluate(
        pair: List<BattlePokemonStateView>,
        opposing: List<BattleOpponentTeamPreviewPokemonView>,
        context: BattleLeadChoiceContext,
        detailed: Boolean,
    ): Double {
        val ownIds = context.ownTeam.mapTo(HashSet()) { it.battlePokemonId }
        val projectedFoes = opposing.mapIndexed { slot, preview ->
            var id = UUID(0, Long.MIN_VALUE + preview.previewSlotId)
            while (id in ownIds) id = UUID(id.mostSignificantBits + 1, id.leastSignificantBits)
            BattlePokemonStateView(id, BattleSide.OPPONENT, slot, preview.speciesId, preview.formId, preview.level,
                1.0, null, emptyMap(), emptySet(), null, null, false, preview.knownTypeIds, preview.combatStats,
                knownVolatileEffectIds = emptySet())
        }
        val own = context.ownTeam.map { pokemon ->
            val build = context.exactOwnTeam.buildFor(pokemon.battlePokemonId)
            pokemon.copyState(activeSlot = pair.indexOfFirst { it.battlePokemonId == pokemon.battlePokemonId }.takeIf { it >= 0 },
                knownAbilityId = build?.abilityId ?: pokemon.knownAbilityId,
                knownHeldItemId = if (build != null) build.heldItemId ?: "" else pokemon.knownHeldItemId)
        }
        val opening = BattleStateView(UUID(0, 0), BattleFormat.DOUBLE, 0, own + projectedFoes,
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to own.count { !it.fainted && it.hpFraction > 0.0 },
                BattleSide.OPPONENT to context.opponentTeamPreview.selectionSize), emptyList(), emptyList())
        val active = pair.map { pokemon -> own.single { it.battlePokemonId == pokemon.battlePokemonId } }
        val speed = active.map { LocalPublicTurnOrder.effectiveSpeed(opening, it) }
        val firstBeforeSecond = if (speed.any { it == null }) 0.5 else
            LocalPublicTurnOrder.uniformGreaterProbability(requireNotNull(speed[0]), requireNotNull(speed[1]))
        val orders = listOf(active to firstBeforeSecond, active.reversed() to 1.0 - firstBeforeSecond)
        return orders.filter { it.second > 0.0 }.sumOf { (order, probability) ->
            val entered = LocalDoubleLeadOpeningMechanics.afterOwnEntries(
                order.fold(opening) { state, incoming -> LocalEntryAbilityProjector.project(state, incoming.battlePokemonId) })
            val publicDecision = BattleDecisionContext(UUID(0, 0), entered,
                listOf(BattleActionCandidate("lead:opening", BattleActionKind.WAIT)), Long.MAX_VALUE,
                BattleTacticalMemoryView.empty(), context.ownMoves, context.opponentTeamPreview)
            // A lead view permits partial builds; the turn-decision exact view requires the whole own team.
            val decision = if (context.exactOwnTeam.builds.mapTo(HashSet()) { it.battlePokemonId } == ownIds)
                publicDecision.copy(exactOwnTeam = context.exactOwnTeam) else publicDecision
            fun score(current: BattleDecisionContext): Double {
                val acting = pair.filter { pokemon -> "commanding" !in current.state.pokemon.single {
                    it.battlePokemonId == pokemon.battlePokemonId
                }.canonicalKnownVolatileEffectIds }
                val matchups = acting.map { pokemon -> opposing.mapIndexed { slot, preview ->
                    LocalLeadChoice.matchup(pokemon, preview, context, detailed,
                        LocalLeadOpeningMatchup(current, current.state.pokemon.single {
                            it.battlePokemonId == projectedFoes[slot].battlePokemonId
                        }))
                } }
                val value = if (acting.size == 1) matchups.single().average() else
                    if (opposing.size == 1) (matchups[0][0] + matchups[1][0]) / 2.0 else
                    maxOf(matchups[0][0] + matchups[1][1], matchups[0][1] + matchups[1][0]) / 2.0
                // One cancelled actor means half as many attacks; retain the existing log-doubling unit.
                return value + kotlin.math.ln(acting.size.toDouble() / pair.size) / kotlin.math.ln(2.0)
            }
            val baseline = score(decision)
            probability * if (detailed) LocalDoubleLeadOpeningPotential.best(decision, baseline, ::score) else baseline
        }
    }
}

internal data class LocalLeadOpeningMatchup(val context: BattleDecisionContext, val foe: BattlePokemonStateView)
