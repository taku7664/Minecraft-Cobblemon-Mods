package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy

/** A modest root preference for a usable, pure self-setup action. */
internal object LocalSetupMovePreference {
    fun bonus(candidate: BattleActionCandidate, context: BattleDecisionContext): Double {
        if (candidate.kind == BattleActionKind.COMPOSITE) {
            return candidate.componentActions.sumOf { bonus(it, context) }
        }
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory != BattleMoveDamageCategory.STATUS || details.power > 0.0) return 0.0
        val effects = details.effects?.effects.orEmpty()
        if (effects.isEmpty() || effects.any {
                it.kind != BattleMoveEffectKind.STAT_STAGE || it.target != BattleMoveEffectTarget.USER
            }
        ) return 0.0
        val actor = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted
        } ?: return 0.0
        if (LocalIdleUtilityMoveRules.isIdle(candidate, context)) return 0.0
        val stages = actor.statStages.mapKeys { canonical(it.key) }
        val canRaise = effects.any { effect ->
            effect.statStages.any { (stat, change) -> change > 0 && (stages[canonical(stat)] ?: 0) < 6 }
        }
        return if (canRaise) BONUS * LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY) else 0.0
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val BONUS = 10.0
}
