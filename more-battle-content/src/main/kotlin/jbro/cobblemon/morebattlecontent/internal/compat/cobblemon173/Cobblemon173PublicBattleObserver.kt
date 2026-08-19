package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeView
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import jbro.cobblemon.morebattlecontent.internal.ai.PublicBattleInferenceEngine
import jbro.cobblemon.morebattlecontent.internal.ai.PublicSpeciesInferenceKnowledge

/**
 * Stateful knowledge store that accepts only sanitized, publicly observable battle events.
 * Hidden moves, abilities and held items cannot enter through [Cobblemon173PublicPokemonSnapshot].
 */
internal class Cobblemon173PublicBattleObserver(
    private val initialOpponentPokemonCount: Int,
    private val maximumRecentEvents: Int = DEFAULT_MAXIMUM_RECENT_EVENTS,
) {
    private val pokemon = linkedMapOf<UUID, BattlePokemonStateView>()
    private val events = ArrayDeque<BattleObservedEventView>()
    private val faintedOpponents = linkedSetOf<UUID>()
    private var sequence = 0L
    private var currentTurn = 0
    private var weather: TrackedTimedEffect? = null
    private var terrain: TrackedTimedEffect? = null
    private val roomEffects = linkedMapOf<String, TrackedTimedEffect>()
    private val globalEffects = linkedMapOf<String, TrackedTimedEffect>()
    private val sideConditions = BattleSide.entries.associateWith {
        linkedMapOf<String, TrackedTimedEffect>()
    }
    private var activeActionWindow: ActiveActionWindow? = null

    init {
        require(initialOpponentPokemonCount > 0)
        require(maximumRecentEvents > 0)
    }

    @Synchronized
    fun observe(observation: Cobblemon173PublicObservation) {
        advanceTurn(observation.turn)
        when (observation) {
            is Cobblemon173PublicObservation.PokemonPresented -> {
                closeActionWindow()
                upsert(observation.pokemon, refreshPublicIdentity = true)
                appendEvent(
                    turn = observation.turn,
                    kind = BattleObservedEventKind.SWITCHED,
                    actor = observation.pokemon.battlePokemonId,
                )
            }

            is Cobblemon173PublicObservation.MoveUsed -> {
                val actor = upsert(observation.actor)
                observation.targets.forEach(::upsert)
                pokemon[actor.battlePokemonId] = actor.withKnownMove(observation.moveId)
                val actionSequence = appendEvent(
                    turn = observation.turn,
                    kind = BattleObservedEventKind.ACTION_ORDER,
                    actor = actor.battlePokemonId,
                    publicValueId = observation.moveId,
                    baseMovePriority = observation.baseMovePriority,
                )
                appendEvent(
                    turn = observation.turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actor = actor.battlePokemonId,
                    targets = observation.targets.map { it.battlePokemonId },
                    publicValueId = observation.moveId,
                )
                activeActionWindow = ActiveActionWindow(
                    turn = observation.turn,
                    actionSequence = actionSequence,
                    actorPokemonId = actor.battlePokemonId,
                    moveId = observation.moveId,
                    targetPokemonIds = observation.targets.mapTo(linkedSetOf()) { it.battlePokemonId },
                )
                if (observation.missed) {
                    appendMoveOutcome(
                        Cobblemon173PublicObservation.MoveOutcome(
                            turn = observation.turn,
                            outcome = BattleMoveOutcomeView(
                                BattleMoveOutcomeKind.MISSED,
                                moveId = observation.moveId,
                            ),
                            source = observation.actor,
                            targets = observation.targets,
                        ),
                    )
                }
            }

            is Cobblemon173PublicObservation.MoveOutcome -> appendMoveOutcome(observation)

            is Cobblemon173PublicObservation.AbilityRevealed -> {
                val actor = knownOrUpsert(observation.pokemon)
                pokemon[actor.battlePokemonId] = actor.withKnownAbility(observation.abilityId)
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.ABILITY_REVEALED,
                    actor.battlePokemonId,
                    publicValueId = observation.abilityId,
                )
            }

            is Cobblemon173PublicObservation.HeldItemRevealed -> {
                val actor = knownOrUpsert(observation.pokemon)
                pokemon[actor.battlePokemonId] = actor.withKnownHeldItem(observation.itemId)
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.HELD_ITEM_REVEALED,
                    actor.battlePokemonId,
                    publicValueId = observation.itemId,
                )
            }

            is Cobblemon173PublicObservation.HpChanged -> {
                val previous = pokemon[observation.pokemon.battlePokemonId]
                val current = upsert(observation.pokemon)
                val hpFractionDelta = previous?.let { current.hpFraction - it.hpFraction }
                val precedingAction = activeActionWindow?.takeIf {
                    observation.allowPrecedingActionLink &&
                        observation.publicSourceEffectId == null &&
                        hpFractionDelta != null && hpFractionDelta < 0.0 &&
                        it.turn == observation.turn && current.battlePokemonId in it.targetPokemonIds
                }
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.HP_CHANGED,
                    current.battlePokemonId,
                    hpFractionDelta = hpFractionDelta,
                    precedingActionSequence = precedingAction?.actionSequence,
                    precedingActionActorPokemonId = precedingAction?.actorPokemonId,
                    precedingActionMoveId = precedingAction?.moveId,
                    publicSourceEffectId = observation.publicSourceEffectId,
                )
            }

            is Cobblemon173PublicObservation.StatusChanged -> {
                val current = upsert(observation.pokemon)
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.STATUS_CHANGED,
                    current.battlePokemonId,
                    publicValueId = current.statusId,
                )
            }

            is Cobblemon173PublicObservation.Fainted -> {
                val current = upsert(observation.pokemon.copy(hpFraction = 0.0, fainted = true))
                if (current.side == BattleSide.OPPONENT) faintedOpponents += current.battlePokemonId
                appendEvent(observation.turn, BattleObservedEventKind.FAINTED, current.battlePokemonId)
            }

            is Cobblemon173PublicObservation.WeatherChanged -> {
                val previousWeatherId = weather?.effectId
                weather = when {
                    observation.weatherId == null -> null
                    observation.upkeep && previousWeatherId == observation.weatherId -> weather
                    observation.upkeep -> TrackedTimedEffect.unknown(observation.weatherId, observation.turn)
                    else -> TrackedTimedEffect.started(
                        observation.weatherId,
                        observation.turn,
                        observation.durationTurns,
                    )
                }
                if (!observation.upkeep || previousWeatherId != observation.weatherId) {
                    appendEvent(
                        observation.turn,
                        BattleObservedEventKind.FIELD_EFFECT_CHANGED,
                        publicValueId = observation.weatherId,
                    )
                }
            }

            is Cobblemon173PublicObservation.FieldEffectChanged -> {
                setFieldEffect(
                    observation.effectId,
                    observation.scope,
                    observation.active,
                    observation.turn,
                    observation.durationTurns,
                )
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.FIELD_EFFECT_CHANGED,
                    publicValueId = observation.effectId,
                )
            }

            is Cobblemon173PublicObservation.SideConditionChanged -> {
                val conditions = sideConditions.getValue(observation.side)
                if (observation.active) {
                    val maximumStacks = STACKABLE_SIDE_CONDITIONS[observation.effectId]
                    val stacks = maximumStacks?.let {
                        ((conditions[observation.effectId]?.stacks ?: 0) + 1).coerceAtMost(it)
                    }
                    conditions[observation.effectId] = TrackedTimedEffect.started(
                        effectId = observation.effectId,
                        turn = observation.turn,
                        durationTurns = observation.durationTurns,
                        stacks = stacks,
                    )
                } else {
                    conditions.remove(observation.effectId)
                }
                appendEvent(
                    observation.turn,
                    BattleObservedEventKind.FIELD_EFFECT_CHANGED,
                    publicValueId = observation.effectId,
                )
            }
        }
    }

    @Synchronized
    fun advanceTurn(turn: Int) {
        require(turn >= 0)
        currentTurn = maxOf(currentTurn, turn)
    }

    @Synchronized
    fun publicSnapshot(): Cobblemon173PublicBattleSnapshot = Cobblemon173PublicBattleSnapshot(
        pokemon = pokemon.values.sortedBy { it.battlePokemonId.toString() },
        field = BattleFieldStateView(
            weather = weather?.toView(currentTurn),
            terrain = terrain?.toView(currentTurn),
            roomEffects = roomEffects.values.map { it.toView(currentTurn) },
            globalEffects = globalEffects.values.map { it.toView(currentTurn) },
            sideConditions = sideConditions.mapValues { (_, effects) ->
                effects.values.map { it.toView(currentTurn) }
            },
        ),
        events = events.toList(),
        remainingOpponentPokemon = (initialOpponentPokemonCount - faintedOpponents.size).coerceAtLeast(0),
    )

    @Synchronized
    fun reset() {
        pokemon.clear()
        events.clear()
        faintedOpponents.clear()
        sequence = 0
        currentTurn = 0
        weather = null
        terrain = null
        roomEffects.clear()
        globalEffects.clear()
        sideConditions.values.forEach(MutableMap<String, TrackedTimedEffect>::clear)
        activeActionWindow = null
    }

    @Synchronized
    fun closeActionWindow() {
        activeActionWindow = null
    }

    private fun upsert(
        snapshot: Cobblemon173PublicPokemonSnapshot,
        refreshPublicIdentity: Boolean = false,
    ): BattlePokemonStateView {
        if (snapshot.activeSlot != null) {
            pokemon.replaceAll { id, current ->
                if (
                    id != snapshot.battlePokemonId &&
                    current.side == snapshot.side &&
                    current.activeSlot == snapshot.activeSlot
                ) {
                    current.withActiveSlot(null)
                } else {
                    current
                }
            }
        }
        val previous = pokemon[snapshot.battlePokemonId]
        return snapshot.toView(previous, refreshPublicIdentity).also { pokemon[it.battlePokemonId] = it }
    }

    private fun knownOrUpsert(snapshot: Cobblemon173PublicPokemonSnapshot): BattlePokemonStateView =
        pokemon[snapshot.battlePokemonId] ?: upsert(snapshot)

    private fun setFieldEffect(
        effectId: String,
        scope: FieldEffectScope,
        active: Boolean,
        turn: Int,
        durationTurns: BattleIntegerRange?,
    ) {
        when (scope) {
            FieldEffectScope.TERRAIN -> terrain = TrackedTimedEffect.started(
                effectId,
                turn,
                durationTurns,
            ).takeIf { active }
            FieldEffectScope.ROOM -> if (active) {
                roomEffects[effectId] = TrackedTimedEffect.started(effectId, turn, durationTurns)
            } else {
                roomEffects.remove(effectId)
            }

            FieldEffectScope.GLOBAL -> if (active) {
                globalEffects[effectId] = TrackedTimedEffect.started(effectId, turn, durationTurns)
            } else {
                globalEffects.remove(effectId)
            }
        }
    }

    private fun appendMoveOutcome(observation: Cobblemon173PublicObservation.MoveOutcome) {
        val source = observation.source?.let(::knownOrUpsert)
        val targets = observation.targets.map(::knownOrUpsert)
        val targetIds = targets.map { it.battlePokemonId }
        val previous = events.lastOrNull()
        val duplicateMiss = observation.outcome.kind == BattleMoveOutcomeKind.MISSED &&
            previous?.kind == BattleObservedEventKind.MOVE_OUTCOME &&
            previous.turn == observation.turn &&
            previous.actorPokemonId == source?.battlePokemonId &&
            previous.targetPokemonIds == targetIds &&
            previous.moveOutcome?.kind == BattleMoveOutcomeKind.MISSED &&
            previous.moveOutcome.publicEffectId == observation.outcome.publicEffectId &&
            previous.moveOutcome.hitCount == observation.outcome.hitCount &&
            (previous.moveOutcome.moveId == observation.outcome.moveId || observation.outcome.moveId == null)
        if (duplicateMiss) return
        appendEvent(
            turn = observation.turn,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            actor = source?.battlePokemonId,
            targets = targetIds,
            moveOutcome = observation.outcome,
        )
    }

    private fun appendEvent(
        turn: Int,
        kind: BattleObservedEventKind,
        actor: UUID? = null,
        targets: List<UUID> = emptyList(),
        publicValueId: String? = null,
        hpFractionDelta: Double? = null,
        baseMovePriority: Int? = null,
        precedingActionSequence: Long? = null,
        precedingActionActorPokemonId: UUID? = null,
        precedingActionMoveId: String? = null,
        publicSourceEffectId: String? = null,
        moveOutcome: BattleMoveOutcomeView? = null,
    ): Long {
        val event = BattleObservedEventView(
            sequence = ++sequence,
            turn = turn,
            kind = kind,
            actorPokemonId = actor,
            targetPokemonIds = targets,
            publicValueId = publicValueId,
            hpFractionDelta = hpFractionDelta,
            baseMovePriority = baseMovePriority,
            precedingActionSequence = precedingActionSequence,
            precedingActionActorPokemonId = precedingActionActorPokemonId,
            precedingActionMoveId = precedingActionMoveId,
            publicSourceEffectId = publicSourceEffectId,
            moveOutcome = moveOutcome,
        )
        events += event
        while (events.size > maximumRecentEvents) events.removeFirst()
        return event.sequence
    }

    private companion object {
        const val DEFAULT_MAXIMUM_RECENT_EVENTS = 128
        val STACKABLE_SIDE_CONDITIONS = mapOf("spikes" to 3, "toxicspikes" to 2)
    }

    private data class ActiveActionWindow(
        val turn: Int,
        val actionSequence: Long,
        val actorPokemonId: UUID,
        val moveId: String,
        val targetPokemonIds: Set<UUID>,
    )

    private data class TrackedTimedEffect(
        val effectId: String,
        val activationTurn: Int,
        val durationTurns: BattleIntegerRange?,
        val stacks: Int? = null,
    ) {
        fun toView(turn: Int): BattleTimedEffectView {
            val duration = durationTurns ?: return BattleTimedEffectView(effectId, null, stacks)
            val elapsedTurns = (turn - activationTurn).coerceAtLeast(0)
            val maximum = duration.maximum - elapsedTurns
            if (maximum <= 0) return BattleTimedEffectView(effectId, null, stacks)
            val minimum = (duration.minimum - elapsedTurns).coerceAtLeast(1)
            return if (minimum == maximum) {
                BattleTimedEffectView(effectId, minimum, stacks)
            } else {
                BattleTimedEffectView(
                    effectId = effectId,
                    remainingTurns = null,
                    stacks = stacks,
                    remainingTurnsRange = BattleIntegerRange(minimum, maximum),
                )
            }
        }

        companion object {
            fun started(
                effectId: String,
                turn: Int,
                durationTurns: BattleIntegerRange?,
                stacks: Int? = null,
            ) = TrackedTimedEffect(
                effectId = effectId,
                activationTurn = maxOf(turn, 1),
                durationTurns = durationTurns,
                stacks = stacks,
            )

            fun unknown(effectId: String, turn: Int) = started(effectId, turn, null)
        }
    }
}

