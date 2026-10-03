package jbro.cobblemon.mcc.betterai.state

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange

/** Applies deterministic, publicly known ability effects caused by entering battle. */
internal object LocalEntryAbilityProjector {
    fun project(state: BattleStateView, incomingPokemonId: UUID): BattleStateView {
        val incoming = state.pokemon.firstOrNull {
            it.battlePokemonId == incomingPokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, incoming)
        val fieldState = projectField(state, incoming, ability)
        entryBoost(fieldState, incoming, ability)?.let { boost ->
            return jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange.apply(fieldState, incoming.battlePokemonId, null, boost)
        }
        if (ability != "intimidate") return fieldState

        val foes = fieldState.pokemon.filter {
            it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        var next = fieldState
        for (foe in foes) {
            val current = next.pokemon.first { it.battlePokemonId == foe.battlePokemonId }
            // Intimidate never calls boost through a decoy, even when Contrary would turn it positive.
            if ("substitute" in current.canonicalKnownVolatileEffectIds) continue
            val targetAbility = LocalPublicAbilityState.effectiveKnownAbility(next, current)
            if (targetAbility in INTIMIDATE_IMMUNITIES) continue
            if (targetAbility == "guarddog") {
                // TryBoost receives the already capped change, so a floor of -6 gives it zero.
                val attack = current.statStages.entries.firstOrNull {
                    LocalStatStageChange.normalise(it.key) == "attack"
                }?.value ?: 0
                if (attack > -6) next = LocalStatStageChange.apply(next, current.battlePokemonId,
                    current.battlePokemonId, mapOf("attack" to 1), updateItems = false)
            } else {
                next = LocalStatStageChange.apply(next, current.battlePokemonId, incomingPokemonId,
                    mapOf("attack" to -1), updateItems = false)
                // AfterBoost still runs when an item or a stage limit removed the attempted drop.
                if (targetAbility == "rattled") next = LocalStatStageChange.apply(next, current.battlePokemonId,
                    current.battlePokemonId, mapOf("speed" to 1), updateItems = false)
            }
        }
        // Showdown updates held items after this entry event, including all Mirror Armor reflections.
        return (foes.map { it.battlePokemonId } + incomingPokemonId).fold(next, LocalStatStageChange::whiteHerb)
    }

    /**
     * Download raises Attack or Special Attack by the foes' weaker defence; Intrepid Sword and Dauntless Shield raise
     * Attack and Defence on entry.
     */
    private fun entryBoost(state: BattleStateView, incoming: BattlePokemonStateView, ability: String?): Map<String, Int>? =
        when (ability) {
            "intrepidsword" -> mapOf("attack" to 1)
            "dauntlessshield" -> mapOf("defence" to 1)
            "download" -> {
                val foes = state.pokemon.filter { it.side != incoming.side && it.activeSlot != null && !it.fainted }
                val defence = foes.sumOf { it.combatStats?.defence?.let { r -> (r.minimum + r.maximum) / 2 } ?: 0 }
                val special = foes.sumOf { it.combatStats?.specialDefence?.let { r -> (r.minimum + r.maximum) / 2 } ?: 0 }
                if (foes.isEmpty() || defence == 0 && special == 0) null
                else if (defence < special) mapOf("attack" to 1) else mapOf("special_attack" to 1)
            }
            else -> null
        }

    private fun projectField(
        state: BattleStateView,
        incoming: BattlePokemonStateView,
        ability: String?,
    ): BattleStateView {
        val requestedWeather = ENTRY_WEATHER[ability]
        val currentWeather = canonical(state.field.weather?.effectId)
        val weather = requestedWeather?.takeUnless {
            currentWeather in STRONG_WEATHERS && it !in STRONG_WEATHERS
        }
        val terrain = ENTRY_TERRAIN[ability]
        if (weather == null && terrain == null) return state
        val item = LocalPublicItemState.activeItemId(state, incoming)
        val weatherTurns = if (WEATHER_EXTENDERS[weather] == item) EXTENDED_FIELD_TURNS else ENTRY_FIELD_TURNS
        val terrainTurns = if (item == TERRAIN_EXTENDER) EXTENDED_FIELD_TURNS else ENTRY_FIELD_TURNS
        val field = BattleFieldStateView(
            weather = weather?.let { BattleTimedEffectView(it, weatherTurns) } ?: state.field.weather,
            terrain = terrain?.let { BattleTimedEffectView(it, terrainTurns) } ?: state.field.terrain,
            roomEffects = state.field.roomEffects,
            globalEffects = state.field.globalEffects,
            sideConditions = state.field.sideConditions,
        )
        return state.derive(
            field = field,
        )
    }

    private fun canonical(value: String?): String? = value?.let(PublicIds::canonical)

    private val INTIMIDATE_IMMUNITIES = setOf(
        "clearbody",
        "fullmetalbody",
        "hypercutter",
        "innerfocus",
        "oblivious",
        "owntempo",
        "scrappy",
        "whitesmoke",
    )
    private val ENTRY_WEATHER = mapOf(
        "drizzle" to "raindance",
        "drought" to "sunnyday",
        "sandstream" to "sandstorm",
        "snowwarning" to "snow",
        "orichalcumpulse" to "sunnyday",
    )
    private val ENTRY_TERRAIN = mapOf(
        "electricsurge" to "electricterrain",
        "grassysurge" to "grassyterrain",
        "mistysurge" to "mistyterrain",
        "psychicsurge" to "psychicterrain",
        "hadronengine" to "electricterrain",
    )
    private const val ENTRY_FIELD_TURNS = 5
    private const val EXTENDED_FIELD_TURNS = 8
    private const val TERRAIN_EXTENDER = "terrainextender"
    private val STRONG_WEATHERS = setOf("desolateland", "primordialsea", "deltastream")
    private val WEATHER_EXTENDERS = mapOf(
        "raindance" to "damprock",
        "sunnyday" to "heatrock",
        "sandstorm" to "smoothrock",
        "snow" to "icyrock",
    )
}
