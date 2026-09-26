package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

internal enum class NativeObservedActionOrderStatus {
    DISABLED,
    INSUFFICIENT_EVIDENCE,
    CONSISTENT,
    CONTRADICTED,
}

internal data class NativeObservedActionOrderConditioning(
    val status: NativeObservedActionOrderStatus,
    val comparableTurns: Set<Int>,
)

/**
 * Conditions a hidden native world on public move order without reimplementing turn-order rules.
 *
 * The native ledger comes from Showdown's actual move messages after priority, Trick Room, abilities,
 * items, speed ties and every other callback have resolved. We compare only same-base-priority public
 * pairs and discard ambiguous repeated-actor turns, matching the public observation contract.
 */
internal object NativeObservedActionOrderConditioner {
    fun evaluate(
        tier: BattleTrainerTier,
        publicState: BattleStateView,
        nativeFrame: NativeBattleFrame,
        publicTurnOffset: Int = 0,
    ): NativeObservedActionOrderConditioning {
        val requiredTurns = when (tier) {
            BattleTrainerTier.INTRODUCTORY -> return result(NativeObservedActionOrderStatus.DISABLED)
            BattleTrainerTier.STANDARD -> 2
            BattleTrainerTier.ADVANCED,
            BattleTrainerTier.BOSS,
            -> 1
        }
        val sideByPokemon = publicState.pokemon.associate {
            it.battlePokemonId to it.side
        }
        val nativeByTurn = nativeFrame.executedMoveOrder.groupBy { it.turn - publicTurnOffset }
        val comparableTurns = linkedSetOf<Int>()
        var contradicted = false

        publicState.observedEvents.asSequence()
            .filter { event ->
                event.kind == BattleObservedEventKind.ACTION_ORDER &&
                    event.actorPokemonId != null &&
                    event.baseMovePriority != null &&
                    event.publicValueId != null
            }
            .groupBy(BattleObservedEventView::turn)
            .toSortedMap()
            .forEach { (turn, publicActions) ->
                if (publicActions.groupingBy(BattleObservedEventView::actorPokemonId)
                        .eachCount().any { it.value > 1 }
                ) {
                    return@forEach
                }
                val nativeActions = nativeByTurn[turn].orEmpty()
                if (nativeActions.groupingBy(NativeExecutedMoveFrame::pokemonUuid)
                        .eachCount().any { it.value > 1 }
                ) {
                    return@forEach
                }
                val nativeIndex = nativeActions.withIndex().associate { (index, action) ->
                    NativeMoveKey(UUID.fromString(action.pokemonUuid), nativeId(action.moveId)) to index
                }
                val opponentActions = publicActions.filter {
                    sideByPokemon[it.actorPokemonId] == BattleSide.OPPONENT
                }
                val allyActions = publicActions.filter {
                    sideByPokemon[it.actorPokemonId] == BattleSide.ALLY
                }
                var comparedThisTurn = false
                opponentActions.forEach { opponent ->
                    allyActions.asSequence()
                        .filter { ally -> ally.baseMovePriority == opponent.baseMovePriority }
                        .forEach pair@{ ally ->
                            val opponentIndex = nativeIndex[opponent.moveKey] ?: return@pair
                            val allyIndex = nativeIndex[ally.moveKey] ?: return@pair
                            comparedThisTurn = true
                            val publicOpponentFirst = opponent.sequence < ally.sequence
                            val nativeOpponentFirst = opponentIndex < allyIndex
                            if (publicOpponentFirst != nativeOpponentFirst) contradicted = true
                        }
                }
                if (comparedThisTurn) comparableTurns += turn
            }

        if (comparableTurns.size < requiredTurns) {
            return NativeObservedActionOrderConditioning(
                NativeObservedActionOrderStatus.INSUFFICIENT_EVIDENCE,
                comparableTurns,
            )
        }
        return NativeObservedActionOrderConditioning(
            if (contradicted) {
                NativeObservedActionOrderStatus.CONTRADICTED
            } else {
                NativeObservedActionOrderStatus.CONSISTENT
            },
            comparableTurns,
        )
    }

    private val BattleObservedEventView.moveKey: NativeMoveKey
        get() = NativeMoveKey(requireNotNull(actorPokemonId), nativeId(requireNotNull(publicValueId)))

    private fun result(status: NativeObservedActionOrderStatus) =
        NativeObservedActionOrderConditioning(status, emptySet())

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class NativeMoveKey(
        val pokemonId: UUID,
        val moveId: String,
    )
}