internal data class Cobblemon173PublicPokemonSnapshot(
    val battlePokemonId: UUID,
    val side: BattleSide,
    val activeSlot: Int?,
    val speciesId: String,
    val formId: String?,
    val level: Int?,
    val hpFraction: Double,
    val statusId: String?,
    val statStages: Map<String, Int>,
    val fainted: Boolean,
    val knownTypeIds: Set<String> = emptySet(),
    val combatStats: BattleCombatStatRangesView? = null,
) {
    init {
        require(activeSlot == null || activeSlot >= 0)
        require(speciesId.isNotBlank())
        require(level == null || level > 0)
        require(hpFraction in 0.0..1.0)
    }

    fun toView(
        previous: BattlePokemonStateView?,
        refreshPublicIdentity: Boolean,
    ): BattlePokemonStateView = BattlePokemonStateView(
        battlePokemonId = battlePokemonId,
        side = side,
        activeSlot = activeSlot,
        speciesId = previous?.speciesId?.takeUnless { refreshPublicIdentity } ?: speciesId,
        formId = if (previous != null && !refreshPublicIdentity) previous.formId else formId,
        level = if (previous != null && !refreshPublicIdentity) previous.level else level,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = previous?.knownMoveIds.orEmpty(),
        knownAbilityId = previous?.knownAbilityId,
        knownHeldItemId = previous?.knownHeldItemId,
        fainted = fainted,
        knownTypeIds = if (previous != null && !refreshPublicIdentity) previous.knownTypeIds else knownTypeIds,
        combatStats = if (previous != null && !refreshPublicIdentity) previous.combatStats else combatStats,
    )
}

