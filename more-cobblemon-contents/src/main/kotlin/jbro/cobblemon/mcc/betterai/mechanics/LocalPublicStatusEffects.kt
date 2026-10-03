package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*

internal data class LocalPublicStatusProjection(
    val state: BattleStateView,
    /** An actual Lum eating event, rather than an inference from a missing item. */
    val confusionCuredPokemonIds: Set<UUID> = emptySet(),
)

/** Public status application and Lum's common AfterSetStatus/Update cure. */
internal object LocalPublicStatusEffects {
    fun setStatus(state: BattleStateView, targetId: UUID, statusId: String, sourceId: UUID? = null,
        byMove: Boolean = true, effectId: String? = null): LocalPublicStatusProjection {
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId }
            ?: return LocalPublicStatusProjection(state)
        if (target.activeSlot == null || target.fainted || target.hpFraction <= 0.0) return LocalPublicStatusProjection(state)
        val source = sourceId?.let { id -> state.pokemon.firstOrNull { it.battlePokemonId == id } }
        if (LocalPublicStatusImmunity.blocked(state, target, statusId, source, byMove = byMove, effectId = effectId)) {
            return LocalPublicStatusProjection(state)
        }
        return updateLum(replace(state, target.copyState(statusId = statusId)), targetId)
    }

    fun confuse(state: BattleStateView, targetId: UUID, sourceId: UUID): LocalPublicStatusProjection {
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId }
            ?: return LocalPublicStatusProjection(state)
        val source = state.pokemon.firstOrNull { it.battlePokemonId == sourceId }
        if (target.activeSlot == null || target.fainted || target.hpFraction <= 0.0 ||
            "confusion" in target.canonicalKnownVolatileEffectIds ||
            LocalPublicStatusImmunity.volatileBlocked(state, target, "confusion", source)
        ) return LocalPublicStatusProjection(state)
        return updateLum(replace(state, target.copyState(knownVolatileEffectIds = target.knownVolatileEffectIds + "confusion")), targetId)
    }

    fun updateLum(state: BattleStateView, targetId: UUID? = null): LocalPublicStatusProjection {
        val cured = linkedSetOf<UUID>()
        val pokemon = state.pokemon.map { current ->
            if (targetId != null && current.battlePokemonId != targetId ||
                current.statusId == null && "confusion" !in current.canonicalKnownVolatileEffectIds ||
                !canEatLum(state, current)
            ) current else {
                cured += current.battlePokemonId
                val removed = if (current.statusId?.let(PublicIds::canonical) in SLEEP_IDS) setOf("confusion", "nightmare")
                    else setOf("confusion")
                current.copyState(statusId = null, knownHeldItemId = "",
                    knownVolatileEffectIds = current.knownVolatileEffectIds.filterNot { PublicIds.canonical(it) in removed }.toSet())
            }
        }
        return LocalPublicStatusProjection(if (cured.isEmpty()) state else state.copyState(pokemon = pokemon), cured)
    }

    private fun canEatLum(state: BattleStateView, target: BattlePokemonStateView): Boolean =
        target.activeSlot != null && !target.fainted && target.hpFraction > 0.0 &&
            LocalPublicItemState.activeItemId(state, target) == "lumberry" && state.pokemon.none {
                it.side != target.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                    LocalPublicAbilityState.effectiveKnownAbility(state, it) in BERRY_STOPPING_ABILITIES
            }

    private fun replace(state: BattleStateView, target: BattlePokemonStateView): BattleStateView = state.copyState(
        pokemon = state.pokemon.map { if (it.battlePokemonId == target.battlePokemonId) target else it })

    private val BERRY_STOPPING_ABILITIES = setOf("unnerve", "asoneglastrier", "asonespectrier")
    private val SLEEP_IDS = setOf("slp", "sleep", "asleep")
}
