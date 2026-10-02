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
    ): BattleStateView {
        if (stages.isEmpty()) return state
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return state
        if (target.fainted || target.hpFraction <= 0.0) return state
        val ability = if (ignoreTargetAbility) null else LocalPublicAbilityState.effectiveKnownAbility(state, target)
        val fromOther = sourceId != null && sourceId != targetId
        val source = sourceId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } }
        // The caller's spelling is kept; stats are compared by their normalised name.
        var change = stages.mapValues { (_, amount) ->
            when (ability) {
                "contrary" -> -amount
                "simple" -> amount * 2
                else -> amount
            }
        }
        var reflected = emptyMap<String, Int>()
        if (fromOther && change.values.any { it < 0 }) {
            val drops = change.filterValues { it < 0 }
            val stopped = ability in DROP_STOPPING_ABILITIES ||
                target.knownVolatileEffectIds.any { PublicIds.canonical(it) == SUBSTITUTE } ||
                LocalPublicItemState.activeItemId(state, target) == CLEAR_AMULET ||
                source != null && source.side != target.side && mistActive(state, target)
            when {
                ability == MIRROR_ARMOR -> {
                    reflected = drops
                    change = change.filterValues { it >= 0 }
                }
                stopped -> change = change.filterValues { it >= 0 }
                source != null && source.side == target.side -> Unit
                else -> {
                    val stat = when (ability) {
                        "defiant" -> "attack"
                        "competitive" -> "special_attack"
                        else -> null
                    }
                    if (stat != null) {
                        val key = change.keys.firstOrNull { normalise(it) == stat }
                            ?: target.statStages.keys.firstOrNull { normalise(it) == stat } ?: stat
                        change = change + (key to (change[key] ?: 0) + 2)
                    }
                }
            }
        }
        var next = changeStages(state, target, change)
        if (reflected.isNotEmpty() && source != null) next = apply(next, source.battlePokemonId, null, reflected)
        return whiteHerb(next, targetId)
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
                knownHeldItemId = null,
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
