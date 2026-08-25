package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.IdentityHashMap
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalBadPoisonCounter
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStallingProtectionRules
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveControlEffectKind
import jbro.cobblemon.morebattlecontent.betterai.mechanics.RecursiveDelayedStrike
import jbro.cobblemon.morebattlecontent.betterai.mechanics.copyState
import kotlin.math.roundToInt

/**
 * Per-branch record of what each side has already been made to do.
 *
 * Extracted from the search file it used to live in. Two other files need these types, and while they
 * were nested inside the 2,565-line lookahead they had to reach back into it, which is what closed the
 * dependency cycle across the whole decision core.
 */
internal data class RecursiveActionHistory(
    val allySwitchedLastTurn: Boolean = false,
    val opponentSwitchedLastTurn: Boolean = false,
    val moveUses: Map<RecursiveMoveUseKey, Int> = emptyMap(),
    val rechargingPokemonIds: Set<UUID> = emptySet(),
    val chargingMoveByPokemon: Map<UUID, String> = emptyMap(),
    val tauntTurnsByPokemon: Map<UUID, Int> = emptyMap(),
    val encoreByPokemon: Map<UUID, RecursiveEncoreLock> = emptyMap(),
    val trappedByPokemon: Map<UUID, RecursiveTrapLock> = emptyMap(),
    val lastMoveByPokemon: Map<UUID, String> = emptyMap(),
    val moveStreakByPokemon: Map<UUID, RecursiveMoveStreak> = emptyMap(),
    val actedSinceEntryPokemonIds: Set<UUID> = emptySet(),
    val badPoisonTurnsByPokemon: Map<UUID, Int> = emptyMap(),
    val saltCuredPokemonIds: Set<UUID> = emptySet(),
    val protectionChainByPokemon: Map<UUID, Int> = emptyMap(),
    val delayedStrikes: List<RecursiveDelayedStrike> = emptyList(),
)

internal data class RecursiveMoveUseKey(val pokemonId: UUID, val moveId: String)

internal data class RecursiveEncoreLock(val moveId: String, val remainingTurns: Int)

internal data class RecursiveTrapLock(val sourcePokemonId: UUID, val remainingTurns: Int)

internal data class RecursiveMoveStreak(val moveId: String, val count: Int)

internal object RecursiveSnapshotActionConstraints {
    fun seed(
        state: BattleStateView,
        allySwitchedLastTurn: Boolean = false,
        allyLastMoveId: String? = null,
        allySameMoveRepeatCount: Int = 0,
    ): RecursiveActionHistory {
        val activeBySide = BattleSide.entries.associateWith { side ->
            state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
                .sortedBy { it.activeSlot }
        }
        val active = activeBySide.values.flatten()
        val publicMoveStreaks = active.mapNotNull { pokemon ->
            publicMoveStreak(state, pokemon.battlePokemonId)?.let { pokemon.battlePokemonId to it }
        }.toMap().toMutableMap()
        val ally = activeBySide.getValue(BattleSide.ALLY).singleOrNull()
        if (ally != null && allyLastMoveId != null && allySameMoveRepeatCount > 0) {
            publicMoveStreaks[ally.battlePokemonId] = RecursiveMoveStreak(
                allyLastMoveId,
                allySameMoveRepeatCount,
            )
        }
        val publicProtectionChains = active.mapNotNull { pokemon ->
            LocalStallingProtectionRules.consecutiveSuccessfulUses(state, pokemon.side, pokemon.activeSlot)
                .takeIf { it > 0 }
                ?.let { pokemon.battlePokemonId to it }
        }.toMap()
        return RecursiveActionHistory(
            allySwitchedLastTurn = allySwitchedLastTurn,
            rechargingPokemonIds = active.filter { it.actionConstraints.mustRecharge }
                .mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId),
            tauntTurnsByPokemon = active.filter { it.actionConstraints.taunted }
                .associate { it.battlePokemonId to MAXIMUM_SNAPSHOT_CONTROL_TURNS },
            encoreByPokemon = active.mapNotNull { pokemon ->
                pokemon.actionConstraints.encoreMoveId?.let { moveId ->
                    pokemon.battlePokemonId to RecursiveEncoreLock(moveId, MAXIMUM_SNAPSHOT_CONTROL_TURNS)
                }
            }.toMap(),
            trappedByPokemon = active.mapNotNull { pokemon ->
                if (!pokemon.actionConstraints.trapped) return@mapNotNull null
                val source = activeBySide.getValue(
                    if (pokemon.side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY,
                ).firstOrNull() ?: return@mapNotNull null
                pokemon.battlePokemonId to RecursiveTrapLock(source.battlePokemonId, MAXIMUM_SNAPSHOT_TRAP_TURNS)
            }.toMap(),
            lastMoveByPokemon = active.mapNotNull { pokemon ->
                pokemon.actionConstraints.encoreMoveId?.let { pokemon.battlePokemonId to it }
            }.toMap() + publicMoveStreaks.mapValues { it.value.moveId },
            moveStreakByPokemon = publicMoveStreaks,
            badPoisonTurnsByPokemon = LocalBadPoisonCounter.seed(state),
            protectionChainByPokemon = publicProtectionChains,
        )
    }

