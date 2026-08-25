package jbro.cobblemon.morebattlecontent.betterai.mechanics

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

internal object LocalStallingProtectionRules {
    const val MECHANIC_FLAG = "stalling_move"
    const val COUNTER_ADVANCE_FLAG = "stall_counter_advance"

    fun isStallingProtection(action: BattleActionCandidate): Boolean =
        action.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.PROTECT_USER } &&
            MECHANIC_FLAG in action.moveDetails?.effects?.mechanicFlags.orEmpty()

    fun nextSuccessProbability(consecutiveSuccessfulUses: Int): Double {
        require(consecutiveSuccessfulUses >= 0)
        var denominator = 1.0
        repeat(consecutiveSuccessfulUses.coerceAtMost(MAX_STALL_EXPONENT)) {
            denominator *= STALL_MULTIPLIER
        }
        return 1.0 / denominator
    }

    fun advancesSharedCounter(action: BattleActionCandidate): Boolean =
        COUNTER_ADVANCE_FLAG in action.moveDetails?.effects?.mechanicFlags.orEmpty()

    fun consecutiveSuccessfulUses(
        state: BattleStateView,
        side: BattleSide,
        actorSlot: Int? = 0,
    ): Int {
        val pokemonId = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == actorSlot && !it.fainted && it.hpFraction > 0.0
        }?.battlePokemonId ?: return 0
        val lastEntrySequence = state.observedEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == pokemonId }
            .maxOfOrNull { it.sequence }
            ?: Long.MIN_VALUE
        val moveTurns = state.observedEvents.asSequence()
            .filter {
                it.sequence > lastEntrySequence &&
                    it.kind == BattleObservedEventKind.MOVE_USED &&
                    it.actorPokemonId == pokemonId
            }
            .map { it.turn }
            .distinct()
            .toSet()
        val latestMoveTurn = moveTurns.maxOrNull() ?: return 0
        val successfulTurns = state.observedEvents.asSequence()
            .filter {
                it.sequence > lastEntrySequence &&
                    it.kind == BattleObservedEventKind.MOVE_OUTCOME &&
                    pokemonId in it.targetPokemonIds &&
                    it.moveOutcome?.kind == BattleMoveOutcomeKind.PROTECTION_STARTED
            }
            .mapTo(hashSetOf()) { it.turn }
        var count = 0
        var turn = latestMoveTurn
        while (turn in moveTurns && turn in successfulTurns) {
            count++
            turn--
        }
        return count
    }

    private const val STALL_MULTIPLIER = 3.0
    private const val MAX_STALL_EXPONENT = 6
}
