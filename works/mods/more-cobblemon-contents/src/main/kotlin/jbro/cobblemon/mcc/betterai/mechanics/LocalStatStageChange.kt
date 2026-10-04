package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A stat stage change as Showdown's `boost` resolves it, for the public projection: Contrary and Simple reshape it,
 * Clear Body, White Smoke, Full Metal Body, a Clear Amulet and Mist stop drops from another Pokemon, Mirror Armor
 * sends them back, Defiant and Competitive answer them, and a White Herb undoes what is left. Applied raw, a
 * Contrary Leaf Storm was read as a drop and a Simple Calm Mind as a single stage.
 */
internal object LocalStatStageChange {
    fun apply(
        state: BattleStateView,
        targetId: UUID,
        sourceId: UUID?,
        stages: Map<String, Int>,
        /** Mold Breaker and its kind ignore the target's Contrary, Simple and drop-stopping abilities. */
        ignoreTargetAbility: Boolean = false,
        /** An ability such as Cotton Down applies boosts directly rather than hitting the Substitute. */
        bypassesSubstitute: Boolean = false,
        /** Entry ability batches run item updates after all adjacent boost attempts. */
        updateItems: Boolean = true,
    ): BattleStateView = applyBoost(state, targetId, sourceId, stages, ignoreTargetAbility, updateItems,
        mirrorReflection = false, bypassesSubstitute = bypassesSubstitute)

