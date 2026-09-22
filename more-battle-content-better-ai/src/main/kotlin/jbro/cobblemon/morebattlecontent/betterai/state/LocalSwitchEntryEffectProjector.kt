package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicItemState

/** Applies deterministic non-HP hazards to the public state after a switch. */
internal object LocalSwitchEntryEffectProjector {
    fun project(state: BattleStateView, incomingPokemonId: UUID): BattleStateView {
        val incoming = state.pokemon.firstOrNull {
            it.battlePokemonId == incomingPokemonId && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        if (!LocalPublicTurnOrder.grounded(state, incoming)) return state

        val hazards = state.field.sideConditions.getValue(incoming.side)
        val toxicSpikes = hazards.firstOrNull { canonical(it.effectId) == TOXIC_SPIKES }
        val types = incoming.knownTypeIds.mapTo(hashSetOf(), ::canonical)
        val item = LocalPublicItemState.activeItemId(state, incoming)
        var nextField = state.field
        var nextIncoming = incoming
        var reflectedTargetId: UUID? = null
        if (toxicSpikes != null && POISON in types) {
            nextField = copyField(
                state.field,
                state.field.sideConditions + (incoming.side to hazards.filterNot { canonical(it.effectId) == TOXIC_SPIKES }),
            )
        } else if (item != HEAVY_DUTY_BOOTS && toxicSpikes != null && incoming.statusId == null &&
            STEEL !in types && !statusBlockedByField(state, incoming) &&
            LocalPublicAbilityState.effectiveKnownAbility(state, incoming) !in POISON_IMMUNITY_ABILITIES
        ) {
            nextIncoming = copyPokemon(
                nextIncoming,
                statusId = if ((toxicSpikes.stacks ?: 1) >= 2) TOXIC else POISONED,
            )
        }

        if (item != HEAVY_DUTY_BOOTS && hazards.any { canonical(it.effectId) == STICKY_WEB }) {
            val ability = LocalPublicAbilityState.effectiveKnownAbility(state, nextIncoming)
            if (ability == MIRROR_ARMOR) {
                reflectedTargetId = state.pokemon.firstOrNull {
                    it.side != incoming.side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                }?.battlePokemonId
            } else if (item != CLEAR_AMULET) {
                nextIncoming = applyStickyWeb(nextIncoming, ability)
            }
        }
        if (nextIncoming == incoming && nextField === state.field && reflectedTargetId == null) return state
        return copyState(
            state,
            state.pokemon.map {
                when (it.battlePokemonId) {
                    incomingPokemonId -> nextIncoming
                    reflectedTargetId -> changeStage(it, SPEED, -1)
                    else -> it
                }
            },
            nextField,
        )
    }

    private fun statusBlockedByField(state: BattleStateView, pokemon: BattlePokemonStateView): Boolean {
        val mistyTerrain = canonical(state.field.terrain?.effectId) == MISTY_TERRAIN
        val safeguard = state.field.sideConditions.getValue(pokemon.side).any {
            val remaining = it.remainingTurns
            canonical(it.effectId) == SAFEGUARD && (remaining == null || remaining > 0)
        }
        return mistyTerrain && LocalPublicTurnOrder.grounded(state, pokemon) || safeguard
    }

    private fun applyStickyWeb(pokemon: BattlePokemonStateView, ability: String?): BattlePokemonStateView = when (ability) {
        in STAT_DROP_IMMUNITIES -> pokemon
        CONTRARY -> changeStage(pokemon, SPEED, 1)
        DEFIANT -> changeStage(changeStage(pokemon, SPEED, -1), ATTACK, 2)
        COMPETITIVE -> changeStage(changeStage(pokemon, SPEED, -1), SPECIAL_ATTACK, 2)
        else -> changeStage(pokemon, SPEED, -1)
    }

    private fun changeStage(pokemon: BattlePokemonStateView, stat: String, amount: Int): BattlePokemonStateView {
        val stages = pokemon.statStages.toMutableMap()
        val key = stages.keys.firstOrNull { canonical(it) in STAT_ALIASES.getValue(stat) } ?: stat
        stages[key] = ((stages[key] ?: 0) + amount).coerceIn(-6, 6)
        return copyPokemon(pokemon, statStages = stages)
    }

    private fun copyPokemon(
        pokemon: BattlePokemonStateView,
        statusId: String? = pokemon.statusId,
        statStages: Map<String, Int> = pokemon.statStages,
    ) = BattlePokemonStateView(
        pokemon.battlePokemonId, pokemon.side, pokemon.activeSlot, pokemon.speciesId, pokemon.formId,
        pokemon.level, pokemon.hpFraction, statusId, statStages, pokemon.knownMoveIds, pokemon.knownAbilityId,
        pokemon.knownHeldItemId, pokemon.fainted, pokemon.knownTypeIds, pokemon.combatStats,
        pokemon.knownFormStates, pokemon.actionConstraints, pokemon.knownVolatileEffectIds,
    )

    private fun copyField(field: BattleFieldStateView, sideConditions: Map<BattleSide, List<BattleTimedEffectView>>) =
        BattleFieldStateView(field.weather, field.terrain, field.roomEffects, field.globalEffects, sideConditions)

    private fun copyState(
        state: BattleStateView,
        pokemon: List<BattlePokemonStateView>,
        field: BattleFieldStateView,
    ) = BattleStateView(
        state.battleId, state.format, state.turn, pokemon, field, state.remainingPokemonBySide,
        state.observedEvents, state.inferences,
    )

    private fun canonical(value: String?): String = value.orEmpty().substringAfter(':')
        .lowercase().filter(Char::isLetterOrDigit)

    private const val HEAVY_DUTY_BOOTS = "heavydutyboots"
    private const val CLEAR_AMULET = "clearamulet"
    private const val STICKY_WEB = "stickyweb"
    private const val TOXIC_SPIKES = "toxicspikes"
    private const val POISON = "poison"
    private const val STEEL = "steel"
    private const val POISONED = "psn"
    private const val TOXIC = "tox"
    private const val SPEED = "speed"
    private const val ATTACK = "attack"
    private const val SPECIAL_ATTACK = "special_attack"
    private const val CONTRARY = "contrary"
    private const val DEFIANT = "defiant"
    private const val COMPETITIVE = "competitive"
    private const val MIRROR_ARMOR = "mirrorarmor"
    private const val MISTY_TERRAIN = "mistyterrain"
    private const val SAFEGUARD = "safeguard"
    private val POISON_IMMUNITY_ABILITIES = setOf("immunity", "pastelveil")
    private val STAT_DROP_IMMUNITIES = setOf("clearbody", "fullmetalbody", "whitesmoke")
    private val STAT_ALIASES = mapOf(
        SPEED to setOf("speed", "spe"),
        ATTACK to setOf("attack", "atk"),
        SPECIAL_ATTACK to setOf("specialattack", "spa"),
    )
}
