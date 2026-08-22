package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

internal data class LocalContactAfterHitBranch(
    val state: BattleStateView,
    val probability: Double,
)

/** Applies public contact reactions only after the move dealt direct damage. */
internal object LocalContactAfterHitMechanics {
    fun project(
        state: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        action: BattleActionCandidate,
        directDamageFraction: Double,
    ): List<LocalContactAfterHitBranch> {
        if (directDamageFraction <= 0.0 || "contact" !in action.moveDetails?.effects?.mechanicFlags.orEmpty()) {
            return listOf(LocalContactAfterHitBranch(state, 1.0))
        }
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorId }
            ?: return listOf(LocalContactAfterHitBranch(state, 1.0))
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId }
            ?: return listOf(LocalContactAfterHitBranch(state, 1.0))
        val indirectImmune = canonical(actor.knownAbilityId) == "magicguard"
        val contactDamage = if (indirectImmune) 0.0 else {
            (if (canonical(target.knownHeldItemId) == "rockyhelmet") 1.0 / 6.0 else 0.0) +
                (if (canonical(target.knownAbilityId) in CONTACT_DAMAGE_ABILITIES) 1.0 / 8.0 else 0.0)
        }
        val damaged = if (contactDamage > 0.0) {
            updateActor(state, actorId) { current ->
                val hp = (current.hpFraction - contactDamage).coerceAtLeast(0.0)
                copyPokemon(current, hpFraction = hp, fainted = hp <= 0.0)
            }
        } else {
            state
        }
        if (!canFlameBodyBurn(actor, target)) return listOf(LocalContactAfterHitBranch(damaged, 1.0))
        val burned = updateActor(damaged, actorId) { current -> copyPokemon(current, statusId = "cobblemon:burn") }
        return listOf(
            LocalContactAfterHitBranch(damaged, 0.70),
            LocalContactAfterHitBranch(burned, 0.30),
        )
    }

    private fun canFlameBodyBurn(actor: BattlePokemonStateView, target: BattlePokemonStateView): Boolean =
        canonical(target.knownAbilityId) == "flamebody" &&
            actor.statusId == null &&
            actor.knownTypeIds.none { canonical(it) == "fire" }

    private fun updateActor(
        state: BattleStateView,
        actorId: UUID,
        update: (BattlePokemonStateView) -> BattlePokemonStateView,
    ): BattleStateView {
        val pokemon = state.pokemon.map { if (it.battlePokemonId == actorId) update(it) else it }
        return BattleStateView(
            battleId = state.battleId,
            format = state.format,
            turn = state.turn,
            pokemon = pokemon,
            field = state.field,
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                val oldLiving = state.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
                val newLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
                (state.remainingPokemonBySide.getValue(side) + newLiving - oldLiving).coerceAtLeast(0)
            },
            observedEvents = state.observedEvents,
            inferences = state.inferences,
        )
    }

    private fun copyPokemon(
        pokemon: BattlePokemonStateView,
        hpFraction: Double = pokemon.hpFraction,
        statusId: String? = pokemon.statusId,
        fainted: Boolean = pokemon.fainted,
    ) = BattlePokemonStateView(
        battlePokemonId = pokemon.battlePokemonId,
        side = pokemon.side,
        activeSlot = pokemon.activeSlot,
        speciesId = pokemon.speciesId,
        formId = pokemon.formId,
        level = pokemon.level,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = pokemon.statStages,
        knownMoveIds = pokemon.knownMoveIds,
        knownAbilityId = pokemon.knownAbilityId,
        knownHeldItemId = pokemon.knownHeldItemId,
        fainted = fainted,
        knownTypeIds = pokemon.knownTypeIds,
        combatStats = pokemon.combatStats,
        knownFormStates = pokemon.knownFormStates,
        actionConstraints = pokemon.actionConstraints,
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private val CONTACT_DAMAGE_ABILITIES = setOf("roughskin", "ironbarbs")
}
