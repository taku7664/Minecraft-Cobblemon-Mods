package jbro.cobblemon.mcc.betterai.state

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.LocalBadPoisonCounter
import jbro.cobblemon.mcc.betterai.mechanics.LocalHpArithmetic
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusBerry

/** Applies public, deterministic end-of-turn mechanics used by recursive search. */
internal object LocalEndTurnStateProjector {
    fun project(
        state: BattleStateView,
        badPoisonTurnsByPokemon: Map<java.util.UUID, Int> = emptyMap(),
        saltCuredPokemonIds: Set<java.util.UUID> = emptySet(),
        leechSeedSourceByPokemon: Map<java.util.UUID, java.util.UUID> = emptyMap(),
        ghostCursedPokemonIds: Set<java.util.UUID> = emptySet(),
        partiallyTrappedPokemonIds: Set<java.util.UUID> = emptySet(),
        /** Speed Boost waits a turn after its holder comes in (Showdown checks `activeTurns`). */
        enteredThisTurnPokemonIds: Set<java.util.UUID> = emptySet(),
        /** Slots whose Wish comes true this turn: half HP for whoever stands there. */
        wishSlots: Set<Pair<BattleSide, Int>> = emptySet(),
        /** Drowsy from last turn's Yawn: asleep now, unless something already stops it. */
        yawnPokemonIds: Set<java.util.UUID> = emptySet(),
        /** Cured at the end of this turn (Shed Skin's roll came up). */
        curedPokemonIds: Set<java.util.UUID> = emptySet(),
    ): BattleStateView {
        // Weather expires before its residual callback. Unknown durations retain the existing estimate.
        val nextField = decrementField(state.field)
        val weatherSuppressed = state.pokemon.any {
            it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                LocalPublicAbilityState.effectiveKnownAbility(state, it) in WEATHER_SUPPRESSION_ABILITIES
        }
        val weather = canonical(nextField.weather?.effectId)?.takeUnless { weatherSuppressed }
        val sandActive = weather == "sandstorm"
        val sun = weather in SUN_IDS
        val rain = weather in RAIN_IDS
        val snow = weather in SNOW_IDS
        val grassyTerrain = canonical(state.field.terrain?.effectId) == "grassyterrain"
        // Leech Seed's drain heals its seeder, so every drain is worked out first.
        val seedHealing = hashMapOf<java.util.UUID, Double>()
        val drained = state.pokemon.associate { pokemon ->
            val sourceId = leechSeedSourceByPokemon[pokemon.battlePokemonId]
            val drain = if (sourceId == null || pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0 ||
                LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == "magicguard") 0.0
                else minOf(hpFractionTick(pokemon, 8), pokemon.hpFraction)
            if (drain > 0.0 && sourceId != null) {
                // Liquid Ooze turns the seeder's heal into damage.
                val sign = if (LocalPublicAbilityState.effectiveKnownAbility(state, pokemon) == "liquidooze") -1.0 else 1.0
                seedHealing[sourceId] = (seedHealing[sourceId] ?: 0.0) + sign * drain
            }
            pokemon.battlePokemonId to drain
        }
        val next = state.pokemon.map { pokemon ->
            if (pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0) return@map pokemon
            val ability = LocalPublicAbilityState.effectiveKnownAbility(state, pokemon)
            val item = LocalPublicItemState.activeItemId(state, pokemon)
            val umbrella = item == "utilityumbrella"
            val magicGuard = ability == "magicguard"
            val types = pokemon.knownTypeIds.mapTo(hashSetOf()) { canonical(it) }
            val stages = if (ability == "speedboost" && pokemon.battlePokemonId !in enteredThisTurnPokemonIds) {
                pokemon.statStages.toMutableMap().also { current ->
                    val speedKey = current.keys.firstOrNull { canonical(it) in SPEED_IDS } ?: "speed"
                    current[speedKey] = ((current[speedKey] ?: 0) + 1).coerceAtMost(6)
                }
            } else {
                pokemon.statStages
            }
            var status = canonical(pokemon.statusId)
            // Hydration cures in rain before the status deals its damage.
            if (ability == "hydration" && rain && !umbrella && status != null) status = null
            if (pokemon.battlePokemonId in curedPokemonIds) status = null
            val poisonHeal = ability == "poisonheal" && status in POISON_IDS
            var hp = pokemon.hpFraction
            fun apply(change: Double) {
                if (hp > 0.0) hp = LocalHpArithmetic.change(pokemon, hp, change).coerceIn(0.0, 1.0)
            }
            // Native event order: weather (1), weather abilities (2), terrain and item healing (5), Leech Seed (8),
            // poison/burn (9/10), Curse (12), binding and Salt Cure (13).
            if (sandActive && ability !in SAND_IMMUNE_ABILITIES &&
                item != "safetygoggles" &&
                types.none { it in SAND_IMMUNE_TYPES }) {
                apply(-hpFractionTick(pokemon, 16))
            }
            if (!umbrella) when {
                rain && ability == "raindish" -> apply(hpFractionTick(pokemon, 16))
                rain && ability == "dryskin" -> apply(hpFractionTick(pokemon, 8))
                sun && ability == "dryskin" && !magicGuard -> apply(-hpFractionTick(pokemon, 8))
                sun && ability == "solarpower" && !magicGuard -> apply(-hpFractionTick(pokemon, 8))
            }
            if (snow && ability == "icebody") apply(hpFractionTick(pokemon, 16))
            if ((pokemon.side to pokemon.activeSlot) in wishSlots) apply(0.5)
            val volatiles = pokemon.knownVolatileEffectIds.mapTo(hashSetOf()) { canonical(it) }
            if ("aquaring" in volatiles) apply(hpFractionTick(pokemon, 16))
            if ("ingrain" in volatiles) apply(hpFractionTick(pokemon, 16))
            // Bad Dreams: an eighth from every sleeping foe.
            if (!magicGuard && canonical(pokemon.statusId) in SLEEP_IDS && state.pokemon.any {
                    it.side != pokemon.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                        LocalPublicAbilityState.effectiveKnownAbility(state, it) == "baddreams"
                }) apply(-hpFractionTick(pokemon, 8))
            if (grassyTerrain && LocalPublicTurnOrder.grounded(state, pokemon)) apply(hpFractionTick(pokemon, 16))
            if (item == "leftovers") apply(hpFractionTick(pokemon, 16))
            if (item == "blacksludge") {
                if ("poison" in types) apply(hpFractionTick(pokemon, 16)) else if (!magicGuard) apply(-hpFractionTick(pokemon, 8))
            }
            drained[pokemon.battlePokemonId]?.takeIf { it > 0.0 }?.let { apply(-it) }
            seedHealing[pokemon.battlePokemonId]?.let { apply(it) }
            if (poisonHeal) {
                apply(hpFractionTick(pokemon, 8))
            } else if (!magicGuard) {
                apply(-when (status) {
                    in BAD_POISON_IDS -> hpFractionTick(pokemon, 16,
                        (badPoisonTurnsByPokemon[pokemon.battlePokemonId] ?: 1)
                            .coerceIn(1, LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN))
                    in REGULAR_POISON_IDS -> hpFractionTick(pokemon, 8)
                    in BURN_IDS -> hpFractionTick(pokemon, if (ability == "heatproof") 32 else 16)
                    else -> 0.0
                })
            }
            if (!magicGuard && pokemon.battlePokemonId in ghostCursedPokemonIds) apply(-hpFractionTick(pokemon, 4))
            if (!magicGuard && pokemon.battlePokemonId in partiallyTrappedPokemonIds) {
                apply(-hpFractionTick(pokemon, if (item == "bindingband") 6 else 8))
            }
            if (!magicGuard && pokemon.battlePokemonId in saltCuredPokemonIds) {
                val divisor = if (types.any { it in SALT_CURE_WEAK_TYPES }) 4 else 8
                apply(-hpFractionTick(pokemon, divisor))
            }
            // A Flame Orb or Toxic Orb inflicts its status at the end of the turn (Guts and Poison Heal want it).
            val orbStatus = when (item) {
                "flameorb" -> "brn".takeIf { status == null && "fire" !in types }
                "toxicorb" -> "tox".takeIf { status == null && "poison" !in types && "steel" !in types }
                else -> null
            }
            val yawnSleep = "slp".takeIf {
                pokemon.battlePokemonId in yawnPokemonIds && status == null && hp > 0.0 &&
                    !jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(state, pokemon, "slp")
            }
            copyPokemon(pokemon, hpFraction = hp, statStages = stages, fainted = hp <= 0.0,
                statusId = yawnSleep ?: orbStatus ?: if (status == null) null else pokemon.statusId)
        }
        val projected = state.derive(
            pokemon = next,
            field = nextField,
            remainingPokemonBySide = BattleSide.entries.associateWith { side ->
                val previousKnownLiving = state.pokemon.count {
                    it.side == side && !it.fainted && it.hpFraction > 0.0
                }
                val nextKnownLiving = next.count {
                    it.side == side && !it.fainted && it.hpFraction > 0.0
                }
                (state.remainingPokemonBySide.getValue(side) + nextKnownLiving - previousKnownLiving)
                    .coerceAtLeast(0)
            },
        )
        // Update uses the post-residual field and living foes: Magic Room may have expired,
        // or Unnerve may have fainted. Curing here does not refund earlier poison/burn damage.
        return LocalPublicStatusBerry.afterUpdate(projected)
    }

