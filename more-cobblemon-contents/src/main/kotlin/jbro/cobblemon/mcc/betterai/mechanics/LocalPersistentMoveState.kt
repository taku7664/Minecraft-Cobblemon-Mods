package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*
import java.util.UUID
import kotlin.math.floor
import kotlin.math.ceil

/** Actual move state carried by a projected branch, separate from selection or score policy. */
internal object LocalPersistentMoveState {
    private const val SUBSTITUTE_HP = "substitutehp:"
    private const val RAMPAGE = "rampage:"
    private const val PUBLIC_RAMPAGE = "rampagepublic:"
    private const val PERISH = "perishsong:"
    private const val HEALING_WISH = "healingwishslot:"

    fun substituteFraction(pokemon: BattlePokemonStateView): Double? = pokemon.knownVolatileEffectIds
        .firstOrNull { it.startsWith(SUBSTITUTE_HP) }?.removePrefix(SUBSTITUTE_HP)?.toDoubleOrNull()

    fun substituteCost(pokemon: BattlePokemonStateView): Double = pokemon.combatStats?.maxHp
        ?.takeIf { it.minimum == it.maximum }?.let { floor(it.minimum / 4.0) / it.minimum } ?: 0.25

    fun shedTailCost(pokemon: BattlePokemonStateView): Double = pokemon.combatStats?.maxHp
        ?.takeIf { it.minimum == it.maximum }?.let { ceil(it.minimum / 2.0) / it.minimum } ?: 0.5

    fun withSubstitute(pokemon: BattlePokemonStateView, fraction: Double): BattlePokemonStateView = pokemon.copyState(
        knownVolatileEffectIds = pokemon.knownVolatileEffectIds.filterNot { it.startsWith(SUBSTITUTE_HP) }.toSet() +
            setOf("substitute", SUBSTITUTE_HP + fraction),
    )

    fun damageSubstitute(pokemon: BattlePokemonStateView, damage: Double): BattlePokemonStateView {
        // A newly created decoy has exact branch HP. An already observed decoy has no public remaining HP.
        val remaining = substituteFraction(pokemon)?.minus(damage)
        return if (remaining != null && remaining > 1e-9) withSubstitute(pokemon, remaining) else pokemon.copyState(
            knownVolatileEffectIds = pokemon.knownVolatileEffectIds.filterNot {
                PublicIds.canonical(it) == "substitute" || it.startsWith(SUBSTITUTE_HP)
            }.toSet(),
        )
    }