    private fun publicMoveStreak(state: BattleStateView, pokemonId: UUID): RecursiveMoveStreak? {
        val lastEntrySequence = state.observedEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == pokemonId }
            .maxOfOrNull { it.sequence }
            ?: Long.MIN_VALUE
        val moves = state.observedEvents.asSequence()
            .filter {
                it.sequence > lastEntrySequence &&
                    it.kind == BattleObservedEventKind.MOVE_USED &&
                    it.actorPokemonId == pokemonId
            }
            .mapNotNull { it.publicValueId }
            .toList()
        val lastMove = moves.lastOrNull() ?: return null
        val count = moves.asReversed().takeWhile { sameMove(it, lastMove) }.size
        return RecursiveMoveStreak(lastMove, count)
    }

    private fun sameMove(first: String, second: String): Boolean = canonicalId(first) == canonicalId(second)

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    fun clearFromProjectedState(state: BattleStateView): BattleStateView {
        if (state.pokemon.none { it.actionConstraints != BattlePokemonActionConstraintView.empty() }) return state
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                pokemon.copyState(actionConstraints = BattlePokemonActionConstraintView.empty())
            },
        )
    }

    private const val MAXIMUM_SNAPSHOT_CONTROL_TURNS = 3
    private const val MAXIMUM_SNAPSHOT_TRAP_TURNS = 5
}