    private fun hpFractionTick(pokemon: BattlePokemonStateView, divisor: Int, ticks: Int = 1): Double {
        val maxHp = pokemon.combatStats?.maxHp
        // Only a public point range permits integer HP rounding. Do not invent hidden max HP.
        if (maxHp == null || maxHp.minimum != maxHp.maximum) return ticks.toDouble() / divisor
        // Native toxic rounds the base tick before multiplying the public elapsed-turn counter.
        return (maxHp.minimum / divisor).coerceAtLeast(1).toDouble() * ticks / maxHp.minimum
    }

    private fun decrementField(field: BattleFieldStateView): BattleFieldStateView = BattleFieldStateView(
        weather = field.weather?.let(::decrement),
        terrain = field.terrain?.let(::decrement),
        roomEffects = field.roomEffects.mapNotNull(::decrement),
        globalEffects = field.globalEffects.mapNotNull(::decrement),
        sideConditions = BattleSide.entries.associateWith { side ->
            field.sideConditions.getValue(side).mapNotNull(::decrement)
        },
    )

    private fun decrement(effect: BattleTimedEffectView): BattleTimedEffectView? {
        effect.remainingTurns?.let { remaining ->
            if (remaining <= 1) return null
            return BattleTimedEffectView(
                effectId = effect.effectId,
                remainingTurns = remaining - 1,
                stacks = effect.stacks,
            )
        }
        val range = effect.remainingTurnsRange ?: return effect
        if (range.maximum <= 1) return null
        val nextMinimum = (range.minimum - 1).coerceAtLeast(1)
        val nextMaximum = range.maximum - 1
        return if (nextMinimum == nextMaximum) {
            BattleTimedEffectView(effect.effectId, nextMinimum, effect.stacks)
        } else {
            BattleTimedEffectView(
                effectId = effect.effectId,
                remainingTurns = null,
                stacks = effect.stacks,
                remainingTurnsRange = BattleIntegerRange(nextMinimum, nextMaximum),
            )
        }
    }

