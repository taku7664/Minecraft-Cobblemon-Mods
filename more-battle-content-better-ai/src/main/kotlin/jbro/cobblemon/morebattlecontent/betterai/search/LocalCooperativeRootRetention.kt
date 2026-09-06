package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import java.util.UUID

/** At most one best initial joint per supported cooperation pattern, never a score bonus. */
internal object LocalCooperativeRootRetention {
    fun select(ranked: List<LocalBattleActionRank>, context: BattleDecisionContext): Set<String> {
        val retained = linkedSetOf<String>()
        ranked.filter { isRedirectSetup(it.outcome.candidate) }.maxByOrNull { it.comparisonValue }
            ?.let { retained += it.outcome.candidate.actionId }
        if (context.state.format != BattleFormat.DOUBLE) return retained
        val nullified = mutableMapOf<Triple<String, Int?, UUID>, Boolean>()
        fun nullifiedAgainst(action: BattleActionCandidate, target: BattlePokemonStateView): Boolean =
            nullified.getOrPut(Triple(action.actionId, action.actorSlot, target.battlePokemonId)) {
                // Do not copy facts calculated against an opponent onto the allied target.
                val targeted = BattleActionCandidate(action.actionId, BattleActionKind.USE_MOVE,
                    actorSlot = action.actorSlot, moveSlot = action.moveSlot, moveId = action.moveId,
                    targets = listOf(BattleTargetSlot(target.side, requireNotNull(target.activeSlot))),
                    moveDetails = action.moveDetails)
                LocalPublicMechanicsKernel.projectMove(targeted, context).publiclyNullified
            }
        val actives = context.state.pokemon.filter { it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        ranked.filter { rank ->
            val parts = rank.outcome.candidate.componentActions
            parts.size == 2 && parts.all { it.kind == BattleActionKind.USE_MOVE && it.mechanic == null } &&
                parts.map { it.actorSlot }.distinct().size == 2 && parts.any { spread ->
                val details = spread.moveDetails
                val pattern = details?.targetPattern
                if (details == null || details.damageCategory == BattleMoveDamageCategory.STATUS ||
                    details.power <= 0.0 || pattern !in setOf(BattleMoveTargetPattern.ALL_ADJACENT, BattleMoveTargetPattern.ALL_ACTIVE)) {
                    return@any false
                }
                val allies = actives.filter { it.side == BattleSide.ALLY &&
                    (pattern == BattleMoveTargetPattern.ALL_ACTIVE || it.activeSlot != spread.actorSlot) }
                val foes = actives.filter { it.side == BattleSide.OPPONENT }
                val facts = spread.facts
                val damagesOpponent = facts?.standardDamageFractionRange?.minimum?.let { it > 0.0 } == true ||
                    facts?.spreadTargets?.any { it.side == BattleSide.OPPONENT &&
                        it.standardDamageFractionRange?.minimum?.let { damage -> damage > 0.0 } == true } == true
                allies.isNotEmpty() && allies.all { nullifiedAgainst(spread, it) } &&
                    foes.any { !nullifiedAgainst(spread, it) } && damagesOpponent
            }
        }.maxByOrNull { it.comparisonValue }?.let { retained += it.outcome.candidate.actionId }
        return retained
    }

    private fun isRedirectSetup(candidate: BattleActionCandidate): Boolean {
        val parts = candidate.componentActions
        return parts.size == 2 && parts.any { redirect ->
            redirect.kind == BattleActionKind.USE_MOVE &&
                redirect.moveId?.substringAfter(':')?.lowercase()?.filter(Char::isLetterOrDigit) in
                setOf("followme", "ragepowder") && parts.any { setup ->
                setup.actorSlot != null && setup.actorSlot != redirect.actorSlot &&
                    setup.kind == BattleActionKind.USE_MOVE &&
                    setup.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
                    setup.moveDetails?.effects?.effects?.any { effect ->
                        effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                            effect.probability?.let { it > 0.0 } == true && effect.statStages.values.any { it > 0 }
                    } == true
            }
        }
    }
}
