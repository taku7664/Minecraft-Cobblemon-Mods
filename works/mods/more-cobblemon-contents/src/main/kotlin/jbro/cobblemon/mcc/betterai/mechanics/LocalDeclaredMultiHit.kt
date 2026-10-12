package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
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

    fun expectedCount(candidate: BattleActionCandidate, accuracy: Double, state: BattleStateView? = null): Double {
        if (!usesPerHitAccuracy(candidate)) return representativeCount(candidate, null, state).toDouble()
        val maximum = maximumCount(candidate)
        return (1..maximum).sumOf { hitIndex -> accuracy.coerceIn(0.0, 1.0).pow(hitIndex) }
    }

    fun representativeCount(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        state: BattleStateView? = null,
    ): Int {
        if ("better_ai:single_hit" in candidate.tags) return 1
        val range = candidate.moveDetails?.effects?.effects.orEmpty()
            .firstOrNull { it.kind == BattleMoveEffectKind.MULTI_HIT }
            ?.amountRange
            ?: return 1
        val ability = if (state == null) canonical(actor?.knownAbilityId) else
            LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        val item = if (state == null) canonical(actor?.knownHeldItemId) else LocalPublicItemState.activeItemId(state, actor)
        return when {
            ability == "skilllink" -> range.maximum
            item == "loadeddice" && range.maximum >= 4 ->
                maxOf(range.minimum, range.maximum - 1)
            else -> ((range.minimum + range.maximum) / 2).coerceIn(range.minimum, range.maximum)
        }
    }

    /**
     * How likely each hit count is, as Showdown draws it (the engine's BattleActions): a 2-5 move lands 2 or 3 hits 35%
     * each and 4 or 5 hits 15% each, 4 or 5 alike with Loaded Dice; Skill Link lands every hit; any other range is
     * even. A move with per-hit accuracy stops at its first miss, which this does not read: it stays one hit, as do
     * single-hit moves.
     */
    fun countDistribution(
        candidate: BattleActionCandidate,
        actor: BattlePokemonStateView?,
        state: BattleStateView? = null,
    ): Map<Int, Double> {
        if ("better_ai:single_hit" in candidate.tags || usesPerHitAccuracy(candidate)) return SINGLE
        val range = candidate.moveDetails?.effects?.effects.orEmpty()
            .firstOrNull { it.kind == BattleMoveEffectKind.MULTI_HIT }
            ?.amountRange
            ?.takeIf { it.maximum > 1 }
            ?: return SINGLE
        val ability = if (state == null) canonical(actor?.knownAbilityId) else
            LocalPublicAbilityState.effectiveKnownAbility(state, actor)
        val item = if (state == null) canonical(actor?.knownHeldItemId) else LocalPublicItemState.activeItemId(state, actor)
        return when {
            ability == "skilllink" || range.minimum == range.maximum -> mapOf(range.maximum to 1.0)
            range.minimum == 2 && range.maximum == 5 -> if (item == "loadeddice") mapOf(4 to 0.5, 5 to 0.5) else TWO_TO_FIVE
            else -> (range.minimum..range.maximum).associateWith { 1.0 / (range.maximum - range.minimum + 1) }
        }
    }

    private val SINGLE = mapOf(1 to 1.0)
    private val TWO_TO_FIVE = mapOf(2 to 0.35, 3 to 0.35, 4 to 0.15, 5 to 0.15)

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

}