internal sealed interface Cobblemon173PublicObservation {
    val turn: Int

    data class PokemonPresented(override val turn: Int, val pokemon: Cobblemon173PublicPokemonSnapshot) :
        Cobblemon173PublicObservation

    data class MoveUsed(
        override val turn: Int,
        val actor: Cobblemon173PublicPokemonSnapshot,
        val moveId: String,
        val targets: List<Cobblemon173PublicPokemonSnapshot>,
        val baseMovePriority: Int? = null,
        val missed: Boolean = false,
    ) : Cobblemon173PublicObservation {
        init {
            require(moveId.isNotBlank())
        }
    }

    data class MoveOutcome(
        override val turn: Int,
        val outcome: BattleMoveOutcomeView,
        val source: Cobblemon173PublicPokemonSnapshot? = null,
        val targets: List<Cobblemon173PublicPokemonSnapshot> = emptyList(),
    ) : Cobblemon173PublicObservation {
        init {
            require(targets.map { it.battlePokemonId }.distinct().size == targets.size)
        }
    }

    data class AbilityRevealed(
        override val turn: Int,
        val pokemon: Cobblemon173PublicPokemonSnapshot,
        val abilityId: String,
    ) : Cobblemon173PublicObservation {
        init {
            require(abilityId.isNotBlank())
        }
    }

