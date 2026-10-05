package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.betterai.simulation.EngineRuntimeDex
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** The public TakeItem contract, shared by item-transfer projection and its root value. */
internal object LocalPublicItemTransferRules {
    fun canSwap(
        state: BattleStateView,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
        ignoreTargetAbility: Boolean = false,
        bypassesSubstitute: Boolean = false,
    ): Boolean {
        if (actor.fainted || target.fainted || actor.activeSlot == null || target.activeSlot == null) return false
        // A missing observation is not an observed empty slot. Unknown items cannot establish that
        // all four TakeItem checks succeed, so the local fallback leaves that branch unresolved.
        if (actor.knownHeldItemId == null || target.knownHeldItemId == null) return false
        if (actor.canonicalKnownHeldItemId == null && target.canonicalKnownHeldItemId == null) return false
        if (!bypassesSubstitute && LocalSubstituteRules.present(target)) return false
        if (!ignoreTargetAbility && LocalPublicAbilityState.effectiveKnownAbility(state, target) == "stickyhold") return false
        val ours = actor.canonicalKnownHeldItemId
        val theirs = target.canonicalKnownHeldItemId
        // Trick first takes both items, then asks each item whether its new holder can receive it.
        return allowsTake(ours, actor, actor) && allowsTake(theirs, target, actor) &&
            allowsTake(ours, target, actor) && allowsTake(theirs, actor, target)
    }

    /** Applies a checked swap and the acquired item's immediate public update. */
    fun swap(state: BattleStateView, actor: BattlePokemonStateView, target: BattlePokemonStateView): BattleStateView {
        val exchanged = state.copyState(pokemon = state.pokemon.map {
            when (it.battlePokemonId) {
                actor.battlePokemonId -> it.copyState(knownHeldItemId = target.knownHeldItemId)
                target.battlePokemonId -> it.copyState(knownHeldItemId = actor.knownHeldItemId)
                else -> it
            }
        })
        return LocalStatStageChange.whiteHerb(
            LocalStatStageChange.whiteHerb(exchanged, actor.battlePokemonId), target.battlePokemonId,
        )
    }

    fun canRemove(state: BattleStateView, target: BattlePokemonStateView, ignoreTargetAbility: Boolean = false): Boolean {
        val item = target.canonicalKnownHeldItemId ?: return false
        if (!ignoreTargetAbility && !target.fainted && target.hpFraction > 0.0 && item != "stickybarb" &&
            LocalPublicAbilityState.effectiveKnownAbility(state, target) == "stickyhold"
        ) return false
        // Knock Off's item check passes the holder as both TakeItem arguments, unlike Trick.
        return allowsTake(item, target, target)
    }

    /** Item-owned restrictions also govern Knock Off's power; Sticky Hold does not remove that boost. */
    fun allowsTake(itemId: String?, holder: BattlePokemonStateView, source: BattlePokemonStateView): Boolean {
        itemId ?: return true
        val item = runCatching { EngineRuntimeDex.current().second.item(itemId) }.getOrNull()
        if (item?.data("onTakeItem") == false) return false
        val holderSpecies = baseSpecies(holder)
        val sourceSpecies = baseSpecies(source)
        val megaStone = item?.data("megaStone")
        val megaOwners = when (megaStone) {
            is Map<*, *> -> megaStone.keys.filterIsInstance<String>().map(PublicIds::canonical)
            is String -> listOfNotNull(item.string("megaEvolves")?.let(PublicIds::canonical))
            else -> emptyList()
        }
        if (holderSpecies in megaOwners) return false
        if (item?.data("onPlate") != null && item.data("zMove") != true &&
            (holderSpecies == "arceus" || sourceSpecies == "arceus")
        ) return false
        if (item?.data("onMemory") != null && (holderSpecies == "silvally" || sourceSpecies == "silvally")) return false
        val signatureSpecies = SIGNATURE_ITEMS[itemId]
        if (signatureSpecies != null && (holderSpecies == signatureSpecies || sourceSpecies == signatureSpecies)) return false
        if (itemId in MASKS && holderSpecies == "ogerpon") return false
        if (itemId == "redorb" && holderSpecies == "groudon" || itemId == "blueorb" && holderSpecies == "kyogre") return false
        if (itemId == "boosterenergy" && LocalPublicSpeciesData.species(holder)?.list("tags")?.contains("Paradox") == true) return false
        return true
    }

    private fun baseSpecies(pokemon: BattlePokemonStateView): String = PublicIds.canonical(
        LocalPublicSpeciesData.species(pokemon)?.baseSpecies ?: pokemon.speciesId.substringAfter(':'),
    )

    private val SIGNATURE_ITEMS = mapOf(
        "adamantcrystal" to "dialga", "lustrousglobe" to "palkia", "griseouscore" to "giratina",
        "rustedsword" to "zacian", "rustedshield" to "zamazenta",
    )
    private val MASKS = setOf("wellspringmask", "hearthflamemask", "cornerstonemask")
}