    data class RampageLock(val moveId: String, val turns: Int)
    fun rampageLock(pokemon: BattlePokemonStateView): RampageLock? {
        val parts = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(RAMPAGE) }?.split(':') ?: return null
        return parts.getOrNull(2)?.toIntOrNull()?.let { RampageLock(parts[1], it) }
    }

    fun rampageMoveId(pokemon: BattlePokemonStateView): String? = rampageLock(pokemon)?.moveId ?:
        pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(PUBLIC_RAMPAGE) }?.split(':')?.getOrNull(1)

    /** The first revealed use leaves 1 or 2 turns; a second non-confused use fixes the last turn. */
    fun publicRampageOutcomes(state: BattleStateView): List<Pair<BattleStateView, Double>> {
        var outcomes = listOf(state to 1.0)
        state.pokemon.filter { it.activeSlot != null && !it.fainted }.forEach { pokemon ->
            val marker = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(PUBLIC_RAMPAGE) } ?: return@forEach
            val parts = marker.split(':')
            val moveId = parts.getOrNull(1) ?: return@forEach
            val uses = parts.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val existing = rampageLock(pokemon)
            val remaining = existing?.let { listOf(it.turns) } ?: if (uses >= 2) listOf(1) else listOf(1, 2)
            outcomes = outcomes.flatMap { (before, probability) -> remaining.map { turns ->
                before.copyState(pokemon = before.pokemon.map { current ->
                    if (current.battlePokemonId != pokemon.battlePokemonId) current else current.copyState(
                        knownVolatileEffectIds = current.knownVolatileEffectIds - marker + "$RAMPAGE$moveId:$turns")
                }) to probability / remaining.size
            } }
        }
        return outcomes
    }

    fun startRampage(state: BattleStateView, userId: UUID, moveId: String): List<Pair<BattleStateView, Double>> {
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId && !it.fainted } ?: return listOf(state to 1.0)
        if (rampageLock(user) != null) return listOf(state to 1.0)
        return listOf(2, 3).map { turns ->
            state.copyState(pokemon = state.pokemon.map {
                if (it.battlePokemonId == userId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + "$RAMPAGE$moveId:$turns") else it
            }) to 0.5
        }
    }

    fun startPerishSong(state: BattleStateView): BattleStateView = state.copyState(pokemon = state.pokemon.map {
        if (it.activeSlot == null || it.fainted || it.hpFraction <= 0.0 ||
            LocalPublicAbilityState.effectiveKnownAbility(state, it) == "soundproof" ||
            it.knownVolatileEffectIds.any { v -> v.startsWith(PERISH) }) it
        else it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + "${PERISH}4")
    })

    /** Residual order 24: timers advance after damage/healing; a switch has already discarded them. */
    fun afterResidual(state: BattleStateView, moveResidualTerrain: BattleTimedEffectView? = state.field.terrain): BattleStateView = state.copyState(pokemon = state.pokemon.map { pokemon ->
        if (pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0) return@map pokemon
        var updated = pokemon
        val perish = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(PERISH) }
        if (perish != null) {
            val remaining = perish.removePrefix(PERISH).toIntOrNull()?.minus(1)
            if (remaining != null) updated = if (remaining <= 0) updated.copyState(hpFraction = 0.0, fainted = true)
            else updated.copyState(knownVolatileEffectIds = updated.knownVolatileEffectIds - perish + "$PERISH$remaining")
        }
        val healBlock = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith("healblockturns:") }
        if (healBlock != null && !updated.fainted) {
            val remaining = healBlock.substringAfter(':').toIntOrNull()?.minus(1) ?: 0
            val cleared = updated.knownVolatileEffectIds - healBlock
            updated = updated.copyState(knownVolatileEffectIds = if (remaining <= 0) cleared - "healblock"
                else cleared + "healblockturns:$remaining")
        }
        for ((timer, effect) in listOf("laserfocusturns:" to "laserfocus", "disableturns:" to "disable")) {
            val marker = pokemon.knownVolatileEffectIds.firstOrNull { it.startsWith(timer) } ?: continue
            val remaining = marker.substringAfter(':').toIntOrNull()?.minus(1) ?: 0
            val cleared = updated.knownVolatileEffectIds - marker
            updated = updated.copyState(knownVolatileEffectIds = if (remaining > 0) cleared + "$timer$remaining"
                else cleared.filterNot { it == effect || effect == "disable" && it.startsWith("disablemove:") }.toSet())
        }
        val rampage = rampageLock(pokemon)
        if (rampage != null && !updated.fainted) {
            val cleared = updated.knownVolatileEffectIds.filterNot { it.startsWith(RAMPAGE) }.toSet()
            updated = updated.copyState(knownVolatileEffectIds = if (PublicIds.canonical(pokemon.statusId.orEmpty()) in setOf("slp", "sleep")) {
                cleared
            } else if (rampage.turns > 1) {
                cleared + "$RAMPAGE${rampage.moveId}:${rampage.turns - 1}"
            } else if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) != "owntempo" &&
                !(PublicIds.canonical(moveResidualTerrain?.effectId.orEmpty()) == "mistyterrain" &&
                    LocalPublicTurnOrder.grounded(state, pokemon) && pokemon.knownVolatileEffectIds.none {
                        PublicIds.canonical(it) in setOf("bounce", "dig", "dive", "fly", "phantomforce", "shadowforce", "skydrop")
                    })) {
                cleared + "confusion"
            } else cleared)
        }
        updated.copyState(knownVolatileEffectIds = updated.knownVolatileEffectIds - "beakblast" - "confusionresolved" - "endure")
    })

    fun rememberDisabledMoves(before: BattleStateView, after: BattleStateView, lastMoves: Map<UUID, String>): BattleStateView =
        after.copyState(pokemon = after.pokemon.map { pokemon ->
            val previous = before.pokemon.firstOrNull { it.battlePokemonId == pokemon.battlePokemonId }
            if ("disable" !in pokemon.knownVolatileEffectIds || "disable" in previous?.knownVolatileEffectIds.orEmpty() ||
                pokemon.knownVolatileEffectIds.any { it.startsWith("disablemove:") }) pokemon else {
                val move = lastMoves[pokemon.battlePokemonId]
                pokemon.copyState(knownVolatileEffectIds = pokemon.knownVolatileEffectIds +
                    move?.let { setOf("disablemove:$it") }.orEmpty() + "disableturns:4")
            }
        })

    fun entryOrders(state: BattleStateView, entrants: List<BattlePokemonStateView>): List<Pair<List<BattlePokemonStateView>, Double>> {
        fun permutations(items: List<BattlePokemonStateView>): List<List<BattlePokemonStateView>> = if (items.isEmpty()) listOf(emptyList())
            else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
        val possible = permutations(entrants).filter { order -> entrants.all { left -> entrants.all { right ->
            val a = LocalPublicTurnOrder.effectiveSpeed(state, left)
            val b = LocalPublicTurnOrder.effectiveSpeed(state, right)
            left.battlePokemonId == right.battlePokemonId || a == null || b == null || a.first <= b.second || order.indexOf(left) < order.indexOf(right)
        } } }
        if (entrants.size == 2 && possible.size == 2) {
            val a = LocalPublicTurnOrder.effectiveSpeed(state, entrants[0])
            val b = LocalPublicTurnOrder.effectiveSpeed(state, entrants[1])
            if (a != null && b != null) {
                val chance = LocalPublicTurnOrder.uniformGreaterProbability(a, b)
                return possible.map { it to if (it.first() == entrants[0]) chance else 1.0 - chance }
            }
        }
        return possible.map { it to 1.0 / possible.size }
    }

    fun prepareBeakBlast(state: BattleStateView, userIds: Set<UUID>): BattleStateView = state.copyState(pokemon = state.pokemon.map {
        if (it.battlePokemonId in userIds && !it.fainted) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds + "beakblast") else it
    })

    fun contactBeakBlast(state: BattleStateView, userId: UUID, targetId: UUID?, action: BattleActionCandidate): BattleStateView {
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId && !it.fainted } ?: return state
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return state
        if ("beakblast" !in target.knownVolatileEffectIds || "contact" !in action.moveDetails?.effects?.mechanicFlags.orEmpty() ||
            LocalPublicItemState.activeItemId(state, user) == "protectivepads" ||
            LocalPublicAbilityState.effectiveKnownAbility(state, user) == "longreach" ||
            LocalPublicStatusImmunity.blocked(state, user, "brn", target)) return state
        return state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == userId) it.copyState(statusId = "brn") else it })
    }

    fun afterHit(state: BattleStateView, userId: UUID, targetId: UUID?, action: BattleActionCandidate,
                 landedOnPokemon: Boolean): BattleStateView {
        var current = contactBeakBlast(state, userId, targetId, action)
        val user = current.pokemon.firstOrNull { it.battlePokemonId == userId && !it.fainted } ?: return current
        val target = current.pokemon.firstOrNull { it.battlePokemonId == targetId } ?: return current
        if (landedOnPokemon && PublicIds.canonical(action.moveId.orEmpty()) in setOf("bugbite", "pluck") &&
            target.knownHeldItemId?.let { PublicIds.canonical(it).endsWith("berry") } == true &&
            LocalPublicAbilityState.effectiveKnownAbility(current, target) != "stickyhold") {
            val berry = requireNotNull(target.knownHeldItemId)
            current = current.copyState(pokemon = current.pokemon.map { if (it.battlePokemonId == targetId) it.copyState(knownHeldItemId = null) else it })
            // These moves run the stolen berry's Eat callback; Unnerve does not stop external consumption.
            current = LocalBerryMechanics.eatExternalBerry(current, user.battlePokemonId, berry)
        }
        if (PublicIds.canonical(action.moveId.orEmpty()) == "beakblast") current = current.copyState(pokemon = current.pokemon.map {
            if (it.battlePokemonId == userId) it.copyState(knownVolatileEffectIds = it.knownVolatileEffectIds - "beakblast") else it
        })
        return current
    }

    fun healingWish(state: BattleStateView, userId: UUID): BattleStateView {
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId && it.activeSlot != null } ?: return state
        if (state.pokemon.none { it.side == user.side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }) return state
        val next = state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == userId) it.copyState(hpFraction = 0.0, fainted = true) else it })
        val sides = next.field.sideConditions.toMutableMap()
        val effect = "$HEALING_WISH${user.activeSlot}"
        sides[user.side] = sides.getValue(user.side).filterNot { it.effectId == effect } + BattleTimedEffectView(effect, null)
        return next.derive(field = BattleFieldStateView(next.field.weather, next.field.terrain, next.field.roomEffects, next.field.globalEffects, sides))
    }

    fun afterSwitch(state: BattleStateView, incomingId: UUID): BattleStateView {
        val incoming = state.pokemon.firstOrNull { it.battlePokemonId == incomingId && it.activeSlot != null && !it.fainted } ?: return state
        val id = "$HEALING_WISH${incoming.activeSlot}"
        if (incoming.hpFraction >= 1.0 && incoming.statusId == null || state.field.sideConditions.getValue(incoming.side).none { it.effectId == id }) return state
        val healed = state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == incomingId) it.copyState(hpFraction = 1.0, statusId = null) else it })
        val sides = healed.field.sideConditions.toMutableMap()
        sides[incoming.side] = sides.getValue(incoming.side).filterNot { it.effectId == id }
        return healed.derive(field = BattleFieldStateView(healed.field.weather, healed.field.terrain, healed.field.roomEffects, healed.field.globalEffects, sides))
    }

    fun revive(state: BattleStateView, side: BattleSide, pokemonId: UUID?): BattleStateView = state.copyState(pokemon = state.pokemon.map {
        if (it.battlePokemonId == pokemonId && it.side == side && it.fainted) {
            val hp = it.combatStats?.maxHp?.takeIf { range -> range.minimum == range.maximum }
                ?.let { range -> floor(range.minimum / 2.0) / range.minimum } ?: 0.5
            it.copyState(hpFraction = hp, fainted = false, statusId = null)
        } else it
    })

    /** Baton Pass copies boosts and the volatile effects that Showdown does not mark noCopy. */
    fun pass(state: BattleStateView, outgoing: BattlePokemonStateView, incomingId: UUID, shedTail: Boolean): BattleStateView {
        val passed = passableEffects(outgoing.knownVolatileEffectIds, shedTail)
        return state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId != incomingId || it.fainted) it else {
                val copied = it.copyState(statStages = if (shedTail) it.statStages else outgoing.statStages,
                    knownVolatileEffectIds = it.knownVolatileEffectIds + passed)
                val clone = substituteFraction(outgoing)
                val oldMax = outgoing.combatStats?.maxHp?.let { range -> (range.minimum.toDouble() + range.maximum) / 2.0 }
                val newMax = it.combatStats?.maxHp?.let { range -> (range.minimum.toDouble() + range.maximum) / 2.0 }
                if (clone != null && oldMax != null && newMax != null) withSubstitute(copied, clone * oldMax / newMax) else copied
            }
        })
    }

    /** Copies existing metadata only; it never supplies an unobserved substitute HP or duration. */
    fun passableEffects(effects: Set<String>, shedTail: Boolean): Set<String> =
        effects.filterTo(linkedSetOf()) { volatile ->
            if (shedTail) volatile == "substitute" || volatile.startsWith(SUBSTITUTE_HP)
            else PublicIds.canonical(volatile) in PASSABLE_VOLATILES || volatile.startsWith(SUBSTITUTE_HP) ||
                volatile.startsWith(PERISH) || volatile.startsWith("healblockturns:") || volatile.startsWith("laserfocusturns:") ||
                volatile.startsWith("leechseedsource:")
        }

    private val PASSABLE_VOLATILES = setOf("substitute", "confusion", "focusenergy", "laserfocus", "aquaring", "ingrain", "magnetrise",
        "leechseed", "curse", "perishsong", "powertrick", "gastroacid", "embargo", "healblock", "stockpile", "telekinesis")
}