    data class HeldItemRevealed(
        override val turn: Int,
        val pokemon: Cobblemon173PublicPokemonSnapshot,
        val itemId: String,
    ) : Cobblemon173PublicObservation {
        init {
            require(itemId.isNotBlank())
        }
    }

    data class HpChanged(
        override val turn: Int,
        val pokemon: Cobblemon173PublicPokemonSnapshot,
        val allowPrecedingActionLink: Boolean = false,
        val publicSourceEffectId: String? = null,
    ) : Cobblemon173PublicObservation {
        init {
            require(publicSourceEffectId == null || publicSourceEffectId.isNotBlank())
        }
    }

    data class StatusChanged(override val turn: Int, val pokemon: Cobblemon173PublicPokemonSnapshot) :
        Cobblemon173PublicObservation

    data class Fainted(override val turn: Int, val pokemon: Cobblemon173PublicPokemonSnapshot) :
        Cobblemon173PublicObservation

    data class WeatherChanged(
        override val turn: Int,
        val weatherId: String?,
        val durationTurns: BattleIntegerRange? = null,
        val upkeep: Boolean = false,
    ) : Cobblemon173PublicObservation {
        init {
            require(weatherId == null || weatherId.isNotBlank())
            require(!upkeep || weatherId != null)
        }
    }

