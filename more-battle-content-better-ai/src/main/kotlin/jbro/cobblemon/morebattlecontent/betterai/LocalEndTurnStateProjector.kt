package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*

/** Applies public, deterministic end-of-turn mechanics used by recursive search. */
internal object LocalEndTurnStateProjector {
    fun project(
        state: BattleStateView,
        badPoisonTurnsByPokemon: Map<java.util.UUID, Int> = emptyMap(),
    ): BattleStateView {
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
            val residual = if (ability == "magicguard" || poisonHeal) 0.0 else when (status) {
                in BAD_POISON_IDS -> (
                    badPoisonTurnsByPokemon[pokemon.battlePokemonId] ?: 1
                    ).coerceIn(1, LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN) / 16.0
                in REGULAR_POISON_IDS, in BURN_IDS -> 1.0 / 16.0
                else -> 0.0
            }
            val passiveHealing = when {
                poisonHeal -> 1.0 / 8.0
                canonical(pokemon.knownHeldItemId) == "leftovers" -> 1.0 / 16.0
                else -> 0.0
            }
            val hp = (pokemon.hpFraction - residual + passiveHealing).coerceIn(0.0, 1.0)
            copyPokemon(pokemon, hpFraction = hp, statStages = stages, fainted = hp <= 0.0)
        }
        val nextField = decrementField(state.field)
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
        val remaining = effect.remainingTurns ?: return effect
        if (remaining <= 1) return null
        return BattleTimedEffectView(
            effectId = effect.effectId,
            remainingTurns = remaining - 1,
            stacks = effect.stacks,
            remainingTurnsRange = effect.remainingTurnsRange,
        )
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
}
