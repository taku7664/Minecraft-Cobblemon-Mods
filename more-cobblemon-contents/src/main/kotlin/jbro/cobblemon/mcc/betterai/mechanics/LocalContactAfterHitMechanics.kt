package jbro.cobblemon.mcc.betterai.mechanics

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*

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
        // Protective Pads and Long Reach make no contact for these reactions.
        if (LocalPublicItemState.activeItemId(state, actor) == "protectivepads" ||
            LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "longreach") {
            return listOf(LocalContactAfterHitBranch(state, 1.0))
        }
        val indirectImmune = LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "magicguard"
        val contactDamage = if (indirectImmune) 0.0 else {
            (if (LocalPublicItemState.activeItemId(state, target) == "rockyhelmet"
            ) 1.0 / 6.0 else 0.0) +
                (if (LocalPublicAbilityState.effectiveKnownAbility(state, target) in CONTACT_DAMAGE_ABILITIES) {
                    1.0 / 8.0
                } else 0.0)
        }
        val damaged = if (contactDamage > 0.0) {
            updateActor(state, actorId) { current ->
                val hp = (current.hpFraction - contactDamage).coerceAtLeast(0.0)
                copyPokemon(current, hpFraction = hp, fainted = hp <= 0.0)
            }
        } else {
            state
        }
        val targetAbility = LocalPublicAbilityState.effectiveKnownAbility(state, target)
        // Gooey and Tangling Hair lower the attacker's Speed; Aftermath takes a quarter from whoever knocked it out.
        var reacted = when (targetAbility) {
            "gooey", "tanglinghair" -> LocalStatStageChange.apply(damaged, actorId, targetId, mapOf("speed" to -1))
            "aftermath" -> if (!indirectImmune && (target.fainted || target.hpFraction <= 0.0)) updateActor(damaged, actorId) { current ->
                val hp = (current.hpFraction - 0.25).coerceAtLeast(0.0)
                copyPokemon(current, hpFraction = hp, fainted = hp <= 0.0)
            } else damaged
            else -> damaged
        }
        // A contact status on the attacker: Flame Body, Static, Poison Point (30%), Effect Spore (10% each).
        val statuses = when (targetAbility) {
            "flamebody" -> listOf("cobblemon:burn" to 0.30)
            "static" -> listOf("par" to 0.30)
            "poisonpoint" -> listOf("psn" to 0.30)
            "effectspore" -> if (actor.knownTypeIds.any { canonical(it) == "grass" } ||
                LocalPublicAbilityState.effectiveKnownAbility(state, actor) == "overcoat") emptyList()
                else listOf("slp" to 0.10, "par" to 0.10, "psn" to 0.10)
            else -> emptyList()
        }.filter { (status, _) ->
            val current = reacted.pokemon.first { it.battlePokemonId == actorId }
            !current.fainted && current.hpFraction > 0.0 && !LocalPublicStatusImmunity.blocked(reacted, current, status, target, byMove = false)
        }
        // Poison Touch and Toxic Chain poison the target in turn.
        val attackerStatus = when (LocalPublicAbilityState.effectiveKnownAbility(state, actor)) {
            "poisontouch" -> "psn" to 0.30
            "toxicchain" -> "tox" to 0.30
            else -> null
        }?.takeIf { (status, _) ->
            val current = reacted.pokemon.first { it.battlePokemonId == target.battlePokemonId }
            !current.fainted && current.hpFraction > 0.0 && !LocalPublicStatusImmunity.blocked(reacted, current, status, actor)
        }
        var branches = listOf(LocalContactAfterHitBranch(reacted, 1.0))
        if (statuses.isNotEmpty()) {
            val untouched = 1.0 - statuses.sumOf { it.second }
            branches = listOf(LocalContactAfterHitBranch(reacted, untouched)) + statuses.map { (status, chance) ->
                LocalContactAfterHitBranch(updateActor(reacted, actorId) { copyPokemon(it, statusId = status) }, chance)
            }
        }
        if (attackerStatus != null) {
            val (status, chance) = attackerStatus
            branches = branches.flatMap { branch ->
                listOf(
                    branch.copy(probability = branch.probability * (1.0 - chance)),
                    LocalContactAfterHitBranch(
                        updateActor(branch.state, target.battlePokemonId) { copyPokemon(it, statusId = status) },
                        branch.probability * chance,
                    ),
                )
            }
        }
        return branches
    }

    private fun updateActor(
        state: BattleStateView,
        actorId: UUID,
        update: (BattlePokemonStateView) -> BattlePokemonStateView,
    ): BattleStateView {
        val pokemon = state.pokemon.map { if (it.battlePokemonId == actorId) update(it) else it }
        return state.derive(
            pokemon = pokemon,
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                val oldLiving = state.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
                val newLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
                (state.remainingPokemonBySide.getValue(side) + newLiving - oldLiving).coerceAtLeast(0)
            },
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
        knownVolatileEffectIds = if (fainted) emptySet() else pokemon.knownVolatileEffectIds,
        knownBaseStabTypeIds = pokemon.knownBaseStabTypeIds,
        knownTeraTypeId = pokemon.knownTeraTypeId,
        knownStellarBoostedTypeIds = pokemon.knownStellarBoostedTypeIds,
    )

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val CONTACT_DAMAGE_ABILITIES = setOf("roughskin", "ironbarbs")
}
