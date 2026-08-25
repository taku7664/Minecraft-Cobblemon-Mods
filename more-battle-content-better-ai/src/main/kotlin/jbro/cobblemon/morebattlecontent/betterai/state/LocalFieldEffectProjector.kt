package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalSideConditionRules
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState

/** Projects declarative public field effects whose duration rules are stable in Gen 9. */
internal object LocalFieldEffectProjector {
    fun apply(
        state: BattleStateView,
        actingSide: BattleSide,
        effect: BattleMoveEffectView,
        actorPokemonId: UUID? = null,
    ): BattleStateView = when (effect.kind) {
        BattleMoveEffectKind.SIDE_CONDITION -> applySideCondition(state, actingSide, effect, actorPokemonId)
        BattleMoveEffectKind.WEATHER -> applyWeather(state, effect, actorPokemonId)
        BattleMoveEffectKind.TERRAIN -> applyTerrain(state, effect, actorPokemonId)
        BattleMoveEffectKind.FIELD_CONDITION -> applyFieldCondition(state, effect)
        else -> state
    }

    private fun applySideCondition(
        state: BattleStateView,
        actingSide: BattleSide,
        effect: BattleMoveEffectView,
        actorPokemonId: UUID?,
    ): BattleStateView {
        val effectId = effect.valueId ?: return state
        val canonical = LocalSideConditionRules.canonical(effectId)
        if (!LocalSideConditionRules.isSupported(effectId)) return state
        val targetSide = when (effect.target) {
            BattleMoveEffectTarget.USER_SIDE -> actingSide
            BattleMoveEffectTarget.TARGET_SIDE -> opposite(actingSide)
            else -> return state
        }
        val current = state.field.sideConditions.getValue(targetSide)
        val existing = current.firstOrNull { LocalSideConditionRules.canonical(it.effectId) == canonical }
        val maximumStacks = LocalSideConditionRules.maximumStacks(effectId, effect.amountRange?.maximum)
        val existingStacks = existing?.stacks ?: if (existing == null) 0 else 1
        if (maximumStacks != null && existingStacks >= maximumStacks) return state
        val nextEffect = if (maximumStacks != null) {
            BattleTimedEffectView(
                effectId = effectId,
                remainingTurns = null,
                stacks = (existingStacks + 1).coerceAtMost(maximumStacks),
            )
        } else {
            durationEffect(
                effectId,
                sideDuration(canonical, state.pokemon.firstOrNull { it.battlePokemonId == actorPokemonId }),
            ) ?: return state
        }
        val nextConditions = state.field.sideConditions.toMutableMap().also { bySide ->
            bySide[targetSide] = current.filterNot {
                LocalSideConditionRules.canonical(it.effectId) == canonical
            } + nextEffect
        }
        return copyState(
            state,
            BattleFieldStateView(
                weather = state.field.weather,
                terrain = state.field.terrain,
                roomEffects = state.field.roomEffects,
                globalEffects = state.field.globalEffects,
                sideConditions = nextConditions,
            ),
        )
    }