    data class FieldEffectChanged(
        override val turn: Int,
        val effectId: String,
        val scope: FieldEffectScope,
        val active: Boolean,
        val durationTurns: BattleIntegerRange? = null,
    ) : Cobblemon173PublicObservation {
        init {
            require(effectId.isNotBlank())
        }
    }

    data class SideConditionChanged(
        override val turn: Int,
        val side: BattleSide,
        val effectId: String,
        val active: Boolean,
        val durationTurns: BattleIntegerRange? = null,
    ) : Cobblemon173PublicObservation {
        init {
            require(effectId.isNotBlank())
        }
    }
}

internal enum class FieldEffectScope { TERRAIN, ROOM, GLOBAL }

internal class Cobblemon173PublicBattleSnapshot(
    pokemon: List<BattlePokemonStateView>,
    val field: BattleFieldStateView,
    events: List<BattleObservedEventView>,
    val remainingOpponentPokemon: Int,
) {
    val pokemon = pokemon.toList()
    val events = events.toList()

    init {
        require(remainingOpponentPokemon >= 0)
    }
}

internal object Cobblemon173BattleStateAssembler {
    fun assemble(
        battleId: UUID,
        format: BattleFormat,
        turn: Int,
        ownPokemon: List<BattlePokemonStateView>,
        publicSnapshot: Cobblemon173PublicBattleSnapshot,
        inferenceKnowledge: PublicSpeciesInferenceKnowledge = Cobblemon173PublicSpeciesInferenceKnowledge,
    ): BattleStateView {
        require(ownPokemon.all { it.side == BattleSide.ALLY })
        val opponents = publicSnapshot.pokemon.filter { it.side == BattleSide.OPPONENT }
        val pokemon = ownPokemon + opponents
        return BattleStateView(
            battleId = battleId,
            format = format,
            turn = turn,
            pokemon = pokemon,
            field = publicSnapshot.field,
            remainingPokemonBySide = mapOf(
                BattleSide.ALLY to ownPokemon.count { !it.fainted },
                BattleSide.OPPONENT to publicSnapshot.remainingOpponentPokemon,
            ),
            observedEvents = publicSnapshot.events,
            inferences = PublicBattleInferenceEngine.infer(pokemon, inferenceKnowledge, publicSnapshot.events),
        )
    }
}

