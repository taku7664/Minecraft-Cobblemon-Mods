package jbro.cobblemon.mcc.betterai.state

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.*

/** Residual random events retain their actual alternatives instead of rounding boosts or healing into a score. */
internal object LocalTraitResidualBranches {
    fun project(state: BattleStateView): List<LocalTraitStateBranch> {
        var branches = listOf(LocalTraitStateBranch(state, 1.0))
        for (holder in state.pokemon.filter { it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }) {
            branches = branches.flatMap { parent ->
                val current = parent.state.pokemon.first { it.battlePokemonId == holder.battlePokemonId }
                val alternatives = when (LocalPublicAbilityState.effectiveKnownAbility(parent.state, current)) {
                    "moody" -> moody(parent.state, current)
                    "harvest" -> harvest(parent.state, current)
                    else -> listOf(LocalTraitStateBranch(parent.state, 1.0))
                }
                alternatives.map { it.copy(probability = parent.probability * it.probability) }
            }
        }
        return branches
    }
    private fun moody(state: BattleStateView, holder: BattlePokemonStateView): List<LocalTraitStateBranch> {
        fun stage(stat: String) = holder.statStages.entries.firstOrNull { PublicIds.canonical(it.key) in ALIASES.getValue(stat) }?.value ?: 0
        val plus = STATS.filter { stage(it) < 6 }.map<String, String?> { it }.ifEmpty { listOf(null) }
        return plus.flatMap { raised ->
            val minus = STATS.filter { it != raised && stage(it) > -6 }.map<String, String?> { it }.ifEmpty { listOf(null) }
            minus.map { lowered ->
                val changes = buildMap { raised?.let { put(it, 2) }; lowered?.let { put(it, -1) } }
                LocalTraitStateBranch(LocalStatStageChange.apply(state, holder.battlePokemonId, holder.battlePokemonId, changes),
                    1.0 / plus.size / minus.size)
            }
        }
    }
    private fun harvest(state: BattleStateView, holder: BattlePokemonStateView): List<LocalTraitStateBranch> {
        val item = LocalBerryMechanics.lastConsumedItem(holder)?.takeIf { it.endsWith("berry") } ?: return listOf(LocalTraitStateBranch(state, 1.0))
        if (holder.canonicalKnownHeldItemId != null) return listOf(LocalTraitStateBranch(state, 1.0))
        val restored = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId != holder.battlePokemonId) it else it.copyState(knownHeldItemId = item,
                knownVolatileEffectIds = it.knownVolatileEffectIds.filterNot { marker -> marker.startsWith(LocalBerryMechanics.LAST_CONSUMED_ITEM) }.toSet())
        })
        val updated = LocalBerryMechanics.afterUpdate(restored)
        val sunny = LocalPublicFieldMechanics.effectiveWeatherId(state) in setOf("sunnyday", "sun", "desolateland", "harshsunlight")
        return if (sunny) listOf(LocalTraitStateBranch(updated, 1.0))
            else listOf(LocalTraitStateBranch(state, .5), LocalTraitStateBranch(updated, .5))
    }
    private val STATS = listOf("attack", "defence", "special_attack", "special_defence", "speed")
    private val ALIASES = mapOf("attack" to setOf("attack", "atk"), "defence" to setOf("defence", "defense", "def"),
        "special_attack" to setOf("specialattack", "spa"), "special_defence" to setOf("specialdefence", "specialdefense", "spd"), "speed" to setOf("speed", "spe"))
}
