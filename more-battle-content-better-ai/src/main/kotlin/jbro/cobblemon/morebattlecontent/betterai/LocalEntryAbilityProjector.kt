package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

/** Applies deterministic, publicly known ability effects caused by entering battle. */
internal object LocalEntryAbilityProjector {
    fun project(state: BattleStateView, incomingPokemonId: UUID): BattleStateView {
        val incoming = state.pokemon.firstOrNull {
            it.battlePokemonId == incomingPokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        if (canonical(incoming.knownAbilityId) != "intimidate") return state

        var reflectedDrops = 0
        val next = state.pokemon.map { pokemon ->
            if (pokemon.side == incoming.side || pokemon.activeSlot == null || pokemon.fainted || pokemon.hpFraction <= 0.0) {
                return@map pokemon
            }
            when (canonical(pokemon.knownAbilityId)) {
                in INTIMIDATE_IMMUNITIES -> pokemon
                "mirrorarmor" -> {
                    reflectedDrops++
                    pokemon
                }
                "guarddog" -> changeStage(pokemon, "attack", 1)
                "defiant" -> changeStage(changeStage(pokemon, "attack", -1), "attack", 2)
                "competitive" -> changeStage(changeStage(pokemon, "attack", -1), "special_attack", 2)
                "rattled" -> changeStage(changeStage(pokemon, "attack", -1), "speed", 1)
                else -> changeStage(pokemon, "attack", -1)
            }
        }.map { pokemon ->
            if (pokemon.battlePokemonId == incomingPokemonId && reflectedDrops > 0) {
                changeStage(pokemon, "attack", -reflectedDrops)
            } else {
                pokemon
            }
        }
        return copyState(state, next)
    }

    private fun changeStage(pokemon: BattlePokemonStateView, stat: String, amount: Int): BattlePokemonStateView {
        val stages = pokemon.statStages.toMutableMap()
        val existingKey = stages.keys.firstOrNull { canonical(it) in STAT_ALIASES.getValue(stat) } ?: stat
        stages[existingKey] = ((stages[existingKey] ?: 0) + amount).coerceIn(-6, 6)
        return BattlePokemonStateView(
            battlePokemonId = pokemon.battlePokemonId,
            side = pokemon.side,
            activeSlot = pokemon.activeSlot,
            speciesId = pokemon.speciesId,
            formId = pokemon.formId,
            level = pokemon.level,
            hpFraction = pokemon.hpFraction,
            statusId = pokemon.statusId,
            statStages = stages,
            knownMoveIds = pokemon.knownMoveIds,
            knownAbilityId = pokemon.knownAbilityId,
            knownHeldItemId = pokemon.knownHeldItemId,
            fainted = pokemon.fainted,
            knownTypeIds = pokemon.knownTypeIds,
            combatStats = pokemon.combatStats,
            knownFormStates = pokemon.knownFormStates,
            actionConstraints = pokemon.actionConstraints,
        )
    }

    private fun copyState(state: BattleStateView, pokemon: List<BattlePokemonStateView>) = BattleStateView(
        battleId = state.battleId,
        format = state.format,
        turn = state.turn,
        pokemon = pokemon,
        field = state.field,
        remainingPokemonBySide = state.remainingPokemonBySide,
        observedEvents = state.observedEvents,
        inferences = state.inferences,
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

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
    private val STAT_ALIASES = mapOf(
        "attack" to setOf("attack", "atk"),
        "special_attack" to setOf("specialattack", "spa"),
        "speed" to setOf("speed", "spe"),
    )
}