    private fun applyWeather(
        state: BattleStateView,
        effect: BattleMoveEffectView,
        actorPokemonId: UUID?,
    ): BattleStateView {
        val effectId = effect.valueId ?: return state
        val canonical = LocalSideConditionRules.canonical(effectId)
        if (canonical !in WEATHER_IDS) return state
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorPokemonId }
        val duration = extendableDuration(actor, weatherExtender(canonical), 5, 8)
        return copyState(state, copyField(state.field, weather = durationEffect(effectId, duration)))
    }

    private fun applyTerrain(
        state: BattleStateView,
        effect: BattleMoveEffectView,
        actorPokemonId: UUID?,
    ): BattleStateView {
        val effectId = effect.valueId ?: return state
        val canonical = LocalSideConditionRules.canonical(effectId)
        if (canonical !in TERRAIN_IDS) return state
        val actor = state.pokemon.firstOrNull { it.battlePokemonId == actorPokemonId }
        val duration = extendableDuration(actor, "terrainextender", 5, 8)
        return copyState(state, copyField(state.field, terrain = durationEffect(effectId, duration)))
    }

    private fun applyFieldCondition(
        state: BattleStateView,
        effect: BattleMoveEffectView,
    ): BattleStateView {
        val effectId = effect.valueId ?: return state
        val canonical = LocalSideConditionRules.canonical(effectId)
        val duration = FIELD_DURATIONS[canonical] ?: return state
        val currentField = state.field
        return if (canonical in ROOM_IDS) {
            val active = currentField.roomEffects.any { LocalSideConditionRules.canonical(it.effectId) == canonical }
            val rooms = currentField.roomEffects.filterNot {
                LocalSideConditionRules.canonical(it.effectId) == canonical
            }.let { if (active) it else it + requireNotNull(durationEffect(effectId, duration)) }
            copyState(state, copyField(currentField, roomEffects = rooms))
        } else {
            val globals = currentField.globalEffects.filterNot {
                LocalSideConditionRules.canonical(it.effectId) == canonical
            } + requireNotNull(durationEffect(effectId, duration))
            copyState(state, copyField(currentField, globalEffects = globals))
        }
    }

    private fun sideDuration(canonical: String, actor: BattlePokemonStateView?): BattleIntegerRange? = when (canonical) {
        "reflect", "lightscreen", "auroraveil" -> extendableDuration(actor, "lightclay", 5, 8)
        "tailwind" -> knownOrRangedDuration(actor, 4, 6)
        "safeguard" -> knownOrRangedDuration(actor, 5, 7)
        "mist", "luckychant" -> BattleIntegerRange(5, 5)
        "firepledge", "grasspledge", "waterpledge",
        "gmaxcannonade", "gmaxvinelash", "gmaxvolcalith", "gmaxwildfire" -> BattleIntegerRange(4, 4)
        "craftyshield", "matblock", "quickguard", "wideguard" -> BattleIntegerRange(1, 1)
        else -> LocalSideConditionRules.fixedDuration(canonical)?.let { BattleIntegerRange(it, it) }
    }

    private fun extendableDuration(
        actor: BattlePokemonStateView?,
        extenderItem: String?,
        baseTurns: Int,
        extendedTurns: Int,
    ): BattleIntegerRange {
        val heldItem = canonical(actor?.knownHeldItemId)
        return when {
            extenderItem != null && heldItem == extenderItem -> BattleIntegerRange(extendedTurns, extendedTurns)
            heldItem != null || actor?.combatStats?.knowledge == BattleCombatStatKnowledge.EXACT_OWN ->
                BattleIntegerRange(baseTurns, baseTurns)
            else -> BattleIntegerRange(baseTurns, extendedTurns)
        }
    }

    private fun knownOrRangedDuration(
        actor: BattlePokemonStateView?,
        baseTurns: Int,
        publicMaximum: Int,
    ): BattleIntegerRange = if (
        actor?.knownHeldItemId != null || actor?.combatStats?.knowledge == BattleCombatStatKnowledge.EXACT_OWN
    ) {
        BattleIntegerRange(baseTurns, baseTurns)
    } else {
        BattleIntegerRange(baseTurns, publicMaximum)
    }

    private fun durationEffect(effectId: String, duration: BattleIntegerRange?): BattleTimedEffectView? = when {
        duration == null -> null
        duration.minimum == duration.maximum -> BattleTimedEffectView(effectId, duration.minimum)
        else -> BattleTimedEffectView(effectId, null, remainingTurnsRange = duration)
    }

    private fun weatherExtender(weatherId: String): String? = when (weatherId) {
        "raindance", "rain" -> "damprock"
        "sunnyday", "sun" -> "heatrock"
        "sandstorm", "sand" -> "smoothrock"
        "hail", "snow" -> "icyrock"
        else -> null
    }

    private fun copyField(
        field: BattleFieldStateView,
        weather: BattleTimedEffectView? = field.weather,
        terrain: BattleTimedEffectView? = field.terrain,
        roomEffects: List<BattleTimedEffectView> = field.roomEffects,
        globalEffects: List<BattleTimedEffectView> = field.globalEffects,
    ) = BattleFieldStateView(
        weather = weather,
        terrain = terrain,
        roomEffects = roomEffects,
        globalEffects = globalEffects,
        sideConditions = field.sideConditions,
    )

    private fun copyState(state: BattleStateView, field: BattleFieldStateView) = BattleStateView(
        battleId = state.battleId,
        format = state.format,
        turn = state.turn,
        pokemon = state.pokemon,
        field = field,
        remainingPokemonBySide = state.remainingPokemonBySide,
        observedEvents = state.observedEvents,
        inferences = state.inferences,
    )

    private fun canonical(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private fun opposite(side: BattleSide) = if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY

    private val WEATHER_IDS = setOf("raindance", "rain", "sunnyday", "sun", "sandstorm", "sand", "hail", "snow")
    private val TERRAIN_IDS = setOf("electricterrain", "grassyterrain", "mistyterrain", "psychicterrain")
    private val ROOM_IDS = setOf("trickroom", "wonderroom", "magicroom")
    private val FIELD_DURATIONS = mapOf(
        "trickroom" to BattleIntegerRange(5, 7),
        "wonderroom" to BattleIntegerRange(5, 7),
        "magicroom" to BattleIntegerRange(5, 7),
        "gravity" to BattleIntegerRange(5, 7),
        "fairylock" to BattleIntegerRange(2, 2),
        "iondeluge" to BattleIntegerRange(1, 1),
        "mudsport" to BattleIntegerRange(5, 5),
        "watersport" to BattleIntegerRange(5, 5),
    )
}