    private fun copyPokemon(
        pokemon: BattlePokemonStateView,
        hpFraction: Double,
        statStages: Map<String, Int>,
        fainted: Boolean,
        statusId: String? = pokemon.statusId,
    ) = BattlePokemonStateView(
        battlePokemonId = pokemon.battlePokemonId,
        side = pokemon.side,
        activeSlot = pokemon.activeSlot,
        speciesId = pokemon.speciesId,
        formId = pokemon.formId,
        level = pokemon.level,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = pokemon.knownMoveIds,
        knownAbilityId = pokemon.knownAbilityId,
        knownHeldItemId = pokemon.knownHeldItemId,
        fainted = fainted,
        knownTypeIds = pokemon.knownTypeIds,
        combatStats = pokemon.combatStats,
        knownFormStates = pokemon.knownFormStates,
        actionConstraints = pokemon.actionConstraints,
        // Endure lasts the turn it was used.
        // Endure and Glaive Rush's check last the turn; Throat Chop's two turns are read as one search turn.
        knownVolatileEffectIds = if (fainted) emptySet() else pokemon.knownVolatileEffectIds
            .filterNot { canonical(it) in TURN_VOLATILES }.toSet(),
        knownBaseStabTypeIds = pokemon.knownBaseStabTypeIds,
        knownTeraTypeId = pokemon.knownTeraTypeId,
        knownStellarBoostedTypeIds = pokemon.knownStellarBoostedTypeIds,
    )

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val SPEED_IDS = setOf("speed", "spe")
    private val REGULAR_POISON_IDS = setOf("psn", "poison", "poisoned")
    private val BAD_POISON_IDS = setOf("tox", "toxic", "badlypoisoned")
    private val POISON_IDS = REGULAR_POISON_IDS + BAD_POISON_IDS
    private val BURN_IDS = setOf("brn", "burn", "burned", "burnt")
    private val SALT_CURE_WEAK_TYPES = setOf("water", "steel")
    private val SAND_IMMUNE_TYPES = setOf("rock", "ground", "steel")
    private val SAND_IMMUNE_ABILITIES = setOf("magicguard", "overcoat", "sandveil", "sandrush", "sandforce")
    private val WEATHER_SUPPRESSION_ABILITIES = setOf("airlock", "cloudnine")
    private val SLEEP_IDS = setOf("slp", "sleep", "asleep")
    private val TURN_VOLATILES = setOf("endure", "attractresolved")
    private val SUN_IDS = setOf("sunnyday", "sun", "desolateland", "harshsunlight")
    private val RAIN_IDS = setOf("raindance", "rain", "primordialsea", "heavyrain")
    private val SNOW_IDS = setOf("snow", "snowscape", "hail")
}