private fun BattlePokemonStateView.withKnownMove(moveId: String) = copyView(knownMoveIds = knownMoveIds + moveId)

private fun BattlePokemonStateView.withKnownAbility(abilityId: String) = copyView(knownAbilityId = abilityId)

private fun BattlePokemonStateView.withKnownHeldItem(itemId: String) = copyView(knownHeldItemId = itemId)

private fun BattlePokemonStateView.withActiveSlot(slot: Int?) = copyView(activeSlot = slot)

private fun BattlePokemonStateView.copyView(
    activeSlot: Int? = this.activeSlot,
    knownMoveIds: Set<String> = this.knownMoveIds,
    knownAbilityId: String? = this.knownAbilityId,
    knownHeldItemId: String? = this.knownHeldItemId,
) = BattlePokemonStateView(
    battlePokemonId = battlePokemonId,
    side = side,
    activeSlot = activeSlot,
    speciesId = speciesId,
    formId = formId,
    level = level,
    hpFraction = hpFraction,
    statusId = statusId,
    statStages = statStages,
    knownMoveIds = knownMoveIds,
    knownAbilityId = knownAbilityId,
    knownHeldItemId = knownHeldItemId,
    fainted = fainted,
    knownTypeIds = knownTypeIds,
    combatStats = combatStats,
)
