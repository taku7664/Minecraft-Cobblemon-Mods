package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID
import kotlin.math.floor

/** Decoy HP is independent of body HP. Unobserved HP remains a bounded public hypothesis. */
internal object LocalSubstituteRules {
    fun present(pokemon: BattlePokemonStateView) = "substitute" in pokemon.canonicalKnownVolatileEffectIds
    fun creationHp(pokemon: BattlePokemonStateView): Double {
        val max = pokemon.combatStats?.maxHp
        return if (max != null && max.minimum == max.maximum) floor(max.maximum / 4.0) / max.maximum else 0.25
    }
    fun hp(pokemon: BattlePokemonStateView): BattleDamageFractionRange = pokemon.knownSubstituteHpFractionRange
        ?: BattleDamageFractionRange(1.0 / (pokemon.combatStats?.maxHp?.maximum ?: 10_000), creationHp(pokemon))

    fun bypasses(action: BattleActionCandidate, actor: BattlePokemonStateView?, state: BattleStateView): Boolean =
        LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "infiltrator" ||
            action.moveDetails?.effects?.mechanicFlags.orEmpty().any { PublicIds.canonical(it) in setOf("sound", "bypasssub") }

    /** Uniform integer HP is an explicit prior for an unobserved decoy, not an observed fact. */
    fun hypotheses(target: BattlePokemonStateView, totalDamage: Double, hits: Int): List<Pair<BattleDamageFractionRange, Double>> {
        val range = hp(target)
        if (range.minimum == range.maximum) return listOf(range to 1.0)
        val unit = 1.0 / (target.combatStats?.maxHp?.maximum ?: 10_000)
        val first = kotlin.math.ceil(range.minimum / unit - 1e-7).toInt().coerceAtLeast(1)
        val last = floor(range.maximum / unit + 1e-7).toInt().coerceAtLeast(first)
        val cuts = (1..hits).map { floor((totalDamage / hits * it) / unit + 1e-7).toInt() }
            .filter { it in first until last }.distinct().sorted() + last
        var lower = first
        return cuts.map { upper ->
            (BattleDamageFractionRange(lower * unit, upper * unit) to (upper - lower + 1.0) / (last - first + 1.0)).also { lower = upper + 1 }
        }
    }
    fun seed(state: BattleStateView, targetId: UUID?, hp: BattleDamageFractionRange?): BattleStateView =
        if (hp == null || targetId == null) state else state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == targetId) it.copyState(knownSubstituteHpFractionRange = hp) else it
        })
}
