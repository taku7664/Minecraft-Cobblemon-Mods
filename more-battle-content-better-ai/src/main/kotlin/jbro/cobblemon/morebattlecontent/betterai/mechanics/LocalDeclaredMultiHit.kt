package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import kotlin.math.pow

/** Selects one mechanically possible representative hit count for bounded recursive search. */
internal object LocalDeclaredMultiHit {
    fun maximumCount(candidate: BattleActionCandidate): Int = candidate.moveDetails?.effects?.effects.orEmpty()
        .firstOrNull { it.kind == BattleMoveEffectKind.MULTI_HIT }
        ?.amountRange
        ?.maximum
        ?: 1

    fun usesPerHitAccuracy(candidate: BattleActionCandidate): Boolean =
        candidate.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.MULTI_ACCURACY } &&
            maximumCount(candidate) > 1

    fun expectedCount(candidate: BattleActionCandidate, accuracy: Double): Double {
        if (!usesPerHitAccuracy(candidate)) return representativeCount(candidate, null).toDouble()
        val maximum = maximumCount(candidate)
        return (1..maximum).sumOf { hitIndex -> accuracy.coerceIn(0.0, 1.0).pow(hitIndex) }
    }

    fun representativeCount(candidate: BattleActionCandidate, actor: BattlePokemonStateView?): Int {
        val range = candidate.moveDetails?.effects?.effects.orEmpty()
            .firstOrNull { it.kind == BattleMoveEffectKind.MULTI_HIT }
            ?.amountRange
            ?: return 1
        val ability = canonical(actor?.knownAbilityId)
        val item = canonical(actor?.knownHeldItemId)
        return when {
            ability == "skilllink" -> range.maximum
            item == "loadeddice" && range.maximum >= 4 -> maxOf(range.minimum, range.maximum - 1)
            else -> ((range.minimum + range.maximum) / 2).coerceIn(range.minimum, range.maximum)
        }
    }

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

}