internal object RecursiveHistoryProjector {
    fun project(
        previous: RecursiveActionHistory,
        stateBefore: BattleStateView,
        outcome: PublicTurnProjection,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): RecursiveActionHistory {
        val actorIds = stateBefore.pokemon.filter {
            it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.map(BattlePokemonStateView::battlePokemonId)
        val moveUses = previous.moveUses.toMutableMap()
        val lastMoves = previous.lastMoveByPokemon.toMutableMap()
        val moveStreaks = previous.moveStreakByPokemon.toMutableMap()
        val protectionChains = previous.protectionChainByPokemon.toMutableMap()
        outcome.executedMoveIdsByPokemon.forEach { (actorId, moveId) ->
                val key = RecursiveMoveUseKey(actorId, moveId)
                moveUses[key] = (moveUses[key] ?: 0) + 1
                lastMoves[actorId] = moveId
                val previousStreak = moveStreaks[actorId]
                moveStreaks[actorId] = RecursiveMoveStreak(
                    moveId = moveId,
                    count = if (previousStreak != null && sameMove(previousStreak.moveId, moveId)) {
                        previousStreak.count + 1
                    } else {
                        1
                    },
                )
        }
        actorIds.forEach { actorId ->
            when (outcome.protectionResultsByPokemon[actorId]) {
                true -> protectionChains[actorId] = (previous.protectionChainByPokemon[actorId] ?: 0) + 1
                false, null -> protectionChains.remove(actorId)
            }
        }

        val taunt = decrement(previous.tauntTurnsByPokemon).toMutableMap()
        val trapped = previous.trappedByPokemon.mapNotNull { (pokemonId, lock) ->
            lock.copy(remainingTurns = lock.remainingTurns - 1)
                .takeIf { it.remainingTurns > 0 }
                ?.let { pokemonId to it }
        }.toMap().toMutableMap()
        val encore = previous.encoreByPokemon.mapNotNull { (pokemonId, lock) ->
            lock.copy(remainingTurns = lock.remainingTurns - 1)
                .takeIf { it.remainingTurns > 0 }
                ?.let { pokemonId to it }
        }.toMap().toMutableMap()
        val recharge = linkedSetOf<UUID>()
        val charging = linkedMapOf<UUID, String>()
        val saltCured = previous.saltCuredPokemonIds.toMutableSet()
        val delayedStrikes = previous.delayedStrikes.mapNotNull { strike ->
            strike.copy(remainingTurns = strike.remainingTurns - 1).takeIf { it.remainingTurns > 0 }
        }.toMutableList()

        outcome.controlEffects.forEach { effect ->
            val sourceIndex = outcome.actionOrderPokemonIds.indexOf(effect.sourcePokemonId)
            val targetIndex = outcome.actionOrderPokemonIds.indexOf(effect.targetPokemonId)
            val targetMovedFirst = targetIndex >= 0 && sourceIndex >= 0 && targetIndex < sourceIndex &&
                effect.targetPokemonId in outcome.executedMoveIdsByPokemon
            when (effect.kind) {
                RecursiveControlEffectKind.CHARGE -> effect.valueId?.let { moveId ->
                    charging[effect.targetPokemonId] = moveId
                }
                RecursiveControlEffectKind.RECHARGE -> recharge += effect.targetPokemonId
                RecursiveControlEffectKind.DELAYED_STRIKE -> effect.delayedStrike?.let(delayedStrikes::add)
                RecursiveControlEffectKind.TAUNT -> taunt[effect.targetPokemonId] =
                    if (targetMovedFirst) STANDARD_CONTROL_TURNS else FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT
                RecursiveControlEffectKind.TRAP -> trapped[effect.targetPokemonId] = RecursiveTrapLock(
                    sourcePokemonId = effect.sourcePokemonId,
                    remainingTurns = MINIMUM_TRAP_FUTURE_TURNS,
                )
                RecursiveControlEffectKind.SALT_CURE -> saltCured += effect.targetPokemonId
                RecursiveControlEffectKind.ENCORE -> {
                    val lockedMove = if (targetMovedFirst) {
                        outcome.executedMoveIdsByPokemon[effect.targetPokemonId]
                    } else {
                        previous.lastMoveByPokemon[effect.targetPokemonId]
                    }
                    if (lockedMove != null) {
                        val turns = if (targetMovedFirst) {
                            STANDARD_CONTROL_TURNS
                        } else {
                            FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT
                        }
                        encore[effect.targetPokemonId] = RecursiveEncoreLock(lockedMove, turns)
                    }
                }
            }
        }

        val activeIds = outcome.state.pokemon.filter { it.activeSlot != null && !it.fainted }.mapTo(hashSetOf()) {
            it.battlePokemonId
        }
        taunt.keys.retainAll(activeIds)
        trapped.keys.retainAll(activeIds)
        trapped.entries.removeIf { (_, lock) -> lock.sourcePokemonId !in activeIds }
        encore.keys.retainAll(activeIds)
        recharge.retainAll(activeIds)
        charging.keys.retainAll(activeIds)
        saltCured.retainAll(activeIds)
        lastMoves.keys.retainAll(activeIds)
        moveStreaks.keys.retainAll(activeIds)
        protectionChains.keys.retainAll(activeIds)
        val actedSinceEntry = (previous.actedSinceEntryPokemonIds + outcome.executedMoveIdsByPokemon.keys)
            .filterTo(linkedSetOf()) { it in activeIds }

        return RecursiveActionHistory(
            allySwitchedLastTurn = allyAction.kind == BattleActionKind.SWITCH || BattleSide.ALLY in outcome.switchedSides,
            opponentSwitchedLastTurn = opponentAction.kind == BattleActionKind.SWITCH || BattleSide.OPPONENT in outcome.switchedSides,
            moveUses = moveUses,
            rechargingPokemonIds = recharge,
            chargingMoveByPokemon = charging,
            tauntTurnsByPokemon = taunt,
            encoreByPokemon = encore,
            trappedByPokemon = trapped,
            lastMoveByPokemon = lastMoves,
            moveStreakByPokemon = moveStreaks,
            actedSinceEntryPokemonIds = actedSinceEntry,
            badPoisonTurnsByPokemon = outcome.badPoisonTurnsByPokemon,
            saltCuredPokemonIds = saltCured,
            protectionChainByPokemon = protectionChains,
            delayedStrikes = delayedStrikes,
        )
    }

    private fun decrement(values: Map<UUID, Int>): Map<UUID, Int> = values.mapNotNull { (id, turns) ->
        (turns - 1).takeIf { it > 0 }?.let { id to it }
    }.toMap()

    private fun sameMove(first: String, second: String): Boolean = canonicalId(first) == canonicalId(second)

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val STANDARD_CONTROL_TURNS = 3
    private const val FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT = 2
    private const val MINIMUM_TRAP_FUTURE_TURNS = 4
}

internal object LocalRecursiveSwitchTempo {
    fun adjustment(
        allySwitch: Boolean,
        opponentSwitch: Boolean,
        allyRepeated: Boolean,
        opponentRepeated: Boolean,
    ): Double = switchCost(opponentSwitch, opponentRepeated) - switchCost(allySwitch, allyRepeated)

