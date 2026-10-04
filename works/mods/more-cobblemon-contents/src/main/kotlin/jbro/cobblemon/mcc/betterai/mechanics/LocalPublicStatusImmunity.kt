package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Checks only status immunities proven by public facts: type, a known/confirmed ability, the field (Safeguard,
 * Misty and Electric Terrain) and a Substitute.
 */
internal object LocalPublicStatusImmunity {
    fun blocked(
        state: BattleStateView,
        target: BattlePokemonStateView,
        statusId: String?,
        /** Who inflicts it. Null is an unknown inflicter: field protection still applies. */
        source: BattlePokemonStateView? = null,
        ignoreTargetAbility: Boolean = false,
        /** False for a status that does not come from a move (Flame Body, an orb). A Substitute and Infiltrator are move rules. */
        byMove: Boolean = true,
    ): Boolean {
        if (target.statusId != null) return true
        val status = canonical(statusId)
        val types = target.knownTypeIds.mapTo(hashSetOf(), ::canonical)
        val ability = if (ignoreTargetAbility) null else LocalPublicAbilityState.effectiveKnownAbility(state, target)
            ?: state.inferences.asSequence()
                .filter { it.subjectPokemonId == target.battlePokemonId && canonical(it.categoryId) == "ability" }
                .mapNotNull { canonical(it.candidateId) }
                .distinct()
                .singleOrNull()
                ?.takeIf { LocalPublicAbilityState.isActive(state, target, it) }
        val sourceAbility = source?.let { LocalPublicAbilityState.effectiveKnownAbility(state, it) }
        val fromOther = source != null && source.battlePokemonId != target.battlePokemonId
        if (fieldBlocks(state, target, status, sourceAbility, source, fromOther, byMove)) return true
        if (ability in ALL_STATUS_IMMUNITIES) return true
        if (fromOther && flowerVeiled(state, target)) return true
        if (ability == LEAF_GUARD && LocalPublicFieldMechanics.effectiveWeatherId(state) in SUN_WEATHER &&
            LocalPublicItemState.activeItemId(state, target) != UTILITY_UMBRELLA) return true
        return when (status) {
            // Corrosion poisons Steel and Poison types alike.
            in POISON -> sourceAbility != CORROSION && ("poison" in types || "steel" in types) ||
                ability == "immunity" || ability == "pastelveil" || allyAbility(state, target, "pastelveil")
            in BURN -> "fire" in types || ability == "waterveil" || ability == "waterbubble" || ability == "thermalexchange"
            in PARALYSIS -> "electric" in types || ability == "limber"
            in SLEEP -> ability in setOf("insomnia", "vitalspirit", "sweetveil") || allyAbility(state, target, "sweetveil")
            in FREEZE -> "ice" in types || ability == "magmaarmor" ||
                LocalPublicFieldMechanics.effectiveWeatherId(state) in SUN_WEATHER
            else -> false
        }
    }

    /** A Grass type beside (or holding) Flower Veil. */
    fun flowerVeiled(state: BattleStateView, target: BattlePokemonStateView): Boolean =
        target.knownTypeIds.any { canonical(it) == "grass" } && state.pokemon.any {
            it.side == target.side && it.activeSlot != null && !it.fainted &&
                LocalPublicAbilityState.effectiveKnownAbility(state, it) == "flowerveil"
        }

    /** Pastel Veil and Sweet Veil also guard the holder's partner. */
    private fun allyAbility(state: BattleStateView, target: BattlePokemonStateView, ability: String): Boolean = state.pokemon.any {
        it.battlePokemonId != target.battlePokemonId && it.side == target.side && it.activeSlot != null &&
            !it.fainted && it.hpFraction > 0.0 && LocalPublicAbilityState.effectiveKnownAbility(state, it) == ability
    }

    /** Safeguard, Misty Terrain (any status) and Electric Terrain (sleep) for a grounded target, and a Substitute. */
    private fun fieldBlocks(
        state: BattleStateView,
        target: BattlePokemonStateView,
        status: String?,
        sourceAbility: String?,
        source: BattlePokemonStateView?,
        /** Safeguard stops any status another Pokemon inflicts, its abilities included; a Substitute only moves. */
        fromOther: Boolean,
        byMove: Boolean,
    ): Boolean {
        val terrain = LocalPublicFieldMechanics.terrainId(state)
        // A Pokemon in the air or underground during Fly or Dig is not on the terrain.
        val grounded = target.activeSlot != null && LocalPublicTurnOrder.grounded(state, target) &&
            target.knownVolatileEffectIds.none { canonical(it) in SEMI_INVULNERABLE }
        if (grounded && terrain == MISTY_TERRAIN) return true
        if (grounded && terrain == ELECTRIC_TERRAIN && status in SLEEP) return true
        if (!fromOther) return false
        val infiltrates = byMove && sourceAbility == INFILTRATOR && source?.side != target.side
        if (!infiltrates && state.field.sideConditions[target.side].orEmpty().any {
                canonical(it.effectId) == SAFEGUARD && (it.remainingTurns == null || it.remainingTurns > 0)
            }) return true
        return byMove && !infiltrates && SUBSTITUTE in target.knownVolatileEffectIds.mapTo(hashSetOf(), ::canonical)
    }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val POISON = setOf("psn", "poison", "poisoned", "tox", "toxic", "badlypoisoned")
    private val BURN = setOf("brn", "burn", "burned", "burnt")
    private val PARALYSIS = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val SLEEP = setOf("slp", "sleep", "asleep")
    private val FREEZE = setOf("frz", "freeze", "frozen")
    private val ALL_STATUS_IMMUNITIES = setOf("comatose", "purifyingsalt")
    private val SUN_WEATHER = setOf("sun", "sunnyday", "harshsunlight", "desolateland")
    private const val LEAF_GUARD = "leafguard"
    private const val CORROSION = "corrosion"
    private const val INFILTRATOR = "infiltrator"
    private const val SAFEGUARD = "safeguard"
    private const val SUBSTITUTE = "substitute"
    private const val MISTY_TERRAIN = "mistyterrain"
    private const val ELECTRIC_TERRAIN = "electricterrain"
    private const val UTILITY_UMBRELLA = "utilityumbrella"
    private val SEMI_INVULNERABLE = setOf("fly", "bounce", "dig", "dive", "phantomforce", "shadowforce", "skydrop")
}
