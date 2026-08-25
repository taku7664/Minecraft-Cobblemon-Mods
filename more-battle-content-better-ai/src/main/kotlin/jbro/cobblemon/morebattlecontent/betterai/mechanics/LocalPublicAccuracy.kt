package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Resolves only public accuracy/evasion stages; hidden item, ability, and field modifiers stay unknown. */
internal object LocalPublicAccuracy {
    fun probability(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actingSide: BattleSide,
    ): Double {
        val base = candidate.facts?.baseAccuracyProbability
            ?: candidate.moveDetails?.accuracy?.div(100.0)
            ?: 0.0
        val actor = context.state.pokemon.singleOrNull {
            it.side == actingSide && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return base.coerceIn(0.0, 1.0)
        val targetSide = if (actingSide == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
        val explicit = candidate.targets.singleOrNull()
        val target = if (explicit != null) {
            context.state.pokemon.singleOrNull {
                it.side == explicit.side && it.activeSlot == explicit.slot && !it.fainted && it.hpFraction > 0.0
            }
        } else {
            context.state.pokemon.singleOrNull {
                it.side == targetSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
        }
        val accuracyStage = actor.stage("accuracy", "acc")
        val ignoresEvasion = candidate.moveDetails?.effects?.effects.orEmpty().any {
            it.kind == BattleMoveEffectKind.IGNORE_EVASION_STAGES
        }
        val evasionStage = if (ignoresEvasion) 0 else target?.stage("evasion", "eva") ?: 0
        val combined = (accuracyStage - evasionStage).coerceIn(-6, 6)
        val multiplier = if (combined >= 0) (3.0 + combined) / 3.0 else 3.0 / (3.0 - combined)
        return (base * multiplier).coerceIn(0.0, 1.0)
    }

    private fun BattlePokemonStateView.stage(vararg aliases: String): Int = statStages.entries.firstOrNull {
        canonical(it.key) in aliases
    }?.value?.coerceIn(-6, 6) ?: 0

    private fun canonical(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
}
