package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDamageFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

/**
 * What the incoming Pokemon gets hit with, projected from the opponent's publicly revealed moves.
 *
 * The local ranking has always worked this out - it projects the board after the switch and asks what
 * the opponent can do to it. Router was given the switch target's health, its typing, and a type-chart
 * multiplier per opposing *type*, and then told in the same breath never to invent a precise damage
 * result. So the one brain that is not allowed to do the arithmetic was the one not given the answer,
 * and a switch into a resisted type that still takes seventy percent looked identical to one that
 * takes twenty.
 *
 * Everything here is the same class of fact the candidate facts already are: a Showdown base
 * projection over public stat ranges, from moves the battle has actually revealed. No tuning weight,
 * no ranking, no recommendation - those belong to whichever brain is reading this, which is the whole
 * point of publishing the number rather than a judgement about it.
 */
internal object PublicSwitchIncomingThreatCalculator {
    fun forCandidate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): List<PublicIncomingThreat> {
        if (candidate.kind != BattleActionKind.SWITCH) return emptyList()
        val incomingId = candidate.switchPokemonId ?: return emptyList()
        val switched = LocalSwitchStateProjector.project(context.state, BattleSide.ALLY, candidate)
        // If the projection did not actually put this Pokemon on the field there is nothing to
        // describe, and describing the board as it stands would answer a different question.
        switched.pokemon.firstOrNull {
            it.battlePokemonId == incomingId && it.activeSlot != null && !it.fainted
        } ?: return emptyList()

        return PublicFutureActionFactory.actions(switched, BattleSide.OPPONENT, context.publicActionCatalog)
            .flatMap { action ->
                if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
            }
            .distinctBy(BattleActionCandidate::actionId)
            .filter { action ->
                action.kind == BattleActionKind.USE_MOVE &&
                    action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
                    (action.moveDetails?.power ?: 0.0) > 0.0
            }
            .mapNotNull { action ->
                val actorId = switched.pokemon.firstOrNull {
                    it.side == BattleSide.OPPONENT && it.activeSlot == action.actorSlot && !it.fainted
                }?.battlePokemonId ?: return@mapNotNull null
                val calculated = PublicBattleTacticalCalculator.calculate(
                    BattleDecisionContext(
                        requestId = context.requestId,
                        state = switched,
                        candidates = listOf(action),
                        deadlineEpochMillis = context.deadlineEpochMillis,
                        memory = BattleTacticalMemoryView.empty(),
                        publicActionCatalog = context.publicActionCatalog,
                    ),
                    BattleSide.OPPONENT,
                )
                val facts = calculated.candidates.single().facts ?: return@mapNotNull null
                val moveId = action.moveId ?: return@mapNotNull null
                PublicIncomingThreat(
                    attackerPokemonId = actorId,
                    attackerActiveSlot = action.actorSlot,
                    moveId = moveId,
                    typeId = action.moveDetails?.typeId,
                    typeChartMultiplier = facts.typeChartMultiplier,
                    damageFractionRange = facts.standardDamageFractionRange,
                    knockoutAssessment = facts.standardKnockoutAssessment,
                )
            }
            // Worst first, so a reader that only looks at the head of the list is looking at the
            // thing that decides whether the switch is survivable.
            .sortedByDescending { it.damageFractionRange?.maximum ?: 0.0 }
    }
}

internal data class PublicIncomingThreat(
    val attackerPokemonId: java.util.UUID,
    val attackerActiveSlot: Int?,
    val moveId: String,
    val typeId: String?,
    val typeChartMultiplier: Double?,
    val damageFractionRange: BattleDamageFractionRange?,
    val knockoutAssessment: BattleKnockoutAssessment?,
)
