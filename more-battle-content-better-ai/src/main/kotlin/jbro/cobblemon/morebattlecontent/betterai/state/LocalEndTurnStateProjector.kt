package jbro.cobblemon.morebattlecontent.betterai.state

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalBadPoisonCounter
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalHpArithmetic

/** Applies public, deterministic end-of-turn mechanics used by recursive search. */
internal object LocalEndTurnStateProjector {
    fun project(
        state: BattleStateView,
        badPoisonTurnsByPokemon: Map<java.util.UUID, Int> = emptyMap(),
        saltCuredPokemonIds: Set<java.util.UUID> = emptySet(),
    ): BattleStateView {
        // Weather expires before its residual callback. Unknown durations retain the existing estimate.
        val nextField = decrementField(state.field)
        val sandActive = canonical(nextField.weather?.effectId) == "sandstorm" && state.pokemon.none {
            it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                canonical(it.knownAbilityId) in WEATHER_SUPPRESSION_ABILITIES
        }
        val next = state.pokemon.map { pokemon ->
            if (pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0) return@map pokemon
            val ability = canonical(pokemon.knownAbilityId)
            val stages = if (ability == "speedboost") {
                pokemon.statStages.toMutableMap().also { current ->
                    val speedKey = current.keys.firstOrNull { canonical(it) in SPEED_IDS } ?: "speed"
                    current[speedKey] = ((current[speedKey] ?: 0) + 1).coerceAtMost(6)
                }
            } else {
                pokemon.statStages
            }
            val status = canonical(pokemon.statusId)
            val poisonHeal = ability == "poisonheal" && status in POISON_IDS
            var hp = pokemon.hpFraction
            fun apply(change: Double) {
                if (hp > 0.0) hp = LocalHpArithmetic.change(pokemon, hp, change).coerceIn(0.0, 1.0)
            }
            // Native event order: weather (1), item healing (5), poison/burn (9/10), Salt Cure (13).
            if (sandActive && ability !in SAND_IMMUNE_ABILITIES &&
                canonical(pokemon.knownHeldItemId) != "safetygoggles" &&
                pokemon.knownTypeIds.none { canonical(it) in SAND_IMMUNE_TYPES }) {
                apply(-hpFractionTick(pokemon, 16))
            }
            if (canonical(pokemon.knownHeldItemId) == "leftovers") apply(hpFractionTick(pokemon, 16))
            if (poisonHeal) {
                apply(hpFractionTick(pokemon, 8))
            } else if (ability != "magicguard") {
                apply(-when (status) {
                    in BAD_POISON_IDS -> hpFractionTick(pokemon, 16,
                        (badPoisonTurnsByPokemon[pokemon.battlePokemonId] ?: 1)
                            .coerceIn(1, LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN))
                    in REGULAR_POISON_IDS -> hpFractionTick(pokemon, 8)
                    in BURN_IDS -> hpFractionTick(pokemon, 16)
                    else -> 0.0
                })
            }
            if (ability != "magicguard" && pokemon.battlePokemonId in saltCuredPokemonIds) {
                val divisor = if (pokemon.knownTypeIds.any { canonical(it) in SALT_CURE_WEAK_TYPES }) 4 else 8
                apply(-hpFractionTick(pokemon, divisor))
            }
            copyPokemon(pokemon, hpFraction = hp, statStages = stages, fainted = hp <= 0.0)
        }
        return BattleStateView(
            battleId = state.battleId,
            format = state.format,
            turn = state.turn,
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
            observedEvents = state.observedEvents,
            inferences = state.inferences,
        )
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
    ) = BattlePokemonStateView(
        battlePokemonId = pokemon.battlePokemonId,
        side = pokemon.side,
        activeSlot = pokemon.activeSlot,
        speciesId = pokemon.speciesId,
        formId = pokemon.formId,
        level = pokemon.level,
        hpFraction = hpFraction,
        statusId = pokemon.statusId,
        statStages = statStages,
        knownMoveIds = pokemon.knownMoveIds,
        knownAbilityId = pokemon.knownAbilityId,
        knownHeldItemId = pokemon.knownHeldItemId,
        fainted = fainted,
        knownTypeIds = pokemon.knownTypeIds,
        combatStats = pokemon.combatStats,
        knownFormStates = pokemon.knownFormStates,
        actionConstraints = pokemon.actionConstraints,
        knownVolatileEffectIds = if (fainted) emptySet() else pokemon.knownVolatileEffectIds,
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private val SPEED_IDS = setOf("speed", "spe")
    private val REGULAR_POISON_IDS = setOf("psn", "poison", "poisoned")
    private val BAD_POISON_IDS = setOf("tox", "toxic", "badlypoisoned")
    private val POISON_IDS = REGULAR_POISON_IDS + BAD_POISON_IDS
    private val BURN_IDS = setOf("brn", "burn", "burned", "burnt")
    private val SALT_CURE_WEAK_TYPES = setOf("water", "steel")
    private val SAND_IMMUNE_TYPES = setOf("rock", "ground", "steel")
    private val SAND_IMMUNE_ABILITIES = setOf("magicguard", "overcoat", "sandveil", "sandrush", "sandforce")
    private val WEATHER_SUPPRESSION_ABILITIES = setOf("airlock", "cloudnine")
}