    private fun switchCost(switched: Boolean, repeated: Boolean): Double = if (!switched) {
        0.0
    } else {
        SWITCH_TEMPO_COST + if (repeated) CONSECUTIVE_SWITCH_COST else 0.0
    }

    private const val SWITCH_TEMPO_COST = 0.15
    private const val CONSECUTIVE_SWITCH_COST = 0.30
}

/** Applies a board-unit opportunity cost to healthy, repeated, pure recovery lines. */
internal object LocalRecursiveMoveHabit {
    fun cost(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        history: RecursiveActionHistory,
    ): Double {
        if (action.kind == BattleActionKind.COMPOSITE) {
            return action.componentActions.sumOf { cost(state, side, it, history) }
        }
        if (action.kind != BattleActionKind.USE_MOVE) return 0.0
        val moveId = action.moveId ?: return 0.0
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 0.0
        val streak = history.moveStreakByPokemon[actor.battlePokemonId] ?: return 0.0
        if (streak.count < MINIMUM_REPEATS || canonicalId(streak.moveId) != canonicalId(moveId)) return 0.0
        val effects = action.moveDetails?.effects?.effects.orEmpty()
        val pureRecovery = effects.isNotEmpty() && effects.all {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
        }
        if (!pureRecovery || actor.hpFraction <= SURVIVAL_HP_THRESHOLD) return 0.0
        val healthyScale = ((actor.hpFraction - SURVIVAL_HP_THRESHOLD) / (1.0 - SURVIVAL_HP_THRESHOLD))
            .coerceIn(0.0, 1.0)
        val repeatPressure = (streak.count - 1).coerceIn(1, MAXIMUM_REPEAT_PRESSURE)
        return healthyScale * repeatPressure * BOARD_COST_PER_REPEAT
    }

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val MINIMUM_REPEATS = 2
    private const val SURVIVAL_HP_THRESHOLD = 0.65
    private const val BOARD_COST_PER_REPEAT = 0.35
    private const val MAXIMUM_REPEAT_PRESSURE = 4
}