    private fun applyBoost(
        state: BattleStateView,
        targetId: UUID,
        sourceId: UUID?,
        stages: Map<String, Int>,
        ignoreTargetAbility: Boolean,
        updateItems: Boolean,
        mirrorReflection: Boolean,
        bypassesSubstitute: Boolean = false,
    ): BattleStateView {
        if (stages.isEmpty()) return state
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return state
        if (target.fainted || target.hpFraction <= 0.0) return state
        val ability = if (ignoreTargetAbility) null else LocalPublicAbilityState.effectiveKnownAbility(state, target)
        val fromOther = sourceId != null && sourceId != targetId
        val source = sourceId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } }
        // The caller's spelling is kept; stats are compared by their normalised name.
        var change = stages.mapValues { (stat, amount) ->
            val reshaped = when (ability) {
                "contrary" -> -amount
                "simple" -> amount * 2
                else -> amount
            }
            val current = target.statStages.entries.firstOrNull { normalise(it.key) == normalise(stat) }?.value ?: 0
            ((current + reshaped).coerceIn(-6, 6) - current)
        }.filterValues { it != 0 }
        var reflected = emptyMap<String, Int>()
        if (fromOther && change.values.any { it < 0 }) {
            val drops = change.filterValues { it < 0 }
            val stopped = ability in DROP_STOPPING_ABILITIES ||
                !ignoreTargetAbility && LocalPublicStatusImmunity.flowerVeiled(state, target) ||
                !bypassesSubstitute && !mirrorReflection && target.knownVolatileEffectIds.any { PublicIds.canonical(it) == SUBSTITUTE } ||
                LocalPublicItemState.activeItemId(state, target) == CLEAR_AMULET ||
                source != null && source.side != target.side && mistActive(state, target)
            when {
                stopped -> change = change.filterValues { it >= 0 }
                ability == MIRROR_ARMOR && !mirrorReflection -> {
                    reflected = drops
                    change = change.filterValues { it >= 0 }
                }
            }
        }
        var next = state
        // TryBoost reflects each drop before the original boost loop. Its source is the reflector;
        // the ability origin prevents a second reflection and bypasses the original user's decoy.
        if (source != null) reflected.forEach { (stat, amount) ->
            next = applyBoost(next, source.battlePokemonId, targetId, mapOf(stat to amount),
                ignoreTargetAbility = false, updateItems = false, mirrorReflection = true)
        }
        for ((stat, amount) in change) {
            val current = next.pokemon.first { it.battlePokemonId == targetId }
            val before = current.statStages.entries.firstOrNull { normalise(it.key) == normalise(stat) }?.value ?: 0
            next = changeStages(next, current, mapOf(stat to amount))
            val after = next.pokemon.first { it.battlePokemonId == targetId }.statStages.entries
                .firstOrNull { normalise(it.key) == normalise(stat) }?.value ?: 0
            // AfterEachBoost reacts to each actual decrease, with its own cap, before the next stat.
            if (after < before && source != null && source.side != target.side) {
                val reactiveStat = when (ability) {
                    "defiant" -> "attack"
                    "competitive" -> "special_attack"
                    else -> null
                }
                if (reactiveStat != null) next = applyBoost(next, targetId, targetId,
                    mapOf(reactiveStat to 2), ignoreTargetAbility = false, updateItems = false,
                    mirrorReflection = false)
            }
        }
        if (!updateItems) return next
        next = whiteHerb(next, targetId)
        return if (reflected.isNotEmpty() && source != null) whiteHerb(next, source.battlePokemonId) else next
    }

    /**
     * The change [target] actually takes, in the caller's stat spelling, for readers that apply stages themselves:
     * Contrary and Simple, and drops from another Pokemon stopped or turned into Defiant and Competitive.
     */
    fun reshape(state: BattleStateView, target: BattlePokemonStateView, sourceId: UUID?, stages: Map<String, Int>): Map<String, Int> {
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, target)
        var change = stages.mapValues { (_, amount) ->
            when (ability) {
                "contrary" -> -amount
                "simple" -> amount * 2
                else -> amount
            }
        }
        if (sourceId != null && sourceId != target.battlePokemonId && change.values.any { it < 0 }) {
            val source = state.pokemon.firstOrNull { it.battlePokemonId == sourceId }
            val stopped = ability in DROP_STOPPING_ABILITIES || ability == MIRROR_ARMOR ||
                LocalPublicItemState.activeItemId(state, target) == CLEAR_AMULET ||
                source != null && source.side != target.side && mistActive(state, target)
            if (stopped) {
                change = change.filterValues { it >= 0 }
            } else if (ability == "defiant" || ability == "competitive") {
                val stat = if (ability == "defiant") "attack" else "special_attack"
                val key = change.keys.firstOrNull { normalise(it) == stat } ?: stat
                change = change + (key to (change[key] ?: 0) + 2)
            }
        }
        return change
    }

    /** A White Herb restores lowered stats once, then is spent. */
    fun whiteHerb(state: BattleStateView, pokemonId: UUID): BattleStateView {
        val pokemon = state.pokemon.firstOrNull { it.battlePokemonId == pokemonId } ?: return state
        if (LocalPublicItemState.activeItemId(state, pokemon) != WHITE_HERB) return state
        if (pokemon.statStages.values.none { it < 0 }) return state
        return state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId != pokemonId) it else it.copyState(
                statStages = it.statStages.mapValues { (_, value) -> value.coerceAtLeast(0) },
                knownHeldItemId = "",
            )
        })
    }

    private fun changeStages(state: BattleStateView, target: BattlePokemonStateView, change: Map<String, Int>): BattleStateView {
        if (change.isEmpty()) return state
        val stages = target.statStages.toMutableMap()
        change.forEach { (stat, amount) ->
            val key = stages.keys.firstOrNull { normalise(it) == normalise(stat) } ?: stat
            stages[key] = ((stages[key] ?: 0) + amount).coerceIn(-6, 6)
        }
        if (stages == target.statStages) return state
        return state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) it.copyState(statStages = stages) else it
        })
    }

    private fun mistActive(state: BattleStateView, target: BattlePokemonStateView): Boolean =
        state.field.sideConditions[target.side].orEmpty().any {
            PublicIds.canonical(it.effectId) == MIST && (it.remainingTurns == null || it.remainingTurns > 0)
        }

    /** One key per stat, whichever spelling the effect or the state used. */
    fun normalise(stat: String): String = when (PublicIds.canonical(stat)) {
        "atk", "attack" -> "attack"
        "def", "defense", "defence" -> "defence"
        "spa", "specialattack" -> "special_attack"
        "spd", "specialdefense", "specialdefence" -> "special_defence"
        "spe", "speed" -> "speed"
        "accuracy" -> "accuracy"
        "evasion" -> "evasion"
        else -> stat
    }

    private val DROP_STOPPING_ABILITIES = setOf("clearbody", "whitesmoke", "fullmetalbody")
    private const val MIRROR_ARMOR = "mirrorarmor"
    private const val CLEAR_AMULET = "clearamulet"
    private const val WHITE_HERB = "whiteherb"
    private const val MIST = "mist"
    private const val SUBSTITUTE = "substitute"
}
